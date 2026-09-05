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
    val mandatory: Boolean = false,
)

data class TranscriptionResult(
    val cues: List<SubtitleCue>,
    val repairCandidates: List<RepairCandidate>,
)

class WhisperRepairEngine(private val context: Context) {
    suspend fun prepareModel(): Boolean = runCatching {
        WhisperModelManager(context).ensureModel()
    }.isSuccess

    fun isModelInstalled(): Boolean = WhisperModelManager(context).isInstalled()

    suspend fun repair(
        original: List<SubtitleCue>,
        candidates: List<RepairCandidate>,
        maxWallTimeMs: Long = Long.MAX_VALUE,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<SubtitleCue> = withContext(Dispatchers.Default) {
        if (candidates.isEmpty()) return@withContext original

        val ordered = candidates.sortedWith(
            compareByDescending<RepairCandidate> { it.mandatory }
                .thenBy { it.confidence }
                .thenBy { it.startMs }
        )
        val modelFile = WhisperModelManager(context).ensureModel()
        val model = Whisper.loadModel(context, modelFile.absolutePath)
        val repaired = original.toMutableList()
        val startedAt = SystemClock.elapsedRealtime()

        try {
            var processed = 0
            for (candidate in ordered) {
                currentCoroutineContext().ensureActive()
                val budgetExpired = SystemClock.elapsedRealtime() - startedAt >= maxWallTimeMs
                if (budgetExpired && !candidate.mandatory) break

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
        // The free whisper-android package officially supports the normal file-transcription path.
        // Use the full multilingual base checkpoint for reliability and accuracy; it is unloaded
        // before Qwen is loaded, so the extra model size does not stack in RAM with translation.
        private const val MODEL_NAME = "ggml-base.bin"
        private const val MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin?download=true"
        private const val LEGACY_MODEL_NAME = "ggml-base-q5_1.bin"
        private const val MIN_VALID_BYTES = 120L * 1024L * 1024L
    }

    fun isInstalled(): Boolean {
        val model = File(File(context.filesDir, "models"), MODEL_NAME)
        return model.isFile && model.length() >= MIN_VALID_BYTES
    }

    suspend fun ensureModel(): File = withContext(Dispatchers.IO) {
        val modelDir = File(context.filesDir, "models").apply { mkdirs() }
        val model = File(modelDir, MODEL_NAME)
        if (model.isFile && model.length() >= MIN_VALID_BYTES) {
            deleteLegacy(modelDir)
            return@withContext model
        }

        val partial = File(modelDir, "$MODEL_NAME.part")
        download(partial)
        check(partial.length() >= MIN_VALID_BYTES) { "تعذر تنزيل نموذج تحسين الاستماع." }
        if (model.exists()) model.delete()
        check(partial.renameTo(model)) { "تعذر تثبيت نموذج تحسين الاستماع." }
        deleteLegacy(modelDir)
        model
    }

    private fun deleteLegacy(modelDir: File) {
        runCatching { File(modelDir, LEGACY_MODEL_NAME).delete() }
        runCatching { File(modelDir, "$LEGACY_MODEL_NAME.part").delete() }
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
