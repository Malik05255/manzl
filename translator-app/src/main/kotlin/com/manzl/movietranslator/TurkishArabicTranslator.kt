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
 * Fast on-device Turkish -> Arabic subtitle translation using quantized NLLB-200 distilled 600M.
 *
 * Unlike the previous Hy-MT2/llama.cpp hot path, NLLB is a dedicated translation model and uses
 * encoder output + decoder KV caching. Each subtitle is translated independently so cue coverage
 * and timing remain exact. Suspicious outputs get one context-aware repair pass instead of running
 * a 1.8B LLM across every line.
 */
class TurkishArabicTranslator(private val context: Context) : AutoCloseable {
    private var env: OrtEnvironment? = null
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private var decoderWithPast: OrtSession? = null
    private var tokenizer: NllbTokenizer? = null
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
            Log.e(TAG, "Failed to initialize NLLB", error)
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
        check(isReady()) { "مترجم NLLB التركي ← العربي غير جاهز." }
        val source = cues.filter { it.sourceText.isNotBlank() }.sortedBy { it.startMs }
        if (source.isEmpty()) return@withContext emptyList()

        val cache = HashMap<String, String>()
        val output = ArrayList<SubtitleCue>(source.size)
        source.forEachIndexed { index, cue ->
            currentCoroutineContext().ensureActive()
            val key = normalizeTurkish(cue.sourceText)
            val translated = COMMON_PHRASES[key]
                ?: cache[key]
                ?: translateCueWithQualityGate(
                    source = source,
                    index = index,
                    deadlineAtElapsedRealtimeMs = deadlineAtElapsedRealtimeMs,
                ).also { cache[key] = it }

            output += cue.copy(translatedText = translated)
            onProgress(index + 1, source.size)
        }
        output
    }

    private fun initializeSessions(modelDir: File) {
        close()
        val runtime = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(stableThreadCount(Runtime.getRuntime().availableProcessors()))
            // Three NLLB sessions stay resident. Disabling long-lived arenas prevents the allocator
            // from retaining large temporary buffers after a subtitle is completed.
            setMemoryPatternOptimization(false)
            setCPUArenaAllocator(false)
        }

        try {
            val enc = runtime.createSession(
                File(modelDir, ENCODER_FILENAME).absolutePath,
                options,
            )
            val dec = runtime.createSession(
                File(modelDir, DECODER_FILENAME).absolutePath,
                options,
            )
            val cached = runtime.createSession(
                File(modelDir, DECODER_WITH_PAST_FILENAME).absolutePath,
                options,
            )
            val tok = NllbTokenizer().also { it.initialize(modelDir) }

            env = runtime
            encoder = enc
            decoder = dec
            decoderWithPast = cached
            tokenizer = tok
        } finally {
            options.close()
        }
    }

    private fun translateCueWithQualityGate(
        source: List<SubtitleCue>,
        index: Int,
        deadlineAtElapsedRealtimeMs: Long,
    ): String {
        val cue = source[index]
        val first = cleanArabicCandidate(runInference(cue.sourceText))
        if (!translationNeedsRepair(cue.sourceText, first)) return first

        val remaining = deadlineAtElapsedRealtimeMs - SystemClock.elapsedRealtime()
        if (remaining <= REPAIR_MIN_REMAINING_MS) {
            return first.ifBlank { cue.sourceText.trim() }
        }

        val previous = source.getOrNull(index - 1)?.sourceText.orEmpty()
        val following = source.getOrNull(index + 1)?.sourceText.orEmpty()
        val markedInput = buildString {
            if (previous.isNotBlank()) append("§0§ ").append(previous.take(REPAIR_CONTEXT_CHARS)).append('\n')
            append("§1§ ").append(cue.sourceText.trim()).append('\n')
            if (following.isNotBlank()) append("§2§ ").append(following.take(REPAIR_CONTEXT_CHARS))
        }.trim()

        val repairedBlock = runCatching { runInference(markedInput) }.getOrNull().orEmpty()
        val repaired = extractMarkedCenter(repairedBlock)
        return when {
            !translationNeedsRepair(cue.sourceText, repaired) -> repaired
            first.isNotBlank() && isUsefulArabic(first) -> first
            else -> cue.sourceText.trim()
        }
    }

    private fun runInference(text: String): String {
        val runtime = env ?: error("ONNX Runtime غير جاهز.")
        val enc = encoder ?: error("NLLB encoder غير جاهز.")
        val dec = decoder ?: error("NLLB decoder غير جاهز.")
        val cachedDecoder = decoderWithPast ?: error("NLLB cached decoder غير جاهز.")
        val tok = tokenizer ?: error("NLLB tokenizer غير جاهز.")

        var inputIds = tok.encodeTurkish(text)
        if (inputIds.size > MAX_INPUT_TOKENS) {
            inputIds = inputIds.copyOfRange(0, MAX_INPUT_TOKENS - 1) +
                longArrayOf(NllbTokenizer.EOS_TOKEN_ID)
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

            val firstInputIds = OnnxTensor.createTensor(
                runtime,
                LongBuffer.wrap(longArrayOf(NllbTokenizer.EOS_TOKEN_ID)),
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

                if (nextToken == NllbTokenizer.EOS_TOKEN_ID) {
                    stepResult.close()
                    break
                }

                outputTokens += nextToken
                nextInput = nextToken
                decoderKvCache = extractDecoderOnlyKvCache(
                    stepResult,
                    runtime,
                    expectedNames,
                )
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
        Log.d(TAG, "NLLB ${text.length} chars -> ${result.length} chars in ${SystemClock.elapsedRealtime() - started} ms")
        return result
    }

    private fun argMax(logits: OnnxTensor): Long {
        val buffer = logits.floatBuffer
        val vocabularySize = logits.info.shape.last().toInt()
        var bestValue = Float.NEGATIVE_INFINITY
        var bestIndex = 0L
        for (index in 0 until vocabularySize) {
            val value = buffer.get(index)
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

    private fun extractMarkedCenter(value: String): String {
        val match = CENTER_MARKER.find(value) ?: return ""
        return cleanArabicCandidate(match.groupValues[1])
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
        if (source.length >= 22 && arabic.count { it.isLetter() } < 4) return true
        if (Regex("^(?:بالطبع|إليك|الترجمة العربية)[:، ]").containsMatchIn(arabic.trim())) return true
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
        private const val TAG = "ManzlNLLB"
        private const val ENCODER_FILENAME = "encoder_model_quantized.onnx"
        private const val DECODER_FILENAME = "decoder_model_quantized.onnx"
        private const val DECODER_WITH_PAST_FILENAME = "decoder_with_past_model_quantized.onnx"
        private const val MAX_INPUT_TOKENS = 128
        private const val MAX_OUTPUT_TOKENS = 96
        private const val REPAIR_CONTEXT_CHARS = 90
        private const val REPAIR_MIN_REMAINING_MS = 20_000L
        private val TURKISH_LOCALE = Locale.forLanguageTag("tr")
        private val CENTER_MARKER = Regex("§\\s*1\\s*§(.*?)(?=§\\s*[02]\\s*§|$)", setOf(RegexOption.DOT_MATCHES_ALL))

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

        internal fun stableThreadCount(cores: Int): Int = when {
            cores >= 8 -> 4
            cores >= 6 -> 3
            else -> 2
        }

        internal fun translationNeedsRepairForTest(source: String, arabic: String): Boolean {
            if (arabic.isBlank()) return true
            val letters = arabic.count { it.isLetter() }.coerceAtLeast(1)
            val arabicLetters = arabic.count { it in '\u0600'..'\u06FF' }
            if (arabicLetters < 2 || arabicLetters.toFloat() / letters.toFloat() < 0.60f) return true
            if (source.length >= 22 && arabic.count { it.isLetter() } < 4) return true
            return Regex("^(?:بالطبع|إليك|الترجمة العربية)[:، ]").containsMatchIn(arabic.trim())
        }

        internal fun maxInputTokensForTest(): Int = MAX_INPUT_TOKENS
        internal fun maxOutputTokensForTest(): Int = MAX_OUTPUT_TOKENS
    }
}
