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
        val result = ArrayList<SubtitleCue>(cues.size)
        val batches = buildContextBatches(cues)
        var done = 0

        for (batch in batches) {
            val translatedBatch = if (batch.size == 1) {
                listOf(translateOne(batch.first()))
            } else {
                translateContextBatch(batch) ?: batch.map { translateOne(it) }
            }
            result += translatedBatch
            done += batch.size
            onProgress(done, cues.size)
        }
        return result
    }

    private suspend fun translateOne(cue: SubtitleCue): SubtitleCue {
        val arabic = translator.translate(cue.sourceText).await().trim()
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
            cue.copy(translatedText = pieces[index].trim().ifBlank { cue.sourceText })
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

    private companion object {
        const val MAX_BATCH_SIZE = 5
        const val MAX_CONTEXT_GAP_MS = 3_500L
        val MARKER_REGEX = Regex("§(\\d+)§")
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
