package com.manzl.movietranslator

import android.content.Context
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class RepairCandidate(
    val startMs: Long,
    val endMs: Long,
    val confidence: Float,
    val wavFile: File,
)

data class TranscriptionResult(
    val cues: List<SubtitleCue>,
    val repairCandidates: List<RepairCandidate>,
)

class WhisperRepairEngine(private val context: Context) {
    suspend fun repair(
        original: List<SubtitleCue>,
        candidates: List<RepairCandidate>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<SubtitleCue> = withContext(Dispatchers.Default) {
        if (candidates.isEmpty()) return@withContext original

        val modelFile = WhisperModelManager(context).ensureModel()
        val model = Whisper.loadModel(context, modelFile.absolutePath)
        val repaired = original.toMutableList()

        try {
            candidates.forEachIndexed { index, candidate ->
                currentCoroutineContext().ensureActive()
                val result = runCatching {
                    Whisper.transcribe(
                        model,
                        candidate.wavFile.absolutePath,
                        WhisperConfig(language = "tr"),
                    )
                }.getOrNull()

                val replacement = result?.segments.orEmpty().mapNotNull { segment ->
                    val text = segment.text.trim()
                    if (text.isBlank()) null else SubtitleCue(
                        startMs = candidate.startMs + segment.startMs,
                        endMs = (candidate.startMs + segment.endMs)
                            .coerceAtLeast(candidate.startMs + segment.startMs + 350L),
                        sourceText = text,
                        confidence = 0.98f,
                    )
                }

                if (replacement.isNotEmpty()) {
                    repaired.removeAll { cue ->
                        cue.startMs < candidate.endMs && cue.endMs > candidate.startMs
                    }
                    repaired += replacement
                }

                candidate.wavFile.delete()
                onProgress(index + 1, candidates.size)
            }
            repaired.sortedBy { it.startMs }
        } finally {
            candidates.forEach { it.wavFile.delete() }
            Whisper.releaseModel(model)
        }
    }
}

private class WhisperModelManager(private val context: Context) {
    companion object {
        private const val MODEL_NAME = "ggml-base.bin"
        private const val MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin?download=true"
        private const val MIN_VALID_BYTES = 100L * 1024L * 1024L
    }

    suspend fun ensureModel(): File = withContext(Dispatchers.IO) {
        val modelDir = File(context.filesDir, "models").apply { mkdirs() }
        val model = File(modelDir, MODEL_NAME)
        if (model.isFile && model.length() >= MIN_VALID_BYTES) return@withContext model

        val partial = File(modelDir, "$MODEL_NAME.part")
        download(partial)
        check(partial.length() >= MIN_VALID_BYTES) { "تعذر تنزيل نموذج تحسين الاستماع." }
        if (model.exists()) model.delete()
        check(partial.renameTo(model)) { "تعذر تثبيت نموذج تحسين الاستماع." }
        model
    }

    private suspend fun download(target: File) {
        val existing = target.takeIf { it.isFile }?.length() ?: 0L
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
        }

        try {
            connection.connect()
            check(connection.responseCode in 200..299) {
                "فشل تنزيل نموذج تحسين الاستماع (${connection.responseCode})."
            }
            val resumed = connection.responseCode == HttpURLConnection.HTTP_PARTIAL && existing > 0L
            FileOutputStream(target, resumed).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
