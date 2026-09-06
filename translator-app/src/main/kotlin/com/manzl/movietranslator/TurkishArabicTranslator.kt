package com.manzl.movietranslator

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/**
 * Lightweight on-device Turkish -> Arabic subtitle translator.
 *
 * The previous NLLB-600M path was accurate enough in isolation but far too slow for phones because
 * every subtitle had to pass through a large autoregressive ONNX decoder. This implementation uses
 * ML Kit's compact on-device translation models and groups nearby Whisper cues so the translator
 * receives useful dialogue context without paying for a heavyweight LLM per line.
 *
 * Translation is performed on text only; the movie never leaves the device.
 */
class TurkishArabicTranslator(private val context: Context) : AutoCloseable {
    private var client: Translator? = null
    private var failed = false

    suspend fun ensureModel(
        onProgress: (Float) -> Unit = {},
        allowDownload: Boolean = true,
    ): Boolean {
        if (client != null) {
            onProgress(1f)
            return true
        }
        if (failed) return false

        val manager = DirectTranslationModelManager(context)
        if (!allowDownload && !manager.isInstalled()) return false

        val initialized = runCatching {
            if (allowDownload) manager.ensureModel(onProgress)
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.TURKISH)
                .setTargetLanguage(TranslateLanguage.ARABIC)
                .build()
            client = Translation.getClient(options)
        }.onFailure { error ->
            Log.e(TAG, "Failed to initialize ML Kit translator", error)
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
        val translator = client ?: error("مترجم ML Kit التركي ← العربي غير جاهز.")
        val source = cues.filter { it.sourceText.isNotBlank() }.sortedBy { it.startMs }
        if (source.isEmpty()) return@withContext emptyList()

        val groups = buildContextGroups(source)
        val cache = HashMap<String, String>()
        val output = ArrayList<SubtitleCue>(source.size)
        var completed = 0

        for (group in groups) {
            currentCoroutineContext().ensureActive()
            val combinedSource = group.joinToString("\n") { it.sourceText.trim() }
            val normalized = normalizeTurkish(combinedSource)
            val started = SystemClock.elapsedRealtime()

            val translatedBlock = if (group.size == 1) {
                COMMON_PHRASES[normalizeTurkish(group.first().sourceText)]
                    ?: cache[normalized]
                    ?: translateText(translator, combinedSource).also { cache[normalized] = it }
            } else {
                cache[normalized]
                    ?: translateText(translator, combinedSource).also { cache[normalized] = it }
            }

            val mapped = if (
                translationNeedsRepair(combinedSource, translatedBlock) &&
                deadlineAtElapsedRealtimeMs - SystemClock.elapsedRealtime() > INDIVIDUAL_RETRY_MIN_MS
            ) {
                translateIndividually(translator, group, cache)
            } else {
                mapBlockBackToCues(group, translatedBlock)
            }

            output += mapped
            completed += group.size
            onProgress(completed, source.size)
            Log.d(
                TAG,
                "ML Kit group ${group.size} cues / ${combinedSource.length} chars in " +
                    "${SystemClock.elapsedRealtime() - started} ms",
            )
        }

        output
    }

    private suspend fun translateIndividually(
        translator: Translator,
        group: List<SubtitleCue>,
        cache: MutableMap<String, String>,
    ): List<SubtitleCue> {
        return group.map { cue ->
            currentCoroutineContext().ensureActive()
            val key = normalizeTurkish(cue.sourceText)
            val translated = COMMON_PHRASES[key]
                ?: cache[key]
                ?: translateText(translator, cue.sourceText).also { cache[key] = it }
            cue.copy(translatedText = cleanArabicLine(translated).ifBlank { translated.trim() })
        }
    }

    private suspend fun translateText(translator: Translator, text: String): String {
        val raw = translator.translate(text).awaitResult()
        return cleanTranslatedBlock(raw)
    }

    /**
     * Keeps the exact cue count/timing expected by the player while translating short runs with
     * context. We first trust preserved newlines, then Arabic sentence boundaries, and finally
     * distribute words proportionally to source lengths. The fallback never duplicates text.
     */
    private fun mapBlockBackToCues(
        group: List<SubtitleCue>,
        translatedBlock: String,
    ): List<SubtitleCue> {
        if (group.size == 1) {
            return listOf(group.first().copy(translatedText = cleanArabicLine(translatedBlock)))
        }

        val directLines = translatedBlock.lines()
            .map(::cleanArabicLine)
            .filter { it.isNotBlank() }
        if (directLines.size == group.size) {
            return group.zip(directLines) { cue, line -> cue.copy(translatedText = line) }
        }

        val sentences = translatedBlock
            .split(Regex("(?<=[.!؟…])\\s+"))
            .map(::cleanArabicLine)
            .filter { it.isNotBlank() }
        if (sentences.size == group.size) {
            return group.zip(sentences) { cue, line -> cue.copy(translatedText = line) }
        }

        val words = cleanArabicLine(translatedBlock).split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < group.size) {
            // Extremely short outputs are rare. Keep the full translation on the first cue and
            // preserve the remaining cues with a minimal continuation marker rather than repeating
            // the same sentence several times on screen.
            return group.mapIndexed { index, cue ->
                cue.copy(translatedText = if (index == 0) words.joinToString(" ") else "…")
            }
        }

        val weights = group.map { cue -> cue.sourceText.count { it.isLetterOrDigit() }.coerceAtLeast(1) }
        val totalWeight = weights.sum().coerceAtLeast(1)
        var cursor = 0
        return group.mapIndexed { index, cue ->
            val remainingCues = group.size - index - 1
            val remainingWords = words.size - cursor
            val take = if (index == group.lastIndex) {
                remainingWords
            } else {
                ((words.size.toDouble() * weights[index].toDouble() / totalWeight.toDouble()).roundToInt())
                    .coerceAtLeast(1)
                    .coerceAtMost((remainingWords - remainingCues).coerceAtLeast(1))
            }
            val line = words.subList(cursor, (cursor + take).coerceAtMost(words.size)).joinToString(" ")
            cursor = (cursor + take).coerceAtMost(words.size)
            cue.copy(translatedText = line)
        }
    }

    private fun cleanTranslatedBlock(value: String): String = value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lines()
        .joinToString("\n") { cleanArabicLine(it) }
        .trim()

    private fun cleanArabicLine(value: String): String = value
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("[ \\t]+"), " ")
        .replace(',', '،')
        .replace(';', '؛')
        .replace('?', '؟')
        .trim(' ', '"', '\'', '`', '«', '»', '“', '”')

    private fun isUsefulArabic(value: String): Boolean {
        if (value.isBlank()) return false
        val letters = value.count { it.isLetter() }.coerceAtLeast(1)
        val arabic = value.count { it in '\u0600'..'\u06FF' }
        return arabic >= 2 && arabic.toFloat() / letters.toFloat() >= 0.45f
    }

    private fun translationNeedsRepair(source: String, arabic: String): Boolean {
        if (arabic.isBlank()) return true
        if (normalizeTurkish(source) == normalizeTurkish(arabic)) return true
        if (!isUsefulArabic(arabic) && source.count { it.isLetter() } >= 8) return true
        if (Regex("^(?:بالطبع|إليك|الترجمة العربية)[:، ]").containsMatchIn(arabic.trim())) return true
        return false
    }

    override fun close() {
        runCatching { client?.close() }
        client = null
    }

    companion object {
        private const val TAG = "ManzlMLKit"
        private const val MAX_GROUP_CUES = 3
        private const val MAX_GROUP_CHARS = 180
        private const val MAX_GROUP_SPAN_MS = 7_000L
        private const val MAX_GROUP_GAP_MS = 900L
        private const val INDIVIDUAL_RETRY_MIN_MS = 8_000L
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
            .lowercase(TURKISH_LOCALE)
            .trim()
            .replace(Regex("[.!?…]+$"), "")
            .replace(Regex("\\s+"), " ")

        private fun buildContextGroups(cues: List<SubtitleCue>): List<List<SubtitleCue>> {
            if (cues.isEmpty()) return emptyList()
            val ordered = cues.sortedBy { it.startMs }
            val groups = mutableListOf<MutableList<SubtitleCue>>()

            for (cue in ordered) {
                val current = groups.lastOrNull()
                if (current == null) {
                    groups += mutableListOf(cue)
                    continue
                }

                val first = current.first()
                val previous = current.last()
                val gap = (cue.startMs - previous.endMs).coerceAtLeast(0L)
                val span = cue.endMs - first.startMs
                val characters = current.sumOf { it.sourceText.length } + cue.sourceText.length
                val previousEndsSentence = previous.sourceText.trimEnd().lastOrNull() in setOf('.', '!', '?', '…')
                val sentenceBoundary = previousEndsSentence && current.size >= 2 && gap >= 250L

                val canJoin = current.size < MAX_GROUP_CUES &&
                    gap <= MAX_GROUP_GAP_MS &&
                    span <= MAX_GROUP_SPAN_MS &&
                    characters <= MAX_GROUP_CHARS &&
                    !sentenceBoundary

                if (canJoin) current += cue else groups += mutableListOf(cue)
            }
            return groups
        }

        internal fun buildContextGroupsForTest(cues: List<SubtitleCue>): List<List<SubtitleCue>> =
            buildContextGroups(cues)

        internal fun translationNeedsRepairForTest(source: String, arabic: String): Boolean {
            val helper = TurkishArabicTranslator::class.java
            // Keep tests independent from Android/ML Kit instantiation by mirroring the pure checks.
            if (arabic.isBlank()) return true
            if (normalizeTurkish(source) == normalizeTurkish(arabic)) return true
            val letters = arabic.count { it.isLetter() }.coerceAtLeast(1)
            val arabicLetters = arabic.count { it in '\u0600'..'\u06FF' }
            if (arabicLetters < 2 || (arabicLetters.toFloat() / letters.toFloat() < 0.45f && source.count { it.isLetter() } >= 8)) {
                return true
            }
            return Regex("^(?:بالطبع|إليك|الترجمة العربية)[:، ]").containsMatchIn(arabic.trim())
        }

        internal fun maxContextGroupSizeForTest(): Int = MAX_GROUP_CUES
        internal fun maxContextCharsForTest(): Int = MAX_GROUP_CHARS
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value ->
        if (continuation.isActive) continuation.resume(value)
    }
    addOnFailureListener { error ->
        if (continuation.isActive) continuation.resumeWithException(error)
    }
    addOnCanceledListener {
        if (continuation.isActive) continuation.cancel()
    }
}
