package com.manzl.movietranslator

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Direct Turkish -> Arabic film subtitle translator.
 *
 * The main rule is quality before fallback: every spoken line is translated by the local direct
 * model. Time pressure may enlarge batches and disable no essential meaning, but it never swaps the
 * remaining movie to a weaker translator. If marker fidelity fails, the batch is split and retried.
 */
class TurkishArabicTranslator(private val context: Context) : AutoCloseable {
    private var directModel: LlamaModel? = null
    private var directFailed = false
    private val powerManager = context.getSystemService(PowerManager::class.java)

    suspend fun ensureModel(
        onProgress: (Float) -> Unit = {},
        allowDownload: Boolean = true,
    ): Boolean {
        if (directModel != null) {
            onProgress(1f)
            return true
        }
        if (directFailed) return false

        val manager = DirectTranslationModelManager(context)
        if (!allowDownload && !manager.isInstalled()) {
            directFailed = true
            return false
        }

        val loaded = runCatching {
            val modelFile = manager.ensureModel(onProgress)
            directModel = Llama.loadModel(
                modelPath = modelFile.absolutePath,
                config = LlamaConfig(
                    contextSize = 1_792,
                    threads = 4,
                    gpuLayers = 0,
                    temperature = 0.10f,
                    topP = 0.80f,
                    topK = 20,
                    seed = 42,
                ),
            )
        }.isSuccess

        if (!loaded || directModel == null) {
            directFailed = true
            return false
        }
        onProgress(1f)
        return true
    }

    suspend fun translate(
        cues: List<SubtitleCue>,
        deadlineAtElapsedRealtimeMs: Long = Long.MAX_VALUE,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<SubtitleCue> {
        if (cues.isEmpty()) return emptyList()
        check(directModel != null && !directFailed) {
            "المترجم التركي ← العربي المباشر غير جاهز."
        }

        val semanticCues = mergeSemanticCues(cues)
        val result = ArrayList<SubtitleCue>(semanticCues.size)
        var cursor = 0
        var observedMs = 0L
        var observedCues = 0
        var compressed = false

        while (cursor < semanticCues.size) {
            thermalYieldIfNeeded()

            val remainingMs = deadlineAtElapsedRealtimeMs - SystemClock.elapsedRealtime()
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
            val translated = translateBatch(
                batch = batch,
                recentContext = recentContext,
                compressed = compressed,
            )

            observedMs += (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
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
        compressed: Boolean,
    ): List<SubtitleCue> {
        if (batch.size == 1) {
            fixedHumanPhrase(batch.first())?.let { return listOf(it) }
        }

        val model = directModel ?: error("المترجم المباشر غير محمل.")
        val direct = runCatching {
            translateDirectBatch(
                model = model,
                batch = batch,
                recentContext = recentContext,
                compressed = compressed,
                repairMode = false,
            )
        }.getOrNull()

        if (isValidBatch(direct, batch.size)) {
            return qualityRepairIfNeeded(
                model = model,
                sourceBatch = batch,
                translatedBatch = direct!!,
                recentContext = recentContext,
            )
        }

        // Never replace a failed direct batch with a lower-quality translator. Split the scene until
        // marker fidelity is restored. A single line gets one strict retry, then the run fails loudly
        // rather than silently outputting Turkish or dropping dialogue.
        if (batch.size > 1) {
            val middle = batch.size / 2
            val left = translateBatch(batch.subList(0, middle), recentContext, compressed = false)
            val rightContext = (recentContext + left).takeLast(RECENT_CONTEXT_CUES)
            val right = translateBatch(batch.subList(middle, batch.size), rightContext, compressed = false)
            return left + right
        }

        val retry = runCatching {
            translateDirectBatch(
                model = model,
                batch = batch,
                recentContext = recentContext,
                compressed = false,
                repairMode = true,
            )
        }.getOrNull()
        if (isValidBatch(retry, 1)) return retry!!

        error("تعذر إخراج ترجمة عربية موثوقة للسطر: ${batch.first().sourceText.take(80)}")
    }

    private suspend fun qualityRepairIfNeeded(
        model: LlamaModel,
        sourceBatch: List<SubtitleCue>,
        translatedBatch: List<SubtitleCue>,
        recentContext: List<SubtitleCue>,
    ): List<SubtitleCue> {
        val suspicious = sourceBatch.indices
            .filter { index -> needsQualityRepair(sourceBatch[index], translatedBatch[index]) }
            .take(MAX_QUALITY_REPAIRS_PER_BATCH)
        if (suspicious.isEmpty()) return translatedBatch

        val repaired = translatedBatch.toMutableList()
        for (index in suspicious) {
            thermalYieldIfNeeded()
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
                appendLine("سياق سابق للفهم فقط — لا تعِد ترجمته:")
                recentContext.forEach { cue ->
                    append("TR: ").append(cue.sourceText.take(110))
                        .append(" | AR: ").append(cue.translatedText.take(110)).append('\n')
                }
            }
            if (repairMode) {
                appendLine("أعد ترجمة السطر التالي بدقة: لا تحذف أي معنى، ولا تشرح، ولا تكتب التركية:")
            } else {
                appendLine("ترجم كل سطر تركي إلى العربية سطرًا بسطر. لا تدمج سطرين ولا تسقط أي سطر. حافظ على العلامات كما هي:")
            }
            batch.forEachIndexed { index, cue ->
                append(marker(index)).append(' ').append(cue.sourceText.trim()).append('\n')
            }
        }

        val sourceChars = batch.sumOf { it.sourceText.length }
        val tokenCap = if (compressed) 560 else 620
        val maxTokens = (sourceChars * 2 / 3 + 96).coerceIn(112, tokenCap)
        val response = Llama.complete(
            model = model,
            prompt = prompt,
            systemPrompt = DIRECT_SYSTEM_PROMPT,
            maxTokens = maxTokens,
        ).text

        val pieces = parseMarkedTranslation(sanitizeModelOutput(response), batch.size) ?: return null
        return batch.mapIndexed { index, cue ->
            val arabic = cleanArabic(pieces[index])
            cue.copy(translatedText = arabic)
        }
    }

    private fun isValidBatch(batch: List<SubtitleCue>?, expected: Int): Boolean =
        batch != null &&
            batch.size == expected &&
            batch.all { hasUsefulArabic(it.translatedText) }

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
        return arabic.toFloat() / letters.toFloat() >= 0.62f
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
    }

    companion object {
        private const val NORMAL_BATCH_SIZE = 6
        private const val NORMAL_BATCH_CHARS = 760
        private const val COMPRESSED_BATCH_SIZE = 9
        private const val COMPRESSED_BATCH_CHARS = 1_050
        private const val MAX_CONTEXT_GAP_MS = 3_500L
        private const val MAX_SEMANTIC_GAP_MS = 450L
        private const val MAX_SEMANTIC_DURATION_MS = 4_800L
        private const val MAX_SEMANTIC_CHARS = 110
        private const val RECENT_CONTEXT_CUES = 3
        private const val MIN_CALIBRATION_CUES = 10
        private const val COMPRESSION_TRIGGER_RATIO = 0.90
        private const val MAX_QUALITY_REPAIRS_PER_BATCH = 2

        private val TURKISH_LOCALE = Locale.forLanguageTag("tr")
        private val MARKER_REGEX = Regex("§(\\d+)§")
        private val SENTENCE_END = Regex("[.!?…]\\s*$")
        private val SPACE_BEFORE_PUNCTUATION = Regex("\\s+([،؛:,.!?؟])")
        private val REPEATED_SPACES = Regex("\\s+")
        private val THINK_BLOCK = Regex("<think>.*?</think>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val LATIN_WORD = Regex("\\b[A-Za-zÇĞİÖŞÜçğıöşü]{3,}\\b")
        private val MODEL_PREFACE = Regex("^(بالطبع|إليك|الترجمة(?: العربية)?[:：]?)\\s*", RegexOption.IGNORE_CASE)
        private val REPEATED_ARABIC_WORD = Regex("\\b([\\u0600-\\u06FF]{2,})\\s+\\1\\s+\\1\\b")

        private val DIRECT_SYSTEM_PROMPT = """
            /no_think
            أنت مترجم أفلام تركية محترف إلى العربية. افهم الجملة في سياق المشهد ثم اكتب المقابل الذي سيختاره مترجم عربي بشري، لا ترجمة كلمة بكلمة.
            لا تختصر ولا تحذف أي معلومة أو رد قصير. لا تخترع معنى. حافظ على الأسماء والعلاقات والضمائر والنبرة والمزاح والسخرية والشتائم والتودد.
            استخدم عربية فصحى طبيعية سهلة وقصيرة مناسبة للشاشة. إذا كان التعبير التركي اصطلاحيًا فانقل معناه العربي الطبيعي لا ألفاظه الحرفية.
            لكل علامة §n§ أعد علامة §n§ نفسها مرة واحدة ثم ترجمة ذلك السطر فقط. ممنوع دمج سطرين أو إسقاط علامة. لا تشرح ولا تكتب التركية.
        """.trimIndent()

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
            if (source.length >= 14 && clean.length < 4) return true
            if (source.length >= 24 && clean.length < source.length * 0.10) return true
            if (clean.length > source.length * 3 + 80) return true
            if (LATIN_WORD.findAll(clean).count() >= 2) return true
            if (REPEATED_ARABIC_WORD.containsMatchIn(clean)) return true

            val letters = clean.count { it.isLetter() }.coerceAtLeast(1)
            val arabicLetters = clean.count { it in '\u0600'..'\u06FF' }
            return arabicLetters.toDouble() / letters.toDouble() < 0.62
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
