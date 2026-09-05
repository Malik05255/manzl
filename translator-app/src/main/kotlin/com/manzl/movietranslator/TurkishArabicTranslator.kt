package com.manzl.movietranslator

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Human-oriented Turkish -> Arabic subtitle translator.
 *
 * Qwen is the primary direct Turkish -> Arabic path. The translator dynamically enlarges scene
 * batches when measured throughput predicts a deadline miss, so we preserve direct semantic
 * translation for as much of the movie as possible. ML Kit is kept only as a last-resort tail or
 * device fallback; it is never the preferred path.
 */
class TurkishArabicTranslator(private val context: Context) : AutoCloseable {
    private var directModel: LlamaModel? = null
    private var directFailed = false
    private var fallbackTranslator: Translator? = null
    private val powerManager = context.getSystemService(PowerManager::class.java)

    /** Returns true when the direct Turkish -> Arabic model is active. */
    suspend fun ensureModel(
        onProgress: (Float) -> Unit = {},
        allowDownload: Boolean = true,
    ): Boolean {
        if (directModel != null) {
            onProgress(1f)
            return true
        }

        if (!directFailed) {
            val manager = DirectTranslationModelManager(context)
            if (!allowDownload && !manager.isInstalled()) {
                directFailed = true
            } else {
                val loaded = runCatching {
                    val modelFile = manager.ensureModel(onProgress)
                    directModel = Llama.loadModel(
                        modelPath = modelFile.absolutePath,
                        config = LlamaConfig(
                            contextSize = 2_048,
                            // The free Android AAR is CPU/NEON. Four threads avoid pushing sustained
                            // inference onto efficiency cores on heterogeneous Snapdragon CPUs.
                            threads = 4,
                            gpuLayers = 0,
                            // Translation quality comes from context/prompting, not randomness.
                            temperature = 0.25f,
                            topP = 0.85f,
                            topK = 20,
                            seed = 42,
                        ),
                    )
                }.isSuccess
                if (loaded && directModel != null) {
                    onProgress(1f)
                    return true
                }
                directFailed = true
            }
        }

        ensureFallbackModel()
        onProgress(1f)
        return false
    }

    /** Downloads the small emergency translator before the processing clock starts. */
    suspend fun prepareFallbackModel() {
        ensureFallbackModel()
    }

    suspend fun translate(
        cues: List<SubtitleCue>,
        deadlineAtElapsedRealtimeMs: Long = Long.MAX_VALUE,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<SubtitleCue> {
        if (cues.isEmpty()) return emptyList()

        val semanticCues = mergeSemanticCues(cues)
        val result = ArrayList<SubtitleCue>(semanticCues.size)
        var cursor = 0
        var observedMs = 0L
        var observedCues = 0
        var compressed = false

        while (cursor < semanticCues.size) {
            thermalYieldIfNeeded()

            val now = SystemClock.elapsedRealtime()
            val remainingMs = deadlineAtElapsedRealtimeMs - now
            val remainingCues = semanticCues.size - cursor

            if (!compressed && observedCues >= MIN_CALIBRATION_CUES && remainingMs > 0L) {
                val averageMsPerCue = observedMs.toDouble() / observedCues.toDouble()
                val projectedMs = averageMsPerCue * remainingCues.toDouble()
                if (projectedMs > remainingMs.toDouble() * COMPRESSION_TRIGGER_RATIO) {
                    compressed = true
                }
            }

            val batch = buildNextBatch(semanticCues, cursor, compressed)
            val recentContext = result.takeLast(RECENT_CONTEXT_CUES)
            val startedAt = SystemClock.elapsedRealtime()

            val translated = if (remainingMs <= HARD_FALLBACK_TAIL_MS) {
                // Absolute deadline safety valve. This should affect only a small tail on a slow or
                // thermally throttled device; the main movie remains direct Turkish -> Arabic.
                translateFallbackBatch(batch)
            } else {
                translateBatch(
                    batch = batch,
                    recentContext = recentContext,
                    deadlineAtElapsedRealtimeMs = deadlineAtElapsedRealtimeMs,
                    compressed = compressed,
                )
            }

            val tookMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
            observedMs += tookMs
            observedCues += batch.size
            result += translated
            cursor += batch.size
            onProgress(cursor, semanticCues.size)
        }

        return result.sortedBy { it.startMs }
    }

    private suspend fun translateBatch(
        batch: List<SubtitleCue>,
        recentContext: List<SubtitleCue>,
        deadlineAtElapsedRealtimeMs: Long,
        compressed: Boolean,
    ): List<SubtitleCue> {
        if (batch.size == 1) {
            fixedHumanPhrase(batch.first())?.let { return listOf(it) }
        }

        val model = directModel
        if (model != null && !directFailed) {
            val direct = runCatching {
                translateDirectBatch(
                    model = model,
                    batch = batch,
                    recentContext = recentContext,
                    compressed = compressed,
                    repairMode = false,
                )
            }.getOrNull()

            if (direct != null && direct.size == batch.size && direct.all { hasUsefulArabic(it.translatedText) }) {
                return qualityRepairIfNeeded(
                    model = model,
                    sourceBatch = batch,
                    translatedBatch = direct,
                    recentContext = recentContext,
                    deadlineAtElapsedRealtimeMs = deadlineAtElapsedRealtimeMs,
                    compressed = compressed,
                )
            }

            // Marker fidelity can occasionally degrade on a complex scene. Retry smaller direct
            // batches only when enough time remains; otherwise use the emergency translator rather
            // than letting recursive retries destroy the global time budget.
            if (batch.size > 1 &&
                deadlineAtElapsedRealtimeMs - SystemClock.elapsedRealtime() > SPLIT_RETRY_MIN_MS
            ) {
                val middle = batch.size / 2
                val left = translateBatch(
                    batch.subList(0, middle),
                    recentContext,
                    deadlineAtElapsedRealtimeMs,
                    compressed,
                )
                val rightContext = (recentContext + left).takeLast(RECENT_CONTEXT_CUES)
                val right = translateBatch(
                    batch.subList(middle, batch.size),
                    rightContext,
                    deadlineAtElapsedRealtimeMs,
                    compressed,
                )
                return left + right
            }
        }

        return translateFallbackBatch(batch)
    }

    private suspend fun qualityRepairIfNeeded(
        model: LlamaModel,
        sourceBatch: List<SubtitleCue>,
        translatedBatch: List<SubtitleCue>,
        recentContext: List<SubtitleCue>,
        deadlineAtElapsedRealtimeMs: Long,
        compressed: Boolean,
    ): List<SubtitleCue> {
        if (compressed ||
            deadlineAtElapsedRealtimeMs - SystemClock.elapsedRealtime() <= QUALITY_REPAIR_MIN_MS
        ) {
            return translatedBatch
        }

        val suspicious = sourceBatch.indices
            .filter { index -> needsQualityRepair(sourceBatch[index], translatedBatch[index]) }
            .take(MAX_QUALITY_REPAIRS_PER_BATCH)
        if (suspicious.isEmpty()) return translatedBatch

        val repaired = translatedBatch.toMutableList()
        for (index in suspicious) {
            if (deadlineAtElapsedRealtimeMs - SystemClock.elapsedRealtime() <= QUALITY_REPAIR_MIN_MS) break

            val localContext = (recentContext + repaired.take(index)).takeLast(RECENT_CONTEXT_CUES)
            val retry = runCatching {
                translateDirectBatch(
                    model = model,
                    batch = listOf(sourceBatch[index]),
                    recentContext = localContext,
                    compressed = false,
                    repairMode = true,
                )
            }.getOrNull()?.firstOrNull()

            if (retry != null &&
                hasUsefulArabic(retry.translatedText) &&
                !needsQualityRepair(sourceBatch[index], retry)
            ) {
                repaired[index] = retry
            }
        }
        return repaired
    }

    private suspend fun translateDirectBatch(
        model: LlamaModel,
        batch: List<SubtitleCue>,
        recentContext: List<SubtitleCue>,
        compressed: Boolean,
        repairMode: Boolean,
    ): List<SubtitleCue>? {
        val prompt = buildString {
            appendLine("/no_think")
            if (recentContext.isNotEmpty()) {
                appendLine("سياق سابق للفهم فقط، لا تعِد إخراجه:")
                recentContext.forEach { cue ->
                    append("TR: ").append(cue.sourceText.take(120))
                        .append(" | AR: ").append(cue.translatedText.take(120)).append('\n')
                }
            }
            if (repairMode) {
                appendLine("أصلح هذا السطر: افهم التركية أولًا ثم اكتب المقابل العربي الطبيعي، بلا حرفية أو شرح:")
            } else {
                appendLine("ترجم الحوار المطلوب مباشرة، وحافظ على كل علامة:")
            }
            batch.forEachIndexed { index, cue ->
                append(marker(index)).append(' ').append(cue.sourceText.trim()).append('\n')
            }
        }

        val sourceChars = batch.sumOf { it.sourceText.length }
        val tokenCap = if (compressed) 560 else 640
        val maxTokens = (sourceChars * 2 / 3 + 80).coerceIn(112, tokenCap)
        val response = Llama.complete(
            model = model,
            prompt = prompt,
            systemPrompt = DIRECT_SYSTEM_PROMPT,
            maxTokens = maxTokens,
        ).text

        val pieces = parseMarkedTranslation(sanitizeModelOutput(response), batch.size) ?: return null
        return batch.mapIndexed { index, cue ->
            val arabic = cleanArabic(pieces[index])
            cue.copy(translatedText = arabic.ifBlank { cue.sourceText })
        }
    }

    private suspend fun translateFallbackBatch(batch: List<SubtitleCue>): List<SubtitleCue> {
        val translator = ensureFallbackModel()
        if (batch.size == 1) {
            val cue = batch.first()
            fixedHumanPhrase(cue)?.let { return listOf(it) }
            val arabic = cleanArabic(translator.translate(cue.sourceText).await())
            return listOf(cue.copy(translatedText = arabic.ifBlank { cue.sourceText }))
        }

        val payload = buildString {
            batch.forEachIndexed { index, cue ->
                append(marker(index)).append(' ').append(cue.sourceText.trim()).append('\n')
            }
        }
        val translated = translator.translate(payload).await()
        val pieces = parseMarkedTranslation(translated, batch.size)
        if (pieces != null) {
            return batch.mapIndexed { index, cue ->
                val arabic = cleanArabic(pieces[index])
                cue.copy(translatedText = arabic.ifBlank { cue.sourceText })
            }
        }

        return batch.map { cue ->
            fixedHumanPhrase(cue) ?: run {
                val arabic = cleanArabic(translator.translate(cue.sourceText).await())
                cue.copy(translatedText = arabic.ifBlank { cue.sourceText })
            }
        }
    }

    private suspend fun ensureFallbackModel(): Translator {
        val existing = fallbackTranslator
        if (existing != null) return existing

        val created = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.TURKISH)
                .setTargetLanguage(TranslateLanguage.ARABIC)
                .build(),
        )
        try {
            created.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        } catch (error: Throwable) {
            created.close()
            throw error
        }
        fallbackTranslator = created
        return created
    }

    private fun buildNextBatch(
        cues: List<SubtitleCue>,
        startIndex: Int,
        compressed: Boolean,
    ): List<SubtitleCue> {
        val maxSize = if (compressed) COMPRESSED_BATCH_SIZE else NORMAL_BATCH_SIZE
        val maxChars = if (compressed) COMPRESSED_BATCH_CHARS else NORMAL_BATCH_CHARS
        val batch = mutableListOf<SubtitleCue>()
        var chars = 0

        for (index in startIndex until cues.size) {
            val cue = cues[index]
            val previous = batch.lastOrNull()
            if (batch.isNotEmpty()) {
                val sceneBreak = previous != null && cue.startMs - previous.endMs > MAX_CONTEXT_GAP_MS
                val tooLarge = batch.size >= maxSize || chars + cue.sourceText.length > maxChars
                if (sceneBreak || tooLarge) break
            }
            batch += cue
            chars += cue.sourceText.length
        }
        return batch.ifEmpty { listOf(cues[startIndex]) }
    }

    private fun parseMarkedTranslation(text: String, expected: Int): List<String>? {
        val positions = MARKER_REGEX.findAll(text).toList()
        if (positions.size < expected) return null
        val pieces = MutableList(expected) { "" }

        positions.forEachIndexed { positionIndex, match ->
            val index = match.groupValues[1].toIntOrNull() ?: return@forEachIndexed
            if (index !in 0 until expected) return@forEachIndexed
            val start = match.range.last + 1
            val end = if (positionIndex + 1 < positions.size) positions[positionIndex + 1].range.first else text.length
            pieces[index] = text.substring(start, end).trim()
        }
        return if (pieces.all { it.isNotBlank() }) pieces else null
    }

    private fun marker(index: Int): String = "§$index§"

    private fun sanitizeModelOutput(value: String): String = value
        .replace(THINK_BLOCK, " ")
        .replace("```arabic", "", ignoreCase = true)
        .replace("```", "")
        .trim()

    private fun hasUsefulArabic(value: String): Boolean {
        if (value.isBlank()) return false
        val letters = value.count { it.isLetter() }.coerceAtLeast(1)
        val arabic = value.count { it in '\u0600'..'\u06FF' }
        return arabic.toFloat() / letters.toFloat() >= 0.45f
    }

    private fun needsQualityRepair(source: SubtitleCue, translated: SubtitleCue): Boolean =
        translationNeedsRepair(source.sourceText, translated.translatedText)

    private fun fixedHumanPhrase(cue: SubtitleCue): SubtitleCue? {
        val key = cue.sourceText
            .lowercase(TURKISH_LOCALE)
            .trim()
            .replace(Regex("[.!?…]+$"), "")
            .replace(Regex("\\s+"), " ")
        val arabic = COMMON_PHRASES[key] ?: return null
        return cue.copy(translatedText = arabic)
    }

    private suspend fun thermalYieldIfNeeded() {
        when {
            powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL -> delay(500L)
            powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> delay(120L)
        }
    }

    override fun close() {
        directModel?.let { runCatching { Llama.releaseModel(it) } }
        directModel = null
        fallbackTranslator?.close()
        fallbackTranslator = null
    }

    companion object {
        private const val NORMAL_BATCH_SIZE = 8
        private const val NORMAL_BATCH_CHARS = 900
        private const val COMPRESSED_BATCH_SIZE = 12
        private const val COMPRESSED_BATCH_CHARS = 1_300
        private const val MAX_CONTEXT_GAP_MS = 4_500L
        private const val MAX_SEMANTIC_GAP_MS = 900L
        private const val MAX_SEMANTIC_DURATION_MS = 6_500L
        private const val MAX_SEMANTIC_CHARS = 140
        private const val RECENT_CONTEXT_CUES = 2
        private const val MIN_CALIBRATION_CUES = 12
        private const val COMPRESSION_TRIGGER_RATIO = 0.88
        private const val HARD_FALLBACK_TAIL_MS = 25_000L
        private const val SPLIT_RETRY_MIN_MS = 40_000L
        private const val QUALITY_REPAIR_MIN_MS = 90_000L
        private const val MAX_QUALITY_REPAIRS_PER_BATCH = 2

        private val TURKISH_LOCALE = Locale.forLanguageTag("tr")
        private val MARKER_REGEX = Regex("§(\\d+)§")
        private val SENTENCE_END = Regex("[.!?…]\\s*$")
        private val SPACE_BEFORE_PUNCTUATION = Regex("\\s+([،؛:,.!?؟])")
        private val REPEATED_SPACES = Regex("\\s+")
        private val THINK_BLOCK = Regex("<think>.*?</think>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val LATIN_WORD = Regex("\\b[A-Za-zÇĞİÖŞÜçğıöşü]{3,}\\b")
        private val MODEL_PREFACE = Regex("^(بالطبع|إليك|الترجمة(?: العربية)?[:：]?)\\s*", RegexOption.IGNORE_CASE)

        private val DIRECT_SYSTEM_PROMPT = """
            /no_think
            أنت مترجم أفلام محترف من التركية إلى العربية. افهم المقصود أولًا ثم اكتب ما سيقوله مترجم بشري عربي، لا ترجمة كلمة بكلمة.
            حافظ على كل معنى مهم، الأسماء، العلاقات، النبرة، المزاح، السخرية، الشتائم والتودد، ولا تلخص الحوار ولا تخترع معنى.
            استخدم عربية فصحى طبيعية قصيرة مناسبة للترجمة على الشاشة، ولا تخمّن جنس المتحدث إذا لم يوضحه السياق.
            أعد علامات §n§ نفسها وبالترتيب، وبعد كل علامة العربية فقط. لا تشرح ولا تذكر أنك تترجم.
        """.trimIndent()

        // Keep only context-stable phrases. Ambiguous replies are deliberately left to Qwen so
        // tone and scene context can decide the human Arabic wording.
        private val COMMON_PHRASES = mapOf(
            "evet" to "نعم.",
            "hayır" to "لا.",
            "teşekkürler" to "شكرًا.",
            "teşekkür ederim" to "شكرًا لك.",
            "sağ ol" to "شكرًا لك.",
            "lütfen" to "من فضلك.",
            "özür dilerim" to "أعتذر.",
            "affedersin" to "عذرًا.",
            "merhaba" to "مرحبًا.",
            "günaydın" to "صباح الخير.",
            "iyi geceler" to "ليلة سعيدة.",
            "görüşürüz" to "إلى اللقاء.",
            "bilmiyorum" to "لا أعرف.",
            "anladım" to "فهمت.",
            "anlamadım" to "لم أفهم.",
        )

        internal fun mergeSemanticCuesForTest(cues: List<SubtitleCue>): List<SubtitleCue> =
            mergeSemanticCues(cues)

        internal fun buildContextBatchesForTest(
            cues: List<SubtitleCue>,
            compressed: Boolean = false,
        ): List<List<SubtitleCue>> {
            if (cues.isEmpty()) return emptyList()
            val batches = mutableListOf<List<SubtitleCue>>()
            var cursor = 0
            while (cursor < cues.size) {
                val maxSize = if (compressed) COMPRESSED_BATCH_SIZE else NORMAL_BATCH_SIZE
                val maxChars = if (compressed) COMPRESSED_BATCH_CHARS else NORMAL_BATCH_CHARS
                val batch = mutableListOf<SubtitleCue>()
                var chars = 0
                for (index in cursor until cues.size) {
                    val cue = cues[index]
                    val previous = batch.lastOrNull()
                    if (batch.isNotEmpty()) {
                        val sceneBreak = previous != null && cue.startMs - previous.endMs > MAX_CONTEXT_GAP_MS
                        val tooLarge = batch.size >= maxSize || chars + cue.sourceText.length > maxChars
                        if (sceneBreak || tooLarge) break
                    }
                    batch += cue
                    chars += cue.sourceText.length
                }
                if (batch.isEmpty()) batch += cues[cursor]
                batches += batch
                cursor += batch.size
            }
            return batches
        }

        internal fun translationNeedsRepairForTest(source: String, arabic: String): Boolean =
            translationNeedsRepair(source, arabic)

        private fun translationNeedsRepair(source: String, arabic: String): Boolean {
            val clean = arabic.trim()
            if (clean.isBlank()) return true
            if (MODEL_PREFACE.containsMatchIn(clean)) return true
            if (source.length >= 18 && clean.length < 4) return true
            if (clean.length > source.length * 3 + 80) return true
            if (LATIN_WORD.findAll(clean).count() >= 3) return true

            val letters = clean.count { it.isLetter() }.coerceAtLeast(1)
            val arabicLetters = clean.count { it in '\u0600'..'\u06FF' }
            return arabicLetters.toDouble() / letters.toDouble() < 0.55
        }

        private fun mergeSemanticCues(cues: List<SubtitleCue>): List<SubtitleCue> {
            if (cues.isEmpty()) return emptyList()
            val sorted = cues.sortedBy { it.startMs }
            val result = mutableListOf<SubtitleCue>()
            var current = sorted.first()

            for (next in sorted.drop(1)) {
                val gap = (next.startMs - current.endMs).coerceAtLeast(0L)
                val combinedDuration = next.endMs - current.startMs
                val combinedText = "${current.sourceText.trim()} ${next.sourceText.trim()}".trim()
                val currentHasSentenceEnd = SENTENCE_END.containsMatchIn(current.sourceText)
                val canMerge = !currentHasSentenceEnd &&
                    gap <= MAX_SEMANTIC_GAP_MS &&
                    combinedDuration <= MAX_SEMANTIC_DURATION_MS &&
                    combinedText.length <= MAX_SEMANTIC_CHARS

                if (canMerge) {
                    current = SubtitleCue(
                        startMs = current.startMs,
                        endMs = next.endMs,
                        sourceText = combinedText,
                        translatedText = "",
                        confidence = minOf(current.confidence, next.confidence),
                    )
                } else {
                    result += current
                    current = next
                }
            }
            result += current
            return result
        }

        private fun cleanArabic(value: String): String = value
            .trim()
            .replace(REPEATED_SPACES, " ")
            .replace(SPACE_BEFORE_PUNCTUATION, "$1")
            .replace(',', '،')
            .replace(';', '؛')
            .replace('?', '؟')
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value ->
        if (continuation.isActive) continuation.resume(value)
    }
    addOnFailureListener { error ->
        if (continuation.isActive) continuation.resumeWithException(error)
    }
    addOnCanceledListener { continuation.cancel() }
}
