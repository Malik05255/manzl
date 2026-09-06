package com.manzl.movietranslator

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
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Locale

/**
 * Direct on-device Turkish -> Arabic translation with quantized M2M100-418M.
 *
 * Two design choices are deliberate:
 * 1) M2M100 translates Turkish directly to Arabic instead of pivoting through English.
 * 2) Whisper fragments are rebuilt into complete dialogue sentences before translation. The Arabic
 *    sentence keeps the full time span instead of being chopped back into Turkish-shaped pieces.
 *
 * The decoder uses KV caching, so only the newest output token is evaluated after the first step.
 */
class TurkishArabicTranslator(private val context: Context) : AutoCloseable {
    private var env: OrtEnvironment? = null
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private var decoderWithPast: OrtSession? = null
    private var tokenizer: M2M100Tokenizer? = null
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
            Log.e(TAG, "Failed to initialize M2M100", error)
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
        check(isReady()) { "مترجم M2M100 التركي ← العربي غير جاهز." }
        val source = cues.filter { it.sourceText.isNotBlank() }.sortedBy { it.startMs }
        if (source.isEmpty()) return@withContext emptyList()

        val segments = buildSourceSegments(source)
        val cache = HashMap<String, String>()
        val output = ArrayList<SubtitleCue>(segments.size)
        var consumedSourceCues = 0

        segments.forEach { segment ->
            currentCoroutineContext().ensureActive()
            val key = normalizeTurkish(segment.sourceText)
            val translated = COMMON_PHRASES[key]
                ?: cache[key]
                ?: translateWithQualityFallback(
                    sourceText = segment.sourceText,
                    deadlineAtElapsedRealtimeMs = deadlineAtElapsedRealtimeMs,
                ).also { cache[key] = it }

            output += segment.copy(translatedText = translated)
            consumedSourceCues += segment.confidence.toInt().coerceAtLeast(1)
            onProgress(consumedSourceCues.coerceAtMost(source.size), source.size)
        }

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
            val enc = runtime.createSession(File(modelDir, ENCODER_FILENAME).absolutePath, options)
            val dec = runtime.createSession(File(modelDir, DECODER_FILENAME).absolutePath, options)
            val cached = runtime.createSession(File(modelDir, DECODER_WITH_PAST_FILENAME).absolutePath, options)
            val tok = M2M100Tokenizer().also { it.initialize(modelDir) }

            env = runtime
            encoder = enc
            decoder = dec
            decoderWithPast = cached
            tokenizer = tok
        } finally {
            options.close()
        }
    }

    private fun translateWithQualityFallback(
        sourceText: String,
        deadlineAtElapsedRealtimeMs: Long,
    ): String {
        val first = cleanArabicCandidate(runInference(sourceText))
        if (!translationNeedsRepair(sourceText, first)) return first

        // Only pay for a second pass when the first output is structurally bad. Splitting at real
        // Turkish sentence boundaries is safer than feeding artificial markers to a translation model.
        val remaining = deadlineAtElapsedRealtimeMs - SystemClock.elapsedRealtime()
        if (remaining > RETRY_MIN_REMAINING_MS) {
            val clauses = splitTurkishSentences(sourceText)
            if (clauses.size > 1) {
                val repaired = clauses.joinToString(" ") { clause ->
                    cleanArabicCandidate(runInference(clause))
                }.trim()
                if (!translationNeedsRepair(sourceText, repaired)) return repaired
            }
        }

        return first.ifBlank { sourceText.trim() }
    }

    private fun runInference(text: String): String {
        val runtime = env ?: error("ONNX Runtime غير جاهز.")
        val enc = encoder ?: error("M2M100 encoder غير جاهز.")
        val dec = decoder ?: error("M2M100 decoder غير جاهز.")
        val cachedDecoder = decoderWithPast ?: error("M2M100 cached decoder غير جاهز.")
        val tok = tokenizer ?: error("M2M100 tokenizer غير جاهز.")

        var inputIds = tok.encodeTurkish(text)
        if (inputIds.size > MAX_INPUT_TOKENS) {
            inputIds = inputIds.copyOfRange(0, MAX_INPUT_TOKENS - 1) +
                longArrayOf(M2M100Tokenizer.EOS_TOKEN_ID)
        }
        val attentionMask = LongArray(inputIds.size) { 1L }
        val outputTokens = mutableListOf<Long>()
        val started = SystemClock.elapsedRealtime()

        val inputTensor = OnnxTensor.createTensor(
            runtime,
            LongBuffer.wrap(inputIds),
            longArrayOf(1, inputIds.size.toLong()),
        )
        val maskTensor = OnnxTensor.createTensor(
            runtime,
            LongBuffer.wrap(attentionMask),
            longArrayOf(1, attentionMask.size.toLong()),
        )

        var encoderResult: OrtSession.Result? = null
        val encoderKvCache = mutableMapOf<String, OnnxTensor>()
        var decoderKvCache = mutableMapOf<String, OnnxTensor>()

        try {
            encoderResult = enc.run(
                mapOf(
                    "input_ids" to inputTensor,
                    "attention_mask" to maskTensor,
                )
            )
            val hiddenStates = encoderResult[0] as OnnxTensor
            val targetLanguageToken = tok.arabicTargetTokenId()

            // M2M100 generation starts with EOS, then forces the target-language token as the first
            // generated token. The initial decoder call also materializes encoder/self KV caches.
            val firstInputIds = OnnxTensor.createTensor(
                runtime,
                LongBuffer.wrap(longArrayOf(M2M100Tokenizer.EOS_TOKEN_ID)),
                longArrayOf(1, 1),
            )
            val firstInputs = mutableMapOf<String, OnnxTensor>(
                "input_ids" to firstInputIds,
                "encoder_hidden_states" to hiddenStates,
                "encoder_attention_mask" to maskTensor,
            )
            val firstResult = dec.run(firstInputs)
            outputTokens += targetLanguageToken

            try {
                val expectedNames = cachedDecoder.inputNames
                val initialCache = extractKvCache(firstResult, runtime, expectedNames)
                initialCache.forEach { (name, tensor) ->
                    if (name.contains(".encoder.")) encoderKvCache[name] = tensor
                    else decoderKvCache[name] = tensor
                }
            } finally {
                firstResult.close()
                firstInputIds.close()
            }

            val expectedNames = cachedDecoder.inputNames
            var nextInput = targetLanguageToken
            var generated = 0
            while (generated < MAX_OUTPUT_TOKENS) {
                currentCoroutineContext().ensureActive()
                generated++
                val stepInputIds = OnnxTensor.createTensor(
                    runtime,
                    LongBuffer.wrap(longArrayOf(nextInput)),
                    longArrayOf(1, 1),
                )
                val stepInputs = mutableMapOf<String, OnnxTensor>("input_ids" to stepInputIds)
                if ("encoder_hidden_states" in expectedNames) {
                    stepInputs["encoder_hidden_states"] = hiddenStates
                }
                if ("encoder_attention_mask" in expectedNames) {
                    stepInputs["encoder_attention_mask"] = maskTensor
                }
                stepInputs.putAll(decoderKvCache)
                stepInputs.putAll(encoderKvCache)

                val stepResult = cachedDecoder.run(stepInputs)
                val logits = stepResult[0] as OnnxTensor
                val nextToken = argMax(logits)

                decoderKvCache.values.forEach { runCatching { it.close() } }
                decoderKvCache = mutableMapOf()
                stepInputIds.close()

                if (nextToken == M2M100Tokenizer.EOS_TOKEN_ID) {
                    stepResult.close()
                    break
                }

                outputTokens += nextToken
                nextInput = nextToken
                decoderKvCache = extractDecoderOnlyKvCache(stepResult, runtime, expectedNames)
                stepResult.close()
            }
        } finally {
            decoderKvCache.values.forEach { runCatching { it.close() } }
            encoderKvCache.values.forEach { runCatching { it.close() } }
            encoderResult?.close()
            inputTensor.close()
            maskTensor.close()
        }

        val result = tok.decodeArabic(outputTokens.toLongArray())
        Log.d(
            TAG,
            "M2M100 ${text.length} chars -> ${result.length} chars in " +
                "${SystemClock.elapsedRealtime() - started} ms",
        )
        return result
    }

    private fun argMax(logits: OnnxTensor): Long {
        val buffer = logits.floatBuffer
        val vocabularySize = logits.info.shape.last().toInt()
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

    private fun extractKvCache(
        outputs: OrtSession.Result,
        runtime: OrtEnvironment,
        expectedInputNames: Set<String>,
    ): MutableMap<String, OnnxTensor> {
        val result = mutableMapOf<String, OnnxTensor>()
        for ((name, _) in outputs) {
            if (!name.startsWith("present")) continue
            val inputName = name.replace("present", "past_key_values")
            if (inputName !in expectedInputNames) continue
            val tensor = outputs.get(name).orElse(null) as? OnnxTensor ?: continue
            result[inputName] = cloneTensor(tensor, runtime)
        }
        return result
    }

    private fun extractDecoderOnlyKvCache(
        outputs: OrtSession.Result,
        runtime: OrtEnvironment,
        expectedInputNames: Set<String>,
    ): MutableMap<String, OnnxTensor> {
        val result = mutableMapOf<String, OnnxTensor>()
        for ((name, _) in outputs) {
            if (!name.startsWith("present") || name.contains(".encoder.")) continue
            val inputName = name.replace("present", "past_key_values")
            if (inputName !in expectedInputNames) continue
            val tensor = outputs.get(name).orElse(null) as? OnnxTensor ?: continue
            result[inputName] = cloneTensor(tensor, runtime)
        }
        return result
    }

    private fun cloneTensor(
        tensor: OnnxTensor,
        runtime: OrtEnvironment,
    ): OnnxTensor {
        val buffer = tensor.floatBuffer
        val data = FloatArray(buffer.remaining())
        buffer.get(data)
        return OnnxTensor.createTensor(runtime, FloatBuffer.wrap(data), tensor.info.shape)
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

    private fun isReady(): Boolean =
        env != null && encoder != null && decoder != null && decoderWithPast != null && tokenizer != null

    override fun close() {
        runCatching { encoder?.close() }
        runCatching { decoder?.close() }
        runCatching { decoderWithPast?.close() }
        runCatching { tokenizer?.close() }
        encoder = null
        decoder = null
        decoderWithPast = null
        tokenizer = null
        env = null
    }

    companion object {
        private const val TAG = "ManzlM2M100"
        private const val ENCODER_FILENAME = "encoder_model_quantized.onnx"
        private const val DECODER_FILENAME = "decoder_model_quantized.onnx"
        private const val DECODER_WITH_PAST_FILENAME = "decoder_with_past_model_quantized.onnx"
        private const val MAX_INPUT_TOKENS = 192
        private const val MAX_OUTPUT_TOKENS = 144
        private const val RETRY_MIN_REMAINING_MS = 25_000L

        private const val MAX_SEGMENT_CUES = 5
        private const val MAX_SEGMENT_CHARS = 260
        private const val MAX_SEGMENT_SPAN_MS = 12_000L
        private const val MAX_SEGMENT_GAP_MS = 1_200L

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

        /**
         * Rebuilds Whisper fragments into semantic translation units. Confidence is repurposed
         * internally to carry how many original cues are represented, so progress remains based on
         * the source-cue count while the final subtitle uses sentence-level timing.
         */
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
                    val newSpan = cue.endMs - current.first().startMs
                    val newChars = current.sumOf { it.sourceText.length } + 1 + cue.sourceText.length
                    val exceedsLimits = current.size >= MAX_SEGMENT_CUES ||
                        gap > MAX_SEGMENT_GAP_MS ||
                        newSpan > MAX_SEGMENT_SPAN_MS ||
                        newChars > MAX_SEGMENT_CHARS
                    if (exceedsLimits) flush()
                }

                current += cue
                val endsSentence = cue.sourceText.trimEnd().lastOrNull() in SENTENCE_END
                if (endsSentence) flush()
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
