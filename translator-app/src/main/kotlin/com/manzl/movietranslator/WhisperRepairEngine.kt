package com.manzl.movietranslator

import android.content.Context
import android.os.SystemClock
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
        maxWallTimeMs: Long = Long.MAX_VALUE,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<SubtitleCue> = withContext(Dispatchers.Default) {
        if (candidates.isEmpty() || maxWallTimeMs <= 0L) {
            candidates.forEach { it.wavFile.delete() }
            return@withContext original
        }

        // Worst-confidence clips first. The wall-clock budget makes Whisper a targeted repair tool
        // instead of allowing it to dominate a two-hour movie's total processing time.
        val ordered = candidates.sortedBy { it.confidence }
        val modelFile = WhisperModelManager(context).ensureModel()
        val model = Whisper.loadModel(context, modelFile.absolutePath)
        val repaired = original.toMutableList()
        val startedAt = SystemClock.elapsedRealtime()

        try {
            var processed = 0
            for (candidate in ordered) {
                currentCoroutineContext().ensureActive()
                if (SystemClock.elapsedRealtime() - startedAt >= maxWallTimeMs) break

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
                processed++
                onProgress(processed, ordered.size)
            }
            repaired.sortedBy { it.startMs }
        } finally {
            ordered.forEach { it.wavFile.delete() }
            Whisper.releaseModel(model)
        }
    }
}

private class WhisperModelManager(private val context: Context) {
    companion object {
        // Q5_1 keeps the multilingual base model but cuts disk/RAM pressure substantially versus
        // the full base checkpoint. Whisper is only used for uncertain clips, never whole-film ASR.
        private const val MODEL_NAME = "ggml-base-q5_1.bin"
        private const val MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin?download=true"
        private const val MIN_VALID_BYTES = 50L * 1024L * 1024L
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
            setRequestProperty("Accept-Encoding", "identity")
            if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
        }

        try {
            connection.connect()
            check(connection.responseCode in 200..299) {
                "فشل تنزيل نموذج تحسين الاستماع (${connection.responseCode})."
            }
            val resumed = connection.responseCode == HttpURLConnection.HTTP_PARTIAL && existing > 0L
            if (!resumed && existing > 0L) target.delete()
            FileOutputStream(target, resumed).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
