package com.manzl.movietranslator

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Human-oriented Turkish -> Arabic subtitle translator.
 *
 * The primary path is a compact local Qwen model running with llama.cpp. It translates Turkish
 * directly into Arabic. ML Kit is kept only as a lazy safety fallback if the direct model fails.
 *
 * Performance notes:
 * - Adjacent semantic cues are grouped into moderately larger scene batches to reduce expensive
 *   repeated prompt evaluation on mobile CPUs.
 * - If a large direct batch does not preserve all subtitle markers, it is recursively split and
 *   retried before falling back. This keeps the fast path aggressive without sacrificing safety.
 */
class TurkishArabicTranslator(private val context: Context) : AutoCloseable {
    private var directModel: LlamaModel? = null
    private var directFailed = false
    private var fallbackTranslator: Translator? = null

    /** Returns true when the direct Turkish -> Arabic model is active. */
    suspend fun ensureModel(onProgress: (Float) -> Unit = {}): Boolean {
        if (directModel != null) {
            onProgress(1f)
            return true
        }

        if (!directFailed) {
            val loaded = runCatching {
                val modelFile = DirectTranslationModelManager(context).ensureModel(onProgress)
                directModel = Llama.loadModel(
                    modelPath = modelFile.absolutePath,
                    config = LlamaConfig(
                        contextSize = 2_048,
                        // Snapdragon 7 Gen 3 has four fast cores (1 prime + 3 performance).
                        // Staying at four avoids spilling inference work onto efficiency cores.
                        threads = 4,
                        gpuLayers = 0,
                        temperature = 0f,
                        topP = 0.9f,
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

        ensureFallbackModel()
        onProgress(1f)
        return false
    }

    suspend fun translate(
        cues: List<SubtitleCue>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<SubtitleCue> {
        if (cues.isEmpty()) return emptyList()

        // ASR engines often split one spoken sentence into several tiny fragments. Reconstruct
        // complete utterances before translation so the Arabic follows intended meaning, not words.
        val semanticCues = mergeSemanticCues(cues)
        val batches = buildContextBatches(semanticCues)
        val result = ArrayList<SubtitleCue>(semanticCues.size)
        var done = 0

        for (batch in batches) {
            val translated = translateBatch(batch)
            result += translated
            done += batch.size
            onProgress(done, semanticCues.size)
        }
        return result
    }

    private suspend fun translateBatch(batch: List<SubtitleCue>): List<SubtitleCue> {
        val model = directModel
        if (model != null && !directFailed) {
            val direct = runCatching { translateDirectBatch(model, batch) }.getOrNull()
            if (direct != null &&
                direct.size == batch.size &&
                direct.all { hasUsefulArabic(it.translatedText) }
            ) {
                return direct
            }

            // Marker formatting occasionally degrades as a batch grows. Split and retry with the
            // same direct model before using the lower-quality fallback translator.
            if (batch.size > 1) {
                val middle = batch.size / 2
                return translateBatch(batch.subList(0, middle)) +
                    translateBatch(batch.subList(middle, batch.size))
            }
        }

        // Safety fallback is initialized only when actually needed. It is not the normal path.
        return translateFallbackBatch(batch)
    }

    private suspend fun translateDirectBatch(
        model: LlamaModel,
        batch: List<SubtitleCue>,
    ): List<SubtitleCue>? {
        val prompt = buildString {
            appendLine("ترجم الأسطر التالية مباشرة إلى عربية طبيعية مختصرة، وحافظ على كل علامة كما هي:")
            batch.forEachIndexed { index, cue ->
                append(marker(index)).append(' ').append(cue.sourceText.trim()).append('\n')
            }
        }

        // Subtitle output should be concise. A tighter generation ceiling prevents pathological
        // long generations while leaving enough room for natural Arabic and every marker.
        val sourceChars = batch.sumOf { it.sourceText.length }
        val maxTokens = (sourceChars * 3 / 4 + 96).coerceIn(144, 560)
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
            val arabic = cleanArabic(translator.translate(cue.sourceText).await())
            cue.copy(translatedText = arabic.ifBlank { cue.sourceText })
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

    private fun buildContextBatches(cues: List<SubtitleCue>): List<List<SubtitleCue>> {
        val batches = mutableListOf<MutableList<SubtitleCue>>()
        for (cue in cues) {
            val current = batches.lastOrNull()
            val previous = current?.lastOrNull()
            val canJoin = current != null &&
                current.size < MAX_BATCH_SIZE &&
                previous != null &&
                cue.startMs - previous.endMs <= MAX_CONTEXT_GAP_MS &&
                current.sumOf { it.sourceText.length } + cue.sourceText.length <= MAX_BATCH_CHARS

            if (canJoin) current!!.add(cue) else batches += mutableListOf(cue)
        }
        return batches
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

    override fun close() {
        directModel?.let { runCatching { Llama.releaseModel(it) } }
        directModel = null
        fallbackTranslator?.close()
        fallbackTranslator = null
    }

    companion object {
        private const val MAX_BATCH_SIZE = 6
        private const val MAX_BATCH_CHARS = 720
        private const val MAX_CONTEXT_GAP_MS = 4_500L
        private const val MAX_SEMANTIC_GAP_MS = 900L
        private const val MAX_SEMANTIC_DURATION_MS = 6_500L
        private const val MAX_SEMANTIC_CHARS = 140

        private val MARKER_REGEX = Regex("§(\\d+)§")
        private val SENTENCE_END = Regex("[.!?…]\\s*$")
        private val SPACE_BEFORE_PUNCTUATION = Regex("\\s+([،؛:,.!?؟])")
        private val REPEATED_SPACES = Regex("\\s+")
        private val THINK_BLOCK = Regex("<think>.*?</think>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

        // Keep this deliberately compact: it is evaluated on every local model call.
        private val DIRECT_SYSTEM_PROMPT = """
            أنت مترجم أفلام من التركية إلى العربية. انقل المعنى والسياق لا الكلمات حرفيًا.
            اكتب عربية فصحى طبيعية ومختصرة، وافهم العامية والأمثال والتهكم والمشاعر، وحافظ على الأسماء والضمائر.
            لا تشرح ولا تضف شيئًا. أعد علامات §n§ نفسها وبالترتيب، وبعد كل علامة الترجمة العربية فقط.
        """.trimIndent()

        internal fun mergeSemanticCuesForTest(cues: List<SubtitleCue>): List<SubtitleCue> =
            mergeSemanticCues(cues)

        internal fun buildContextBatchesForTest(cues: List<SubtitleCue>): List<List<SubtitleCue>> {
            val translator = TestBatchBuilder
            return translator.build(cues)
        }

        private object TestBatchBuilder {
            fun build(cues: List<SubtitleCue>): List<List<SubtitleCue>> {
                val batches = mutableListOf<MutableList<SubtitleCue>>()
                for (cue in cues) {
                    val current = batches.lastOrNull()
                    val previous = current?.lastOrNull()
                    val canJoin = current != null &&
                        current.size < MAX_BATCH_SIZE &&
                        previous != null &&
                        cue.startMs - previous.endMs <= MAX_CONTEXT_GAP_MS &&
                        current.sumOf { it.sourceText.length } + cue.sourceText.length <= MAX_BATCH_CHARS
                    if (canJoin) current!!.add(cue) else batches += mutableListOf(cue)
                }
                return batches
            }
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
