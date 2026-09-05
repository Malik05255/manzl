package com.manzl.movietranslator

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TurkishArabicTranslator : AutoCloseable {
    private val translator: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.TURKISH)
            .setTargetLanguage(TranslateLanguage.ARABIC)
            .build()
    )

    suspend fun ensureModel() {
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
    }

    suspend fun translate(
        cues: List<SubtitleCue>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<SubtitleCue> {
        if (cues.isEmpty()) return emptyList()

        // ASR engines often split one spoken sentence into several tiny fragments.
        // Translating those fragments separately produces literal/unnatural Arabic, so first
        // reconstruct short semantic dialogue units and translate them as complete utterances.
        val semanticCues = mergeSemanticCues(cues)
        val result = ArrayList<SubtitleCue>(semanticCues.size)
        val batches = buildContextBatches(semanticCues)
        var done = 0

        for (batch in batches) {
            val translatedBatch = if (batch.size == 1) {
                listOf(translateOne(batch.first()))
            } else {
                translateContextBatch(batch) ?: batch.map { translateOne(it) }
            }
            result += translatedBatch
            done += batch.size
            onProgress(done, semanticCues.size)
        }
        return result
    }

    private suspend fun translateOne(cue: SubtitleCue): SubtitleCue {
        val arabic = cleanArabic(translator.translate(cue.sourceText).await())
        return cue.copy(translatedText = arabic.ifBlank { cue.sourceText })
    }

    private suspend fun translateContextBatch(batch: List<SubtitleCue>): List<SubtitleCue>? {
        val payload = buildString {
            batch.forEachIndexed { index, cue ->
                append(marker(index)).append(' ').append(cue.sourceText.trim()).append('\n')
            }
        }
        val translated = translator.translate(payload).await()
        val pieces = parseMarkedTranslation(translated, batch.size) ?: return null
        return batch.mapIndexed { index, cue ->
            val naturalArabic = cleanArabic(pieces[index])
            cue.copy(translatedText = naturalArabic.ifBlank { cue.sourceText })
        }
    }

    private fun buildContextBatches(cues: List<SubtitleCue>): List<List<SubtitleCue>> {
        val batches = mutableListOf<MutableList<SubtitleCue>>()
        for (cue in cues) {
            val current = batches.lastOrNull()
            val previous = current?.lastOrNull()
            val canJoin = current != null &&
                current.size < MAX_BATCH_SIZE &&
                previous != null &&
                cue.startMs - previous.endMs <= MAX_CONTEXT_GAP_MS

            if (canJoin) {
                current!!.add(cue)
            } else {
                batches += mutableListOf(cue)
            }
        }
        return batches
    }

    private fun parseMarkedTranslation(text: String, expected: Int): List<String>? {
        val positions = MARKER_REGEX.findAll(text).toList()
        if (positions.size != expected) return null
        val pieces = MutableList(expected) { "" }
        positions.forEachIndexed { positionIndex, match ->
            val index = match.groupValues[1].toIntOrNull() ?: return null
            if (index !in 0 until expected) return null
            val start = match.range.last + 1
            val end = if (positionIndex + 1 < positions.size) positions[positionIndex + 1].range.first else text.length
            pieces[index] = text.substring(start, end).trim()
        }
        return if (pieces.all { it.isNotBlank() }) pieces else null
    }

    private fun marker(index: Int): String = "§$index§"

    override fun close() = translator.close()

    companion object {
        private const val MAX_BATCH_SIZE = 4
        private const val MAX_CONTEXT_GAP_MS = 4_500L
        private const val MAX_SEMANTIC_GAP_MS = 900L
        private const val MAX_SEMANTIC_DURATION_MS = 6_500L
        private const val MAX_SEMANTIC_CHARS = 140
        private val MARKER_REGEX = Regex("§(\\d+)§")
        private val SENTENCE_END = Regex("[.!?…]\\s*$")
        private val SPACE_BEFORE_PUNCTUATION = Regex("\\s+([،؛:,.!?؟])")
        private val REPEATED_SPACES = Regex("\\s+")

        internal fun mergeSemanticCuesForTest(cues: List<SubtitleCue>): List<SubtitleCue> =
            mergeSemanticCues(cues)

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
    addOnCanceledListener {
        continuation.cancel()
    }
}
