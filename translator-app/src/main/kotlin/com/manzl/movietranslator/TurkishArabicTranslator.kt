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
 * Turkish -> Arabic subtitle translation using a translation-specialized model.
 *
 * Coverage rule: one source cue must produce one target cue. We never merge away Whisper cues and
 * never accept a batch with missing markers. The mobile profile deliberately keeps the llama.cpp
 * context and prompt batches bounded because long CPU inference can otherwise create large native
 * memory/thermal spikes on Android.
 */
class TurkishArabicTranslator(private val context: Context) : AutoCloseable {
    private var model: LlamaModel? = null
    private var failed = false
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val translationThreads = stableThreadCount(Runtime.getRuntime().availableProcessors())

    suspend fun ensureModel(
        onProgress: (Float) -> Unit = {},
        allowDownload: Boolean = true,
    ): Boolean {
        if (model != null) {
            onProgress(1f)
            return true
        }
        if (failed) return false

        val manager = DirectTranslationModelManager(context)
        if (!allowDownload && !manager.isInstalled()) return false
        val loaded = runCatching {
            val file = manager.ensureModel(onProgress)
            model = Llama.loadModel(
                modelPath = file.absolutePath,
                config = LlamaConfig(
                    contextSize = MOBILE_CONTEXT_SIZE,
                    threads = translationThreads,
                    gpuLayers = 0,
                    temperature = 0.70f,
                    topP = 0.60f,
                    topK = 20,
                    seed = 42,
                ),
            )
        }.isSuccess
        if (!loaded || model == null) failed = true
        if (loaded) onProgress(1f)
        return loaded && model != null
    }

    suspend fun translate(
        cues: List<SubtitleCue>,
        deadlineAtElapsedRealtimeMs: Long = Long.MAX_VALUE,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<SubtitleCue> {
        if (cues.isEmpty()) return emptyList()
        val activeModel = model ?: error("المترجم التركي ← العربي غير جاهز.")
        val source = cues.filter { it.sourceText.isNotBlank() }.sortedBy { it.startMs }
        val result = ArrayList<SubtitleCue>(source.size)
        var cursor = 0

        while (cursor < source.size) {
            thermalYieldIfNeeded()
            val remaining = deadlineAtElapsedRealtimeMs - SystemClock.elapsedRealtime()
            val compact = remaining in 1 until 90_000L
            val batch = buildNextBatch(source, cursor, compact)
            val before = source.subList(0, cursor).takeLast(CONTEXT_BEFORE)
            val after = source.drop(cursor + batch.size).take(CONTEXT_AFTER)

            val translated = translateBatch(activeModel, batch, before, after)
            check(translated.size == batch.size) { "فقد المترجم سطرًا من الحوار." }
            result += translated
            cursor += batch.size
            onProgress(cursor, source.size)

            // Give Android a scheduling point between native llama.cpp generations. This is short
            // enough to be imperceptible but prevents a long film from becoming one uninterrupted
            // CPU burst on thermally constrained phones.
            delay(BETWEEN_BATCH_DELAY_MS)
        }
        return result
    }

    private suspend fun translateBatch(
        activeModel: LlamaModel,
        batch: List<SubtitleCue>,
        before: List<SubtitleCue>,
        after: List<SubtitleCue>,
    ): List<SubtitleCue> {
        if (batch.size == 1) {
            return listOf(translateSingleWithRecovery(activeModel, batch.first(), before, after))
        }

        val firstPass = runCatching {
            translateMarked(activeModel, batch, before, after)
        }.getOrNull() ?: List(batch.size) { null }

        val output = MutableList<SubtitleCue?>(batch.size) { null }
        firstPass.forEachIndexed { index, value ->
            val arabic = value?.let(::cleanArabicCandidate).orEmpty()
            if (isUsefulArabic(arabic)) output[index] = batch[index].copy(translatedText = arabic)
        }

        for (index in batch.indices) {
            if (output[index] != null) continue
            val localBefore = (before + batch.take(index)).takeLast(CONTEXT_BEFORE)
            val localAfter = (batch.drop(index + 1) + after).take(CONTEXT_AFTER)
            output[index] = translateSingleWithRecovery(
                activeModel, batch[index], localBefore, localAfter
            )
        }
        return output.map { it ?: error("تعذر ترجمة سطر من الحوار.") }
    }

    private suspend fun translateMarked(
        activeModel: LlamaModel,
        batch: List<SubtitleCue>,
        before: List<SubtitleCue>,
        after: List<SubtitleCue>,
    ): List<String?> {
        thermalYieldIfNeeded()
        val prompt = buildString {
            if (before.isNotEmpty() || after.isNotEmpty()) {
                appendLine("[Background Information]")
                before.forEach { append("Previous: ").append(it.sourceText.take(CONTEXT_CHARS)).append('\n') }
                after.forEach { append("Following: ").append(it.sourceText.take(CONTEXT_CHARS)).append('\n') }
                appendLine()
            }
            // Hy-MT2's published delimiter prompt is intentionally kept compact here. The previous
            // longer instruction duplicated constraints and increased prompt/compute memory.
            appendLine("Please accurately translate the following Turkish film subtitles into Arabic.")
            appendLine("Use natural Modern Standard Arabic suitable for film subtitles.")
            appendLine("Retain the exact same §number§ delimiters. Output only the translated lines:")
            batch.forEachIndexed { index, cue ->
                append(marker(index)).append(' ').append(cue.sourceText.trim()).append('\n')
            }
        }
        val chars = batch.sumOf { it.sourceText.length }
        val maxTokens = maxBatchTokens(chars, batch.size)
        val completion = Llama.complete(
            model = activeModel,
            prompt = prompt,
            systemPrompt = "",
            maxTokens = maxTokens,
        )
        return parseMarked(completion.text, batch.size)
    }

    private suspend fun translateSingleWithRecovery(
        activeModel: LlamaModel,
        cue: SubtitleCue,
        before: List<SubtitleCue>,
        after: List<SubtitleCue>,
    ): SubtitleCue {
        COMMON_PHRASES[normalizeTurkish(cue.sourceText)]?.let {
            return cue.copy(translatedText = it)
        }

        repeat(2) { attempt ->
            val candidate = runCatching {
                translateSingle(activeModel, cue, before, after, attempt)
            }.getOrNull().orEmpty()
            if (isUsefulArabic(candidate)) return cue.copy(translatedText = candidate)
        }

        // Never delete the cue. Keeping the Turkish source is preferable to silently losing speech;
        // the output can then be visibly diagnosed rather than pretending coverage is complete.
        return cue.copy(translatedText = cue.sourceText.trim())
    }

    private suspend fun translateSingle(
        activeModel: LlamaModel,
        cue: SubtitleCue,
        before: List<SubtitleCue>,
        after: List<SubtitleCue>,
        attempt: Int,
    ): String {
        thermalYieldIfNeeded()
        val prompt = buildString {
            if (before.isNotEmpty() || after.isNotEmpty()) {
                appendLine("[Background Information]")
                before.takeLast(CONTEXT_BEFORE).forEach {
                    append("Previous: ").append(it.sourceText.take(CONTEXT_CHARS)).append('\n')
                }
                after.take(CONTEXT_AFTER).forEach {
                    append("Following: ").append(it.sourceText.take(CONTEXT_CHARS)).append('\n')
                }
                appendLine()
            }
            appendLine("Translate the following Turkish film subtitle into Arabic. Output only the translation:")
            appendLine(cue.sourceText.trim())
            if (attempt > 0) appendLine("Use natural meaning in the dialogue context, not word-for-word translation.")
        }
        val maxTokens = (cue.sourceText.length / 2 + 20).coerceIn(20, SINGLE_MAX_TOKENS)
        val completion = Llama.complete(
            model = activeModel,
            prompt = prompt,
            systemPrompt = "",
            maxTokens = maxTokens,
        )
        return cleanArabicCandidate(completion.text)
    }

    private fun buildNextBatch(
        cues: List<SubtitleCue>,
        startIndex: Int,
        compact: Boolean,
    ): List<SubtitleCue> {
        val maxSize = if (compact) COMPACT_BATCH_SIZE else NORMAL_BATCH_SIZE
        val maxChars = if (compact) COMPACT_BATCH_CHARS else NORMAL_BATCH_CHARS
        val batch = mutableListOf<SubtitleCue>()
        var chars = 0
        for (index in startIndex until cues.size) {
            val cue = cues[index]
            val previous = batch.lastOrNull()
            if (batch.isNotEmpty()) {
                val gap = previous?.let { cue.startMs - it.endMs } ?: 0L
                if (gap > MAX_BATCH_GAP_MS || batch.size >= maxSize || chars + cue.sourceText.length > maxChars) break
            }
            batch += cue
            chars += cue.sourceText.length
        }
        return batch.ifEmpty { listOf(cues[startIndex]) }
    }

    private fun parseMarked(text: String, expected: Int): List<String?> {
        val cleaned = text.replace(Regex("<think>.*?</think>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace("```", "").trim()
        val result = MutableList<String?>(expected) { null }
        val matches = MARKER_REGEX.findAll(cleaned).toList()
        matches.forEachIndexed { position, match ->
            val index = match.groupValues[1].toIntOrNull() ?: return@forEachIndexed
            if (index !in 0 until expected) return@forEachIndexed
            val from = match.range.last + 1
            val to = matches.getOrNull(position + 1)?.range?.first ?: cleaned.length
            val value = cleaned.substring(from, to).trim()
            if (value.isNotBlank()) result[index] = value
        }
        if (matches.isEmpty()) {
            val lines = cleaned.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
            if (lines.size == expected) lines.forEachIndexed { i, line -> result[i] = line }
        }
        return result
    }

    private fun cleanArabicCandidate(value: String): String {
        if (value.isBlank()) return ""
        val line = value
            .replace(Regex("<think>.*?</think>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace("```arabic", "", ignoreCase = true)
            .replace("```", "")
            .replace(Regex("^\\s*(?:§\\s*\\d+\\s*§|الترجمة(?: العربية)?\\s*[:：-]?)\\s*"), "")
            .lineSequence().map { it.trim() }.filter { it.isNotBlank() }
            .firstOrNull { it.any { ch -> ch in '\u0600'..'\u06FF' } }
            .orEmpty()
        return line.replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+([،؛:,.!?؟])"), "$1")
            .replace(',', '،').replace(';', '؛').replace('?', '؟')
            .trim(' ', '"', '\'', '`', '«', '»', '“', '”')
    }

    private fun isUsefulArabic(value: String): Boolean {
        if (value.isBlank()) return false
        val letters = value.count { it.isLetter() }.coerceAtLeast(1)
        val arabic = value.count { it in '\u0600'..'\u06FF' }
        return arabic >= 2 && arabic.toFloat() / letters.toFloat() >= 0.55f
    }

    private suspend fun thermalYieldIfNeeded() {
        when {
            powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL -> delay(1_500L)
            powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> delay(500L)
            powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> delay(150L)
        }
    }

    override fun close() {
        model?.let { runCatching { Llama.releaseModel(it) } }
        model = null
    }

    companion object {
        // llama-android keeps one native context for the lifetime of the model. 1024 is ample for
        // the tightly bounded subtitle prompts below while cutting KV/context memory versus 1536.
        private const val MOBILE_CONTEXT_SIZE = 1_024
        private const val NORMAL_BATCH_SIZE = 4
        private const val NORMAL_BATCH_CHARS = 360
        private const val COMPACT_BATCH_SIZE = 5
        private const val COMPACT_BATCH_CHARS = 420
        private const val MAX_BATCH_GAP_MS = 6_000L
        private const val CONTEXT_BEFORE = 1
        private const val CONTEXT_AFTER = 1
        private const val CONTEXT_CHARS = 80
        private const val BATCH_MAX_TOKENS = 200
        private const val SINGLE_MAX_TOKENS = 64
        private const val BETWEEN_BATCH_DELAY_MS = 40L
        private val MARKER_REGEX = Regex("§\\s*(\\d+)\\s*§")
        private val TURKISH_LOCALE = Locale.forLanguageTag("tr")

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
            .lowercase(TURKISH_LOCALE).trim()
            .replace(Regex("[.!?…]+$"), "")
            .replace(Regex("\\s+"), " ")

        private fun maxBatchTokens(chars: Int, count: Int): Int =
            (chars / 2 + count * 8 + 20).coerceIn(40, BATCH_MAX_TOKENS)

        internal fun stableThreadCount(cores: Int): Int = if (cores >= 8) 3 else 2

        internal fun maxBatchTokensForTest(chars: Int, count: Int): Int =
            maxBatchTokens(chars, count)

        internal fun buildContextBatchesForTest(
            cues: List<SubtitleCue>,
            compressed: Boolean = false,
        ): List<List<SubtitleCue>> {
            if (cues.isEmpty()) return emptyList()
            val maxSize = if (compressed) COMPACT_BATCH_SIZE else NORMAL_BATCH_SIZE
            val maxChars = if (compressed) COMPACT_BATCH_CHARS else NORMAL_BATCH_CHARS
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

        internal fun translationNeedsRepairForTest(source: String, arabic: String): Boolean {
            if (arabic.isBlank()) return true
            val letters = arabic.count { it.isLetter() }.coerceAtLeast(1)
            val ar = arabic.count { it in '\u0600'..'\u06FF' }
            if (ar.toFloat() / letters.toFloat() < 0.55f) return true
            if (source.length >= 18 && arabic.length < 3) return true
            return Regex("^(?:بالطبع|إليك|الترجمة العربية)").containsMatchIn(arabic.trim())
        }
    }
}
