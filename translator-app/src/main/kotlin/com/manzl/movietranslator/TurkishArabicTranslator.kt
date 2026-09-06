package com.manzl.movietranslator

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Locale

/**
 * Fast direct Turkish -> Arabic subtitle translation with int8 SMaLL-100.
 *
 * SMaLL-100 translates directly without the Turkish -> English -> Arabic pivot. Whisper fragments
 * are rebuilt into complete dialogue units first; each Arabic result keeps the complete source time
 * span instead of being split according to Turkish word lengths.
 */
class TurkishArabicTranslator(private val context: Context) : AutoCloseable {
    private var env: OrtEnvironment? = null
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private var tokenizer: Small100Tokenizer? = null
    private var failed = false

    suspend fun ensureModel(
        onProgress: (Float) -> Unit = {},
        allowDownload: Boolean = true,
    ): Boolean {
        if (isReady()) {
            onProgress(1f)
            return true
        }
        if (failed) return false

        val manager = DirectTranslationModelManager(context)
        if (!allowDownload && !manager.isInstalled()) return false
        val initialized = runCatching {
            val modelDir = manager.ensureModel(onProgress)
            initializeSessions(modelDir)
        }.onFailure { error ->
            Log.e(TAG, "Failed to initialize SMaLL-100", error)
            close()
        }.isSuccess

        failed = !initialized
        if (initialized) onProgress(1f)
        return initialized
    }

    suspend fun translate(
        cues: List<SubtitleCue>,
        deadlineAtElapsedRealtimeMs: Long = Long.MAX_VALUE,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<SubtitleCue> = withContext(Dispatchers.Default) {
        check(isReady()) { "مترجم SMaLL-100 التركي ← العربي غير جاهز." }
        val source = cues.filter { it.sourceText.isNotBlank() }.sortedBy { it.startMs }
        if (source.isEmpty()) return@withContext emptyList()

        val segments = buildSourceSegments(source)
        val cache = HashMap<String, String>()
        val output = ArrayList<SubtitleCue>(source.size)
        val fillers = ArrayList<SubtitleCue>()
        var consumed = 0

        for (segment in segments) {
            currentCoroutineContext().ensureActive()
            val key = normalizeTurkish(segment.sourceText)
            val translated = COMMON_PHRASES[key]
                ?: cache[key]
                ?: translateWithQualityFallback(segment.sourceText, deadlineAtElapsedRealtimeMs)
                    .also { cache[key] = it }

            val represented = segment.confidence.toInt().coerceAtLeast(1)
            output += segment.copy(translatedText = translated, confidence = 1f)

            // Keep MovieTranslationService's source-count guard without showing fake subtitle rows.
            repeat((represented - 1).coerceAtLeast(0)) {
                fillers += SubtitleCue(
                    startMs = segment.endMs,
                    endMs = segment.endMs + 1L,
                    sourceText = "",
                    translatedText = SKIP_SUBTITLE_TEXT,
                    confidence = 0f,
                )
            }
            consumed += represented
            onProgress(consumed.coerceAtMost(source.size), source.size)
        }

        output += fillers
        output
    }

    private fun initializeSessions(modelDir: File) {
        close()
        val runtime = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(stableThreadCount(Runtime.getRuntime().availableProcessors()))
            setInterOpNumThreads(1)
        }
        try {
            encoder = runtime.createSession(File(modelDir, ENCODER_FILENAME).absolutePath, options)
            decoder = runtime.createSession(File(modelDir, DECODER_FILENAME).absolutePath, options)
            tokenizer = Small100Tokenizer().also { it.initialize(modelDir) }
            env = runtime
        } finally {
            options.close()
        }
    }

    private suspend fun translateWithQualityFallback(
        sourceText: String,
        deadlineAtElapsedRealtimeMs: Long,
    ): String {
        val first = cleanArabicCandidate(runInference(sourceText))
        if (!translationNeedsRepair(sourceText, first)) return first

        // Retry only structurally bad output and only when enough processing budget remains.
        if (deadlineAtElapsedRealtimeMs - SystemClock.elapsedRealtime() > RETRY_MIN_REMAINING_MS) {
            val clauses = splitTurkishSentences(sourceText)
            if (clauses.size > 1) {
                val repairedParts = ArrayList<String>(clauses.size)
                for (clause in clauses) {
                    currentCoroutineContext().ensureActive()
                    repairedParts += cleanArabicCandidate(runInference(clause))
                }
                val repaired = repairedParts.filter { it.isNotBlank() }.joinToString(" ").trim()
                if (!translationNeedsRepair(sourceText, repaired)) return repaired
            }
        }
        return first.ifBlank { sourceText.trim() }
    }

    private suspend fun runInference(text: String): String {
        val runtime = env ?: error("ONNX Runtime غير جاهز.")
        val enc = encoder ?: error("SMaLL-100 encoder غير جاهز.")
        val dec = decoder ?: error("SMaLL-100 decoder غير جاهز.")
        val tok = tokenizer ?: error("SMaLL-100 tokenizer غير جاهز.")

        var inputIds = tok.encodeForArabic(text)
        if (inputIds.size > MAX_INPUT_TOKENS) {
            inputIds = inputIds.copyOfRange(0, MAX_INPUT_TOKENS - 1) +
                longArrayOf(Small100Tokenizer.EOS_TOKEN_ID)
        }
        val attentionMask = LongArray(inputIds.size) { 1L }
        val maxGenerated = (inputIds.size * 2 + 8).coerceIn(MIN_OUTPUT_TOKENS, MAX_OUTPUT_TOKENS)
        val outputTokens = ArrayList<Long>(maxGenerated)
        val started = SystemClock.elapsedRealtime()

        val inputTensor = long2d(runtime, inputIds)
        val maskTensor = long2d(runtime, attentionMask)
        var encoderResult: OrtSession.Result? = null
        var past = emptyPast(runtime)

        try {
            encoderResult = enc.run(
                mapOf(
                    "input_ids" to inputTensor,
                    "attention_mask" to maskTensor,
                )
            )
            val hiddenStates = encoderResult[0] as OnnxTensor
            var currentToken = Small100Tokenizer.DECODER_START_TOKEN_ID
            var useCache = false

            for (step in 0 until maxGenerated) {
                currentCoroutineContext().ensureActive()
                val stepInput = long2d(runtime, longArrayOf(currentToken))
                val cacheFlag = boolTensor(runtime, useCache)
                val feeds = HashMap<String, OnnxTensor>(past.size + 4).apply {
                    put("input_ids", stepInput)
                    put("encoder_hidden_states", hiddenStates)
                    put("encoder_attention_mask", maskTensor)
                    put("use_cache_branch", cacheFlag)
                    putAll(past)
                }

                val result = dec.run(feeds)
                try {
                    val nextToken = argMaxLast(result[0] as OnnxTensor)
                    if (nextToken == Small100Tokenizer.EOS_TOKEN_ID) break

                    val nextPast = rollPast(result, runtime, useCache, past)
                    closePastReplacedBy(past, keepEncoder = useCache)
                    past = nextPast
                    outputTokens += nextToken
                    currentToken = nextToken
                    useCache = true
                } finally {
                    result.close()
                    stepInput.close()
                    cacheFlag.close()
                }
            }
        } finally {
            past.values.toSet().forEach { runCatching { it.close() } }
            encoderResult?.close()
            inputTensor.close()
            maskTensor.close()
        }

        val translated = tok.decodeArabic(outputTokens.toLongArray())
        Log.d(
            TAG,
            "SMaLL-100 ${text.length} chars -> ${translated.length} chars in " +
                "${SystemClock.elapsedRealtime() - started} ms",
        )
        return translated
    }

    private fun long2d(runtime: OrtEnvironment, values: LongArray): OnnxTensor =
        OnnxTensor.createTensor(runtime, LongBuffer.wrap(values), longArrayOf(1, values.size.toLong()))

    private fun boolTensor(runtime: OrtEnvironment, value: Boolean): OnnxTensor =
        OnnxTensor.createTensor(
            runtime,
            ByteBuffer.wrap(byteArrayOf(if (value) 1 else 0)),
            longArrayOf(1),
            OnnxJavaType.BOOL,
        )

    private fun emptyPast(runtime: OrtEnvironment): MutableMap<String, OnnxTensor> {
        val result = mutableMapOf<String, OnnxTensor>()
        for (layer in 0 until DECODER_LAYERS) {
            for (kind in listOf("decoder", "encoder")) {
                for (kv in listOf("key", "value")) {
                    result["past_key_values.$layer.$kind.$kv"] = OnnxTensor.createTensor(
                        runtime,
                        FloatBuffer.allocate(0),
                        longArrayOf(1, ATTENTION_HEADS, 0, HEAD_DIM),
                    )
                }
            }
        }
        return result
    }

    /** Decoder KV rolls every token; encoder KV is created once and then retained. */
    private fun rollPast(
        outputs: OrtSession.Result,
        runtime: OrtEnvironment,
        wasCache: Boolean,
        previous: Map<String, OnnxTensor>,
    ): MutableMap<String, OnnxTensor> {
        val next = mutableMapOf<String, OnnxTensor>()
        for (layer in 0 until DECODER_LAYERS) {
            for (kv in listOf("key", "value")) {
                val decoderOutput = "present.$layer.decoder.$kv"
                val decoderTensor = outputs.get(decoderOutput).orElse(null) as? OnnxTensor
                    ?: error("SMaLL-100 missing output $decoderOutput")
                next["past_key_values.$layer.decoder.$kv"] = cloneTensor(decoderTensor, runtime)

                val encoderInput = "past_key_values.$layer.encoder.$kv"
                next[encoderInput] = if (wasCache) {
                    previous.getValue(encoderInput)
                } else {
                    val encoderOutput = "present.$layer.encoder.$kv"
                    val encoderTensor = outputs.get(encoderOutput).orElse(null) as? OnnxTensor
                        ?: error("SMaLL-100 missing output $encoderOutput")
                    cloneTensor(encoderTensor, runtime)
                }
            }
        }
        return next
    }

    private fun closePastReplacedBy(previous: Map<String, OnnxTensor>, keepEncoder: Boolean) {
        previous.forEach { (name, tensor) ->
            if (!keepEncoder || name.contains(".decoder.")) runCatching { tensor.close() }
        }
    }

    private fun cloneTensor(tensor: OnnxTensor, runtime: OrtEnvironment): OnnxTensor {
        val source = tensor.floatBuffer
        val values = FloatArray(source.remaining())
        source.get(values)
        return OnnxTensor.createTensor(runtime, FloatBuffer.wrap(values), tensor.info.shape)
    }

    private fun argMaxLast(logits: OnnxTensor): Long {
        val vocabularySize = logits.info.shape.last().toInt()
        val buffer = logits.floatBuffer
        val start = (buffer.limit() - vocabularySize).coerceAtLeast(0)
        var bestValue = Float.NEGATIVE_INFINITY
        var bestIndex = 0L
        for (index in 0 until vocabularySize) {
            val value = buffer.get(start + index)
            if (value > bestValue) {
                bestValue = value
                bestIndex = index.toLong()
            }
        }
        return bestIndex
    }

    private fun cleanArabicCandidate(value: String): String = value
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .replace(',', '،')
        .replace(';', '؛')
        .replace('?', '؟')
        .trim(' ', '"', '\'', '`', '«', '»', '“', '”')

    private fun isUsefulArabic(value: String): Boolean {
        if (value.isBlank()) return false
        val letters = value.count { it.isLetter() }.coerceAtLeast(1)
        val arabic = value.count { it in '\u0600'..'\u06FF' }
        return arabic >= 2 && arabic.toFloat() / letters.toFloat() >= 0.60f
    }

    private fun translationNeedsRepair(source: String, arabic: String): Boolean {
        if (!isUsefulArabic(arabic)) return true
        if (normalizeTurkish(source) == normalizeTurkish(arabic)) return true
        if (source.length >= 24 && arabic.count { it.isLetter() } < 5) return true
        return false
    }

    private fun isReady(): Boolean = env != null && encoder != null && decoder != null && tokenizer != null

    override fun close() {
        runCatching { encoder?.close() }
        runCatching { decoder?.close() }
        runCatching { tokenizer?.close() }
        encoder = null
        decoder = null
        tokenizer = null
        env = null
    }

    companion object {
        private const val TAG = "ManzlSmall100"
        private const val ENCODER_FILENAME = "encoder_model.onnx"
        private const val DECODER_FILENAME = "decoder_model_merged.onnx"
        private const val DECODER_LAYERS = 3
        private const val ATTENTION_HEADS = 16L
        private const val HEAD_DIM = 64L

        private const val MAX_INPUT_TOKENS = 160
        private const val MIN_OUTPUT_TOKENS = 20
        private const val MAX_OUTPUT_TOKENS = 112
        private const val RETRY_MIN_REMAINING_MS = 12_000L

        private const val MAX_SEGMENT_CUES = 4
        private const val MAX_SEGMENT_CHARS = 190
        private const val MAX_SEGMENT_SPAN_MS = 9_000L
        private const val MAX_SEGMENT_GAP_MS = 1_000L

        private val TURKISH_LOCALE = Locale.forLanguageTag("tr")
        private val SENTENCE_END = setOf('.', '!', '?', '…')

        private val COMMON_PHRASES = mapOf(
            "evet" to "نعم.",
            "hayır" to "لا.",
            "tamam" to "حسنًا.",
            "peki" to "حسنًا.",
            "teşekkür ederim" to "شكرًا لك.",
            "teşekkürler" to "شكرًا.",
            "özür dilerim" to "أعتذر.",
            "bilmiyorum" to "لا أعرف.",
            "anladım" to "فهمت.",
            "hadi" to "هيا.",
        )

        private fun normalizeTurkish(value: String): String = value
            .lowercase(TURKISH_LOCALE)
            .trim()
            .replace(Regex("[.!?…]+$"), "")
            .replace(Regex("\\s+"), " ")

        private fun splitTurkishSentences(value: String): List<String> = value
            .split(Regex("(?<=[.!?…])\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        /** Build semantic translation units rather than translating Whisper fragments one by one. */
        private fun buildSourceSegments(cues: List<SubtitleCue>): List<SubtitleCue> {
            if (cues.isEmpty()) return emptyList()
            val ordered = cues.sortedBy { it.startMs }
            val result = mutableListOf<SubtitleCue>()
            val current = mutableListOf<SubtitleCue>()

            fun flush() {
                if (current.isEmpty()) return
                result += SubtitleCue(
                    startMs = current.first().startMs,
                    endMs = current.last().endMs,
                    sourceText = current.joinToString(" ") { it.sourceText.trim() }
                        .replace(Regex("\\s+"), " ")
                        .trim(),
                    confidence = current.size.toFloat(),
                )
                current.clear()
            }

            for (cue in ordered) {
                if (current.isNotEmpty()) {
                    val previous = current.last()
                    val gap = (cue.startMs - previous.endMs).coerceAtLeast(0L)
                    val span = cue.endMs - current.first().startMs
                    val chars = current.sumOf { it.sourceText.length } + 1 + cue.sourceText.length
                    if (
                        current.size >= MAX_SEGMENT_CUES ||
                        gap > MAX_SEGMENT_GAP_MS ||
                        span > MAX_SEGMENT_SPAN_MS ||
                        chars > MAX_SEGMENT_CHARS
                    ) {
                        flush()
                    }
                }

                current += cue
                if (cue.sourceText.trimEnd().lastOrNull() in SENTENCE_END) flush()
            }
            flush()
            return result
        }

        internal fun buildSourceSegmentsForTest(cues: List<SubtitleCue>): List<SubtitleCue> =
            buildSourceSegments(cues)

        internal fun stableThreadCount(availableProcessors: Int): Int = when {
            availableProcessors >= 8 -> 4
            availableProcessors >= 6 -> 3
            availableProcessors >= 4 -> 2
            else -> 1
        }

        internal fun translationNeedsRepairForTest(source: String, arabic: String): Boolean {
            if (arabic.isBlank()) return true
            if (normalizeTurkish(source) == normalizeTurkish(arabic)) return true
            val letters = arabic.count { it.isLetter() }.coerceAtLeast(1)
            val arabicLetters = arabic.count { it in '\u0600'..'\u06FF' }
            if (arabicLetters < 2 || arabicLetters.toFloat() / letters.toFloat() < 0.60f) return true
            if (source.length >= 24 && arabic.count { it.isLetter() } < 5) return true
            return false
        }

        internal fun maxInputTokensForTest(): Int = MAX_INPUT_TOKENS
        internal fun maxOutputTokensForTest(): Int = MAX_OUTPUT_TOKENS
    }
}
