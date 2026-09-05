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
 * Mobile-first Turkish -> Arabic film subtitle translator.
 *
 * The important performance rule is to amortize LLM setup/prompt processing over many subtitle
 * lines. A two-minute clip must not trigger 20-30 separate model calls. We translate compact scene
 * windows in batches, cap generation tightly, and retry only the rare missing line.
 */
class TurkishArabicTranslator(private val context: Context) : AutoCloseable {
    private var directModel: LlamaModel? = null
    private var directFailed = false
    private var fastPressure = false
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
                    contextSize = 1_024,
                    threads = 4,
                    gpuLayers = 0,
                    temperature = 0.08f,
                    topP = 0.78f,
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
        val model = directModel ?: error("المترجم التركي ← العربي المباشر غير جاهز.")
        check(!directFailed) { "المترجم التركي ← العربي المباشر غير جاهز." }

        val semanticCues = mergeSemanticCues(cues)
        val result = ArrayList<SubtitleCue>(semanticCues.size)
        var cursor = 0
        var measuredMs = 0L
        var measuredCues = 0

        while (cursor < semanticCues.size) {
            thermalYieldIfNeeded()

            val remainingMs = deadlineAtElapsedRealtimeMs - SystemClock.elapsedRealtime()
            val remainingCues = semanticCues.size - cursor
            val projectedMs = if (measuredCues > 0) {
                measuredMs.toDouble() / measuredCues.toDouble() * remainingCues.toDouble()
            } else {
                0.0
            }
            val compressed = fastPressure ||
                (remainingMs > 0L && projectedMs > remainingMs.toDouble() * 0.85)

            val batch = buildNextBatch(semanticCues, cursor, compressed)
            val recentContext = result.takeLast(RECENT_CONTEXT_CUES)
            val futureContext = semanticCues.drop(cursor + batch.size).take(NEXT_CONTEXT_CUES)
            val startedAt = SystemClock.elapsedRealtime()

            val translated = translateBatch(
                model = model,
                batch = batch,
                recentContext = recentContext,
                futureContext = futureContext,
                deadlineAtElapsedRealtimeMs = deadlineAtElapsedRealtimeMs,
                compressed = compressed,
            )

            val tookMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
            measuredMs += tookMs
            measuredCues += batch.size
            if (tookMs / batch.size.coerceAtLeast(1) > SLOW_PER_CUE_MS) {
                fastPressure = true
            }

            result += translated
            cursor += batch.size
            onProgress(cursor, semanticCues.size)
        }

        return result.sortedBy { it.startMs }
    }

    private suspend fun translateBatch(
        model: LlamaModel,
        batch: List<SubtitleCue>,
        recentContext: List<SubtitleCue>,
        futureContext: List<SubtitleCue>,
        deadlineAtElapsedRealtimeMs: Long,
        compressed: Boolean,
    ): List<SubtitleCue> {
        if (batch.size == 1) {
            return listOf(
                recoverSingleCue(model, batch.first(), recentContext, futureContext)
            )
        }

        val pieces = runCatching {
            translateMarkedBatch(model, batch, recentContext, futureContext, compressed)
        }.getOrNull() ?: List(batch.size) { null }

        val recovered = MutableList<SubtitleCue?>(batch.size) { null }
        pieces.forEachIndexed { index, text ->
            val arabic = text?.let(::singleArabicCandidate).orEmpty()
            if (arabic.isNotBlank()) recovered[index] = batch[index].copy(translatedText = arabic)
        }

        // Retry only missing lines. Never recursively re-run an already successful batch.
        for (index in batch.indices) {
            if (recovered[index] != null) continue
            val before = (recentContext + recovered.take(index).filterNotNull()).takeLast(RECENT_CONTEXT_CUES)
            val after = (batch.drop(index + 1) + futureContext).take(NEXT_CONTEXT_CUES)
            recovered[index] = recoverSingleCue(model, batch[index], before, after)
        }

        val complete = recovered.map { it ?: error("تعذر استعادة سطر الترجمة.") }
        if (fastPressure ||
            deadlineAtElapsedRealtimeMs - SystemClock.elapsedRealtime() < OPTIONAL_REPAIR_MIN_REMAINING_MS
        ) {
            return complete
        }

        // At most one expensive quality retry per batch, and only while comfortably inside budget.
        val suspiciousIndex = batch.indices.firstOrNull { index ->
            translationNeedsRepair(batch[index].sourceText, complete[index].translatedText)
        } ?: return complete

        val before = (recentContext + complete.take(suspiciousIndex)).takeLast(RECENT_CONTEXT_CUES)
        val after = (batch.drop(suspiciousIndex + 1) + futureContext).take(NEXT_CONTEXT_CUES)
        val retry = runCatching {
            translateSingleDirect(model, batch[suspiciousIndex], before, after)
        }.getOrNull()
        if (retry != null && !translationNeedsRepair(batch[suspiciousIndex].sourceText, retry.translatedText)) {
            return complete.toMutableList().also { it[suspiciousIndex] = retry }
        }
        return complete
    }

    private suspend fun translateMarkedBatch(
        model: LlamaModel,
        batch: List<SubtitleCue>,
        recentContext: List<SubtitleCue>,
        futureContext: List<SubtitleCue>,
        compressed: Boolean,
    ): List<String?> {
        val prompt = buildString {
            if (recentContext.isNotEmpty()) {
                appendLine("سياق سابق فقط:")
                recentContext.forEach { cue ->
                    append("TR: ").append(cue.sourceText.take(90))
                    if (cue.translatedText.isNotBlank()) append(" | AR: ").append(cue.translatedText.take(90))
                    append('\n')
                }
            }
            appendLine("ترجم الأسطر التالية من التركية إلى عربية طبيعية غير حرفية. حافظ على كل علامة كما هي، ولا تشرح:")
            batch.forEachIndexed { index, cue ->
                append(marker(index)).append(' ').append(cue.sourceText.trim()).append('\n')
            }
            if (futureContext.isNotEmpty()) {
                appendLine("سياق لاحق للفهم فقط، لا تترجمه الآن:")
                futureContext.forEach { cue -> append("TR: ").append(cue.sourceText.take(90)).append('\n') }
            }
        }

        val sourceChars = batch.sumOf { it.sourceText.length }
        val estimated = sourceChars / 3 + batch.size * 14 + 20
        val maxTokens = estimated.coerceIn(40, if (compressed) 220 else 280)
        val completion = Llama.complete(
            model = model,
            prompt = prompt,
            systemPrompt = DIRECT_SYSTEM_PROMPT,
            maxTokens = maxTokens,
        )
        return parseMarkedTranslationPartial(sanitizeModelOutput(completion.text), batch.size)
    }

    private suspend fun recoverSingleCue(
        model: LlamaModel,
        cue: SubtitleCue,
        before: List<SubtitleCue>,
        after: List<SubtitleCue>,
    ): SubtitleCue {
        fixedHumanPhrase(cue)?.let { return it }
        contextualFragmentFallback(cue.sourceText)?.let { fallback ->
            if (cue.sourceText.length <= 12) return cue.copy(translatedText = fallback)
        }

        return runCatching { translateSingleDirect(model, cue, before, after) }
            .getOrNull()
            ?.takeIf { hasUsefulArabicLenient(it.translatedText) }
            ?: cue.copy(translatedText = contextualFragmentFallback(cue.sourceText) ?: "[حوار غير واضح]")
    }

    private suspend fun translateSingleDirect(
        model: LlamaModel,
        cue: SubtitleCue,
        before: List<SubtitleCue>,
        after: List<SubtitleCue>,
    ): SubtitleCue? {
        val prompt = buildString {
            before.takeLast(RECENT_CONTEXT_CUES).forEach { item ->
                append("قبل: ").append(item.sourceText.take(90))
                if (item.translatedText.isNotBlank()) append(" = ").append(item.translatedText.take(90))
                append('\n')
            }
            appendLine("الهدف: ${cue.sourceText.trim()}")
            after.take(NEXT_CONTEXT_CUES).forEach { item -> append("بعد: ").append(item.sourceText.take(90)).append('\n') }
            append("اكتب ترجمة الهدف بالعربية فقط، بصياغة بشرية طبيعية:")
        }

        val maxTokens = (cue.sourceText.length / 2 + 18).coerceIn(16, 44)
        val completion = Llama.complete(
            model = model,
            prompt = prompt,
            systemPrompt = SINGLE_SYSTEM_PROMPT,
            maxTokens = maxTokens,
        )
        val arabic = singleArabicCandidate(completion.text)
        return if (arabic.isBlank()) null else cue.copy(translatedText = arabic)
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
                val gap = previous?.let { cue.startMs - it.endMs } ?: 0L
                val tooLarge = batch.size >= maxSize || chars + cue.sourceText.length > maxChars
                if (gap > MAX_BATCH_GAP_MS || tooLarge) break
            }
            batch += cue
            chars += cue.sourceText.length
        }
        return batch.ifEmpty { listOf(cues[startIndex]) }
    }

    private fun parseMarkedTranslationPartial(text: String, expected: Int): List<String?> {
        val pieces = MutableList<String?>(expected) { null }
        val positions = FLEX_MARKER_REGEX.findAll(text).toList()
        if (positions.isEmpty()) {
            val lines = text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
            if (lines.size == expected) lines.forEachIndexed { index, line -> pieces[index] = line }
            return pieces
        }
        positions.forEachIndexed { positionIndex, match ->
            val index = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.toIntOrNull()
                ?: return@forEachIndexed
            if (index !in 0 until expected) return@forEachIndexed
            val start = match.range.last + 1
            val end = if (positionIndex + 1 < positions.size) positions[positionIndex + 1].range.first else text.length
            text.substring(start, end).trim().takeIf { it.isNotBlank() }?.let { pieces[index] = it }
        }
        return pieces
    }

    private fun marker(index: Int): String = "§$index§"

    private fun sanitizeModelOutput(value: String): String = value
        .replace(THINK_BLOCK, " ")
        .replace("```arabic", "", ignoreCase = true)
        .replace("```", "")
        .trim()

    private fun singleArabicCandidate(value: String): String = singleArabicCandidateStatic(sanitizeModelOutput(value))

    private fun hasUsefulArabicLenient(value: String): Boolean {
        if (value.isBlank()) return false
        val letters = value.count { it.isLetter() }.coerceAtLeast(1)
        val arabic = value.count { it in '\u0600'..'\u06FF' }
        return arabic >= 2 && arabic.toFloat() / letters.toFloat() >= 0.40f
    }

    private fun fixedHumanPhrase(cue: SubtitleCue): SubtitleCue? =
        COMMON_PHRASES[normalizeTurkishKey(cue.sourceText)]?.let { cue.copy(translatedText = it) }

    private fun contextualFragmentFallback(source: String): String? =
        SHORT_FRAGMENT_FALLBACKS[normalizeTurkishKey(source)]

    private fun normalizeTurkishKey(value: String): String = normalizeTurkishKeyStatic(value)

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
        private const val NORMAL_BATCH_SIZE = 14
        private const val NORMAL_BATCH_CHARS = 1_500
        private const val COMPRESSED_BATCH_SIZE = 20
        private const val COMPRESSED_BATCH_CHARS = 2_100
        private const val MAX_BATCH_GAP_MS = 12_000L
        private const val MAX_SEMANTIC_GAP_MS = 550L
        private const val MAX_SEMANTIC_DURATION_MS = 5_200L
        private const val MAX_SEMANTIC_CHARS = 120
        private const val RECENT_CONTEXT_CUES = 1
        private const val NEXT_CONTEXT_CUES = 1
        private const val SLOW_PER_CUE_MS = 12_000L
        private const val OPTIONAL_REPAIR_MIN_REMAINING_MS = 45_000L

        private val TURKISH_LOCALE = Locale.forLanguageTag("tr")
        private val FLEX_MARKER_REGEX = Regex("(?m)(?:§\\s*(\\d+)\\s*§|\\[(\\d+)]|^\\s*(\\d+)\\s*[.)：:])")
        private val SINGLE_MARKER_PREFIX = Regex("^\\s*(?:§\\s*\\d+\\s*§|\\[\\d+]|\\d+\\s*[.)：:])\\s*")
        private val SENTENCE_END = Regex("[.!?…]\\s*$")
        private val SPACE_BEFORE_PUNCTUATION = Regex("\\s+([،؛:,.!?؟])")
        private val REPEATED_SPACES = Regex("\\s+")
        private val THINK_BLOCK = Regex("<think>.*?</think>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val LATIN_WORD = Regex("\\b[A-Za-zÇĞİÖŞÜçğıöşü]{3,}\\b")
        private val LATIN_PARENTHETICAL = Regex("\\s*[\\(\\[（][^\\)\\]）]*[A-Za-zÇĞİÖŞÜçğıöşü][^\\)\\]）]*[\\)\\]）]\\s*")
        private val TRAILING_LATIN_EXPLANATION = Regex("\\s*[-—–]\\s*[A-Za-zÇĞİÖŞÜçğıöşü].*$")
        private val MODEL_PREFACE = Regex("^(?:بالطبع[،,:：]?\\s*|إليك(?: الترجمة)?[،,:：]?\\s*|الترجمة(?: العربية)?[،,:：]?\\s*)", RegexOption.IGNORE_CASE)
        private val REPEATED_ARABIC_WORD = Regex("\\b([\\u0600-\\u06FF]{2,})\\s+\\1\\s+\\1\\b")

        private val DIRECT_SYSTEM_PROMPT = """
            أنت مترجم أفلام تركية محترف إلى العربية. انقل المعنى والنبرة كما يكتب مترجم عربي بشري، لا كلمة بكلمة.
            لا تحذف أي رد أو معلومة، ولا تشرح. حافظ على الأسماء والعلاقات والمزاح والسخرية والتعبيرات الاصطلاحية.
            أخرج العلامات المطلوبة نفسها، وبعد كل علامة العربية فقط.
        """.trimIndent()

        private val SINGLE_SYSTEM_PROMPT = """
            أنت مترجم حوار تركي إلى العربية. افهم السطر من السياق واكتب المقابل العربي الطبيعي المختصر فقط، بلا شرح وبلا تركية.
        """.trimIndent()

        private val COMMON_PHRASES = mapOf(
            "evet" to "نعم.", "hayır" to "لا.", "teşekkürler" to "شكرًا.",
            "teşekkür ederim" to "شكرًا لك.", "sağ ol" to "شكرًا لك.", "lütfen" to "من فضلك.",
            "özür dilerim" to "أعتذر.", "affedersin" to "عذرًا.", "merhaba" to "مرحبًا.",
            "günaydın" to "صباح الخير.", "iyi geceler" to "ليلة سعيدة.", "görüşürüz" to "إلى اللقاء.",
            "bilmiyorum" to "لا أعرف.", "anladım" to "فهمت.", "anlamadım" to "لم أفهم.",
            "tamam" to "حسنًا.", "peki" to "حسنًا.", "hadi" to "هيا.", "bekle" to "انتظر.",
            "dur" to "توقف.", "gel" to "تعال.", "git" to "اذهب.", "neden" to "لماذا؟",
            "nasıl" to "كيف؟", "nerede" to "أين؟",
        )

        private val SHORT_FRAGMENT_FALLBACKS = mapOf(
            "benden" to "مني.", "senden" to "منك.", "bizden" to "منا.", "sizden" to "منكم.",
            "ben" to "أنا.", "sen" to "أنت.", "biz" to "نحن.", "siz" to "أنتم.",
            "bana" to "لي.", "sana" to "لك.", "burada" to "هنا.", "orada" to "هناك.",
            "şimdi" to "الآن.", "sonra" to "لاحقًا.", "kim" to "من؟", "ne" to "ماذا؟",
        )

        internal fun mergeSemanticCuesForTest(cues: List<SubtitleCue>): List<SubtitleCue> = mergeSemanticCues(cues)

        internal fun buildContextBatchesForTest(
            cues: List<SubtitleCue>,
            compressed: Boolean = false,
        ): List<List<SubtitleCue>> {
            if (cues.isEmpty()) return emptyList()
            val maxSize = if (compressed) COMPRESSED_BATCH_SIZE else NORMAL_BATCH_SIZE
            val maxChars = if (compressed) COMPRESSED_BATCH_CHARS else NORMAL_BATCH_CHARS
            val batches = mutableListOf<List<SubtitleCue>>()
            var cursor = 0
            while (cursor < cues.size) {
                val batch = mutableListOf<SubtitleCue>()
                var chars = 0
                for (index in cursor until cues.size) {
                    val cue = cues[index]
                    val previous = batch.lastOrNull()
                    if (batch.isNotEmpty()) {
                        val gap = previous?.let { cue.startMs - it.endMs } ?: 0L
                        if (gap > MAX_BATCH_GAP_MS || batch.size >= maxSize || chars + cue.sourceText.length > maxChars) break
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

        internal fun singleArabicCandidateForTest(value: String): String = singleArabicCandidateStatic(value)

        internal fun contextualFragmentFallbackForTest(source: String): String =
            SHORT_FRAGMENT_FALLBACKS[normalizeTurkishKeyStatic(source)].orEmpty()

        private fun singleArabicCandidateStatic(value: String): String {
            if (value.isBlank()) return ""
            var cleaned = value
                .replace(THINK_BLOCK, " ")
                .replace("```arabic", "", ignoreCase = true)
                .replace("```", "")
                .trim()
                .replace(SINGLE_MARKER_PREFIX, "")
                .replace(MODEL_PREFACE, "")
                .replace(LATIN_PARENTHETICAL, " ")
                .replace(TRAILING_LATIN_EXPLANATION, " ")
                .trim()
                .trim('"', '\'', '`', '«', '»', '“', '”')
            if (cleaned.contains('\n')) {
                cleaned = cleaned.lineSequence().map { it.trim() }
                    .firstOrNull { line -> line.any { it in '\u0600'..'\u06FF' } }.orEmpty()
            }
            cleaned = cleanArabic(cleaned)
            if (cleaned.isBlank()) return ""
            val letters = cleaned.count { it.isLetter() }.coerceAtLeast(1)
            val arabic = cleaned.count { it in '\u0600'..'\u06FF' }
            if (arabic < 2 || arabic.toFloat() / letters.toFloat() < 0.40f) return ""
            if (LATIN_WORD.findAll(cleaned).count() >= 2) return ""
            return cleaned
        }

        private fun normalizeTurkishKeyStatic(value: String): String = value
            .lowercase(TURKISH_LOCALE).trim().replace(Regex("[.!?…]+$"), "").replace(Regex("\\s+"), " ")

        private fun translationNeedsRepair(source: String, arabic: String): Boolean {
            val clean = arabic.trim()
            if (clean.isBlank()) return true
            if (MODEL_PREFACE.containsMatchIn(clean)) return true
            if (source.length >= 18 && clean.length < 4) return true
            if (clean.length > source.length * 3 + 80) return true
            if (LATIN_WORD.findAll(clean).count() >= 2) return true
            if (REPEATED_ARABIC_WORD.containsMatchIn(clean)) return true
            val letters = clean.count { it.isLetter() }.coerceAtLeast(1)
            val arabicLetters = clean.count { it in '\u0600'..'\u06FF' }
            return arabicLetters.toDouble() / letters.toDouble() < 0.50
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
                val canMerge = !SENTENCE_END.containsMatchIn(current.sourceText) &&
                    gap <= MAX_SEMANTIC_GAP_MS && combinedDuration <= MAX_SEMANTIC_DURATION_MS &&
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

        private fun cleanArabic(value: String): String = value.trim()
            .replace(REPEATED_SPACES, " ")
            .replace(SPACE_BEFORE_PUNCTUATION, "$1")
            .replace(',', '،').replace(';', '؛').replace('?', '؟')
    }
}
