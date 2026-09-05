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
 * Spoken dialogue must never disappear because the model missed an output marker. Multi-line batches
 * are the fast path, while only missing/suspicious lines are retried individually with nearby source
 * context. Single fragments intentionally do not use markers because short Turkish forms such as
 * "benden" need linguistic context rather than a brittle formatting contract.
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
            val futureContext = semanticCues
                .drop(cursor + batch.size)
                .take(NEXT_CONTEXT_CUES)
            val startedAt = SystemClock.elapsedRealtime()

            val translated = translateBatch(
                batch = batch,
                recentContext = recentContext,
                futureContext = futureContext,
                compressed = compressed,
                deadlineAtElapsedRealtimeMs = deadlineAtElapsedRealtimeMs,
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
        futureContext: List<SubtitleCue>,
        compressed: Boolean,
        deadlineAtElapsedRealtimeMs: Long,
    ): List<SubtitleCue> {
        val model = directModel ?: error("المترجم المباشر غير محمل.")

        if (batch.size == 1) {
            return listOf(
                recoverSingleCue(
                    model = model,
                    cue = batch.first(),
                    before = recentContext,
                    after = futureContext,
                )
            )
        }

        val attempt = runCatching {
            translateMarkedBatch(
                model = model,
                batch = batch,
                recentContext = recentContext,
                futureContext = futureContext,
                compressed = compressed,
            )
        }.getOrNull()

        val recovered = MutableList<SubtitleCue?>(batch.size) { null }
        attempt?.forEachIndexed { index, cue ->
            if (cue != null && hasUsefulArabic(cue.translatedText)) {
                recovered[index] = cue
            }
        }

        // Recover only failed lines. The old recursive split path could repeatedly re-run already
        // successful dialogue and turn one malformed marker into several minutes of extra work.
        for (index in batch.indices) {
            if (recovered[index] != null) continue
            thermalYieldIfNeeded()
            val before = (recentContext + recovered.take(index).filterNotNull())
                .takeLast(RECENT_CONTEXT_CUES)
            val after = (batch.drop(index + 1) + futureContext).take(NEXT_CONTEXT_CUES)
            recovered[index] = recoverSingleCue(
                model = model,
                cue = batch[index],
                before = before,
                after = after,
            )
        }

        val complete = recovered.map { it ?: error("تعذر استعادة سطر الترجمة.") }
        return qualityRepairIfNeeded(
            model = model,
            sourceBatch = batch,
            translatedBatch = complete,
            recentContext = recentContext,
            futureContext = futureContext,
            deadlineAtElapsedRealtimeMs = deadlineAtElapsedRealtimeMs,
        )
    }

    private suspend fun recoverSingleCue(
        model: LlamaModel,
        cue: SubtitleCue,
        before: List<SubtitleCue>,
        after: List<SubtitleCue>,
    ): SubtitleCue {
        fixedHumanPhrase(cue)?.let { return it }

        val contextual = runCatching {
            translateSingleDirect(
                model = model,
                cue = cue,
                before = before,
                after = after,
                rescueMode = false,
            )
        }.getOrNull()
        if (contextual != null && hasUsefulArabic(contextual.translatedText)) {
            return contextual
        }

        // One short rescue attempt uses an even simpler output contract. No marker is required.
        val rescue = runCatching {
            translateSingleDirect(
                model = model,
                cue = cue,
                before = before,
                after = after,
                rescueMode = true,
            )
        }.getOrNull()
        if (rescue != null && hasUsefulArabicLenient(rescue.translatedText)) {
            return rescue
        }

        contextualFragmentFallback(cue.sourceText)?.let { arabic ->
            return cue.copy(translatedText = arabic)
        }

        // Do not cancel a whole movie after many minutes because of one malformed model response.
        // This visible Arabic placeholder is deliberately preferable to silently dropping dialogue
        // or writing the Turkish source as if it were translated.
        return cue.copy(translatedText = "[حوار غير واضح]")
    }

    private suspend fun translateMarkedBatch(
        model: LlamaModel,
        batch: List<SubtitleCue>,
        recentContext: List<SubtitleCue>,
        futureContext: List<SubtitleCue>,
        compressed: Boolean,
    ): List<SubtitleCue?> {
        val prompt = buildString {
            appendLine("/no_think")
            if (recentContext.isNotEmpty()) {
                appendLine("سياق سابق للفهم فقط — لا تعِد ترجمته:")
                recentContext.forEach { cue ->
                    append("TR: ").append(cue.sourceText.take(110))
                        .append(" | AR: ").append(cue.translatedText.take(110)).append('\n')
                }
            }
            if (futureContext.isNotEmpty()) {
                appendLine("سياق لاحق للفهم فقط — لا تترجمه الآن:")
                futureContext.forEach { cue ->
                    append("TR: ").append(cue.sourceText.take(110)).append('\n')
                }
            }
            appendLine("ترجم كل سطر مطلوب إلى العربية الطبيعية. لا تسقط أي رد، وحافظ على العلامات:")
            batch.forEachIndexed { index, cue ->
                append(marker(index)).append(' ').append(cue.sourceText.trim()).append('\n')
            }
        }

        val sourceChars = batch.sumOf { it.sourceText.length }
        val tokenCap = if (compressed) 520 else 600
        val maxTokens = (sourceChars * 2 / 3 + 96).coerceIn(112, tokenCap)
        val response = Llama.complete(
            model = model,
            prompt = prompt,
            systemPrompt = DIRECT_SYSTEM_PROMPT,
            maxTokens = maxTokens,
        ).text

        val pieces = parseMarkedTranslationPartial(sanitizeModelOutput(response), batch.size)
        return batch.mapIndexed { index, cue ->
            val arabic = pieces[index]?.let(::singleArabicCandidate).orEmpty()
            if (arabic.isBlank()) null else cue.copy(translatedText = arabic)
        }
    }

    private suspend fun translateSingleDirect(
        model: LlamaModel,
        cue: SubtitleCue,
        before: List<SubtitleCue>,
        after: List<SubtitleCue>,
        rescueMode: Boolean,
    ): SubtitleCue? {
        val prompt = buildString {
            appendLine("/no_think")
            if (before.isNotEmpty()) {
                appendLine("قبل السطر الهدف:")
                before.takeLast(RECENT_CONTEXT_CUES).forEach { item ->
                    append("TR: ").append(item.sourceText.take(110))
                    if (item.translatedText.isNotBlank()) {
                        append(" | AR: ").append(item.translatedText.take(110))
                    }
                    append('\n')
                }
            }
            appendLine("السطر التركي الهدف: ${cue.sourceText.trim()}")
            if (after.isNotEmpty()) {
                appendLine("بعده، للسياق فقط:")
                after.take(NEXT_CONTEXT_CUES).forEach { item ->
                    append("TR: ").append(item.sourceText.take(110)).append('\n')
                }
            }
            if (rescueMode) {
                appendLine("قد يكون الهدف كلمة أو جزءًا صرفيًا مقتطعًا من الحوار. استنتج معناها من السياق واختر أقرب عبارة عربية بشرية.")
            }
            appendLine("أعد ترجمة السطر الهدف بالعربية فقط. بلا علامة، بلا شرح، بلا التركية.")
        }

        val maxTokens = (cue.sourceText.length * 2 + 56).coerceIn(48, 144)
        val response = Llama.complete(
            model = model,
            prompt = prompt,
            systemPrompt = SINGLE_SYSTEM_PROMPT,
            maxTokens = maxTokens,
        ).text
        val arabic = singleArabicCandidate(response)
        return if (arabic.isBlank()) null else cue.copy(translatedText = arabic)
    }

    private suspend fun qualityRepairIfNeeded(
        model: LlamaModel,
        sourceBatch: List<SubtitleCue>,
        translatedBatch: List<SubtitleCue>,
        recentContext: List<SubtitleCue>,
        futureContext: List<SubtitleCue>,
        deadlineAtElapsedRealtimeMs: Long,
    ): List<SubtitleCue> {
        if (deadlineAtElapsedRealtimeMs != Long.MAX_VALUE &&
            deadlineAtElapsedRealtimeMs - SystemClock.elapsedRealtime() < OPTIONAL_REPAIR_MIN_REMAINING_MS
        ) {
            return translatedBatch
        }

        val suspicious = sourceBatch.indices
            .filter { index -> needsQualityRepair(sourceBatch[index], translatedBatch[index]) }
            .take(MAX_QUALITY_REPAIRS_PER_BATCH)
        if (suspicious.isEmpty()) return translatedBatch

        val repaired = translatedBatch.toMutableList()
        for (index in suspicious) {
            thermalYieldIfNeeded()
            val before = (recentContext + repaired.take(index)).takeLast(RECENT_CONTEXT_CUES)
            val after = (sourceBatch.drop(index + 1) + futureContext).take(NEXT_CONTEXT_CUES)
            val retry = runCatching {
                translateSingleDirect(
                    model = model,
                    cue = sourceBatch[index],
                    before = before,
                    after = after,
                    rescueMode = true,
                )
            }.getOrNull()

            if (retry != null &&
                hasUsefulArabic(retry.translatedText) &&
                !needsQualityRepair(sourceBatch[index], retry)
            ) {
                repaired[index] = retry
            }
        }
        return repaired
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

    private fun parseMarkedTranslationPartial(text: String, expected: Int): List<String?> {
        val pieces = MutableList<String?>(expected) { null }
        val positions = FLEX_MARKER_REGEX.findAll(text).toList()

        if (positions.isEmpty()) {
            val lines = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toList()
            if (lines.size == expected) {
                lines.forEachIndexed { index, line -> pieces[index] = line }
            }
            return pieces
        }

        positions.forEachIndexed { positionIndex, match ->
            val index = match.groupValues.drop(1)
                .firstOrNull { it.isNotBlank() }
                ?.toIntOrNull()
                ?: return@forEachIndexed
            if (index !in 0 until expected) return@forEachIndexed
            val start = match.range.last + 1
            val end = if (positionIndex + 1 < positions.size) {
                positions[positionIndex + 1].range.first
            } else {
                text.length
            }
            val value = text.substring(start, end).trim()
            if (value.isNotBlank()) pieces[index] = value
        }
        return pieces
    }

    private fun marker(index: Int): String = "§$index§"

    private fun sanitizeModelOutput(value: String): String = value
        .replace(THINK_BLOCK, " ")
        .replace("```arabic", "", ignoreCase = true)
        .replace("```", "")
        .trim()

    private fun singleArabicCandidate(value: String): String =
        singleArabicCandidateStatic(sanitizeModelOutput(value))

    private fun hasUsefulArabic(value: String): Boolean {
        if (value.isBlank()) return false
        val letters = value.count { it.isLetter() }.coerceAtLeast(1)
        val arabic = value.count { it in '\u0600'..'\u06FF' }
        return arabic >= 2 && arabic.toFloat() / letters.toFloat() >= 0.55f
    }

    private fun hasUsefulArabicLenient(value: String): Boolean {
        if (value.isBlank()) return false
        val letters = value.count { it.isLetter() }.coerceAtLeast(1)
        val arabic = value.count { it in '\u0600'..'\u06FF' }
        return arabic >= 2 && arabic.toFloat() / letters.toFloat() >= 0.42f
    }

    private fun needsQualityRepair(source: SubtitleCue, translated: SubtitleCue): Boolean =
        translationNeedsRepair(source.sourceText, translated.translatedText)

    private fun fixedHumanPhrase(cue: SubtitleCue): SubtitleCue? {
        val key = normalizeTurkishKey(cue.sourceText)
        val arabic = COMMON_PHRASES[key] ?: return null
        return cue.copy(translatedText = arabic)
    }

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
        private const val NORMAL_BATCH_SIZE = 6
        private const val NORMAL_BATCH_CHARS = 760
        private const val COMPRESSED_BATCH_SIZE = 9
        private const val COMPRESSED_BATCH_CHARS = 1_050
        private const val MAX_CONTEXT_GAP_MS = 3_500L
        private const val MAX_SEMANTIC_GAP_MS = 550L
        private const val MAX_SEMANTIC_DURATION_MS = 5_200L
        private const val MAX_SEMANTIC_CHARS = 120
        private const val RECENT_CONTEXT_CUES = 3
        private const val NEXT_CONTEXT_CUES = 2
        private const val MIN_CALIBRATION_CUES = 10
        private const val COMPRESSION_TRIGGER_RATIO = 0.90
        private const val MAX_QUALITY_REPAIRS_PER_BATCH = 2
        private const val OPTIONAL_REPAIR_MIN_REMAINING_MS = 35_000L

        private val TURKISH_LOCALE = Locale.forLanguageTag("tr")
        private val FLEX_MARKER_REGEX = Regex(
            "(?m)(?:§\\s*(\\d+)\\s*§|\\[(\\d+)]|^\\s*(\\d+)\\s*[.)：:])"
        )
        private val SINGLE_MARKER_PREFIX = Regex("^\\s*(?:§\\s*\\d+\\s*§|\\[\\d+]|\\d+\\s*[.)：:])\\s*")
        private val SENTENCE_END = Regex("[.!?…]\\s*$")
        private val SPACE_BEFORE_PUNCTUATION = Regex("\\s+([،؛:,.!?؟])")
        private val REPEATED_SPACES = Regex("\\s+")
        private val THINK_BLOCK = Regex(
            "<think>.*?</think>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val LATIN_WORD = Regex("\\b[A-Za-zÇĞİÖŞÜçğıöşü]{3,}\\b")
        private val LATIN_PARENTHETICAL = Regex(
            "\\s*[\\(\\[（][^\\)\\]）]*[A-Za-zÇĞİÖŞÜçğıöşü][^\\)\\]）]*[\\)\\]）]\\s*"
        )
        private val TRAILING_LATIN_EXPLANATION = Regex(
            "\\s*[-—–]\\s*[A-Za-zÇĞİÖŞÜçğıöşü].*$"
        )
        private val MODEL_PREFACE = Regex(
            "^(?:بالطبع[،,:：]?\\s*|إليك(?: الترجمة)?[،,:：]?\\s*|الترجمة(?: العربية)?[،,:：]?\\s*)",
            RegexOption.IGNORE_CASE
        )
        private val REPEATED_ARABIC_WORD = Regex("\\b([\\u0600-\\u06FF]{2,})\\s+\\1\\s+\\1\\b")

        private val DIRECT_SYSTEM_PROMPT = """
            /no_think
            أنت مترجم أفلام تركية محترف إلى العربية. افهم كل سطر داخل سياق المشهد ثم اكتب المقابل الذي سيختاره مترجم عربي بشري، لا ترجمة كلمة بكلمة.
            لا تختصر ولا تحذف أي معلومة أو رد قصير. قد تكون بعض السطور كلمات أو أجزاء صرفية مقتطعة مثل benden؛ استخدم ما قبلها وما بعدها لفهمها ولا تعتبرها غير قابلة للترجمة.
            حافظ على الأسماء والعلاقات والضمائر والنبرة والمزاح والسخرية والشتائم والتودد، وانقل التعبير الاصطلاحي بمعناه الطبيعي.
            لكل علامة §n§ أعد العلامة نفسها ثم ترجمة ذلك السطر فقط. لا تشرح ولا تكتب التركية في الناتج.
        """.trimIndent()

        private val SINGLE_SYSTEM_PROMPT = """
            /no_think
            أنت مترجم حوار تركي إلى العربية. المطلوب ترجمة السطر الهدف فقط بصياغة عربية بشرية طبيعية اعتمادًا على السياق المحيط.
            قد يكون الهدف كلمة قصيرة أو جزءًا من جملة؛ لا ترفضه ولا تشرحه. أخرج العربية فقط، بلا التركية وبلا مقدمات.
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
            "tamam" to "حسنًا.",
            "peki" to "حسنًا.",
            "hadi" to "هيا.",
            "bekle" to "انتظر.",
            "dur" to "توقف.",
            "gel" to "تعال.",
            "git" to "اذهب.",
            "neden" to "لماذا؟",
            "nasıl" to "كيف؟",
            "nerede" to "أين؟",
        )

        private val SHORT_FRAGMENT_FALLBACKS = mapOf(
            "benden" to "مني.",
            "senden" to "منك.",
            "bizden" to "منا.",
            "sizden" to "منكم.",
            "ben" to "أنا.",
            "sen" to "أنت.",
            "biz" to "نحن.",
            "siz" to "أنتم.",
            "bana" to "لي.",
            "sana" to "لك.",
            "burada" to "هنا.",
            "orada" to "هناك.",
            "şimdi" to "الآن.",
            "sonra" to "لاحقًا.",
            "kim" to "من؟",
            "ne" to "ماذا؟",
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

        internal fun singleArabicCandidateForTest(value: String): String =
            singleArabicCandidateStatic(value)

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
                cleaned = cleaned.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { line -> line.any { it in '\u0600'..'\u06FF' } }
                    .orEmpty()
            }
            cleaned = cleanArabic(cleaned)
            if (cleaned.isBlank()) return ""
            val letters = cleaned.count { it.isLetter() }.coerceAtLeast(1)
            val arabic = cleaned.count { it in '\u0600'..'\u06FF' }
            if (arabic < 2 || arabic.toFloat() / letters.toFloat() < 0.42f) return ""
            if (LATIN_WORD.findAll(cleaned).count() >= 2) return ""
            return cleaned
        }

        private fun normalizeTurkishKeyStatic(value: String): String = value
            .lowercase(TURKISH_LOCALE)
            .trim()
            .replace(Regex("[.!?…]+$"), "")
            .replace(Regex("\\s+"), " ")

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
