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

/**
 * Primary Turkish speech recognizer. Every Silero-confirmed speech window is transcribed; there is
 * no fast ASR gate allowed to discard dialogue before Whisper sees it.
 */
class WhisperRepairEngine(private val context: Context) {
    suspend fun prepareModel(): Boolean = runCatching {
        WhisperModelManager(context).ensureModel()
    }.isSuccess

    fun isModelInstalled(): Boolean = WhisperModelManager(context).isInstalled()

    suspend fun transcribeAll(
        windows: List<SpeechWindow>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<SubtitleCue> = withContext(Dispatchers.Default) {
        if (windows.isEmpty()) return@withContext emptyList()
        val ordered = windows.sortedBy { it.startMs }
        val modelFile = WhisperModelManager(context).ensureModel()
        val model = Whisper.loadModel(context, modelFile.absolutePath)
        val raw = mutableListOf<SubtitleCue>()

        try {
            ordered.forEachIndexed { index, window ->
                currentCoroutineContext().ensureActive()
                val result = runCatching {
                    Whisper.transcribe(
                        model,
                        window.wavFile.absolutePath,
                        WhisperConfig(language = "tr"),
                    )
                }.getOrElse { error ->
                    throw IllegalStateException(
                        "تعذر الاستماع إلى مقطع حوار عند ${formatTime(window.startMs)}.",
                        error,
                    )
                }

                result.segments.orEmpty().forEach { segment ->
                    val text = cleanTurkishTranscript(segment.text)
                    if (text.isNotBlank()) {
                        val start = (window.startMs + segment.startMs)
                            .coerceIn(window.startMs, window.endMs)
                        val end = (window.startMs + segment.endMs)
                            .coerceIn(start + 250L, window.endMs.coerceAtLeast(start + 250L))
                        raw += SubtitleCue(
                            startMs = start,
                            endMs = end,
                            sourceText = text,
                            confidence = 0.99f,
                        )
                    }
                }
                window.wavFile.delete()
                onProgress(index + 1, ordered.size)
            }
            normalizeWhisperTimeline(raw)
        } finally {
            ordered.forEach { it.wavFile.delete() }
            Whisper.releaseModel(model)
        }
    }

    private fun cleanTurkishTranscript(text: String): String = text
        .replace(Regex("^\\s*[-–—]+\\s*"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun normalizeWhisperTimeline(input: List<SubtitleCue>): List<SubtitleCue> {
        if (input.isEmpty()) return emptyList()
        val sorted = input.sortedBy { it.startMs }
        val result = mutableListOf<SubtitleCue>()
        for (cue in sorted) {
            if (cue.sourceText.isBlank()) continue
            val previous = result.lastOrNull()
            val sameText = previous != null &&
                normalizeText(previous.sourceText) == normalizeText(cue.sourceText)
            val near = previous != null && cue.startMs <= previous.endMs + 650L
            if (sameText && near) {
                if (cue.endMs > previous.endMs) {
                    result[result.lastIndex] = previous.copy(endMs = cue.endMs)
                }
            } else {
                result += cue
            }
        }
        return result
    }

    private fun normalizeText(text: String): String = text
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), "")

    private fun formatTime(ms: Long): String {
        val seconds = ms.coerceAtLeast(0L) / 1_000L
        return "%02d:%02d".format(seconds / 60L, seconds % 60L)
    }
}

private class WhisperModelManager(private val context: Context) {
    companion object {
        // Small multilingual Q5_1 is much more capable than base for Turkish while remaining around
        // 182 MiB. It is released before the translation model is loaded, so RAM does not stack.
        private const val MODEL_NAME = "ggml-small-q5_1.bin"
        private const val MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin?download=true"
        private val LEGACY_MODELS = listOf("ggml-base.bin", "ggml-base-q5_1.bin")
        private const val MIN_VALID_BYTES = 170L * 1024L * 1024L
        private const val REQUIRED_FREE_BYTES = 260L * 1024L * 1024L
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
        val already = partial.takeIf { it.isFile }?.length() ?: 0L
        check(modelDir.usableSpace >= (REQUIRED_FREE_BYTES - already).coerceAtLeast(0L)) {
            "المساحة الحرة غير كافية لتنزيل نموذج الاستماع التركي الدقيق."
        }
        download(partial)
        check(partial.length() >= MIN_VALID_BYTES) {
            "تعذر تنزيل نموذج الاستماع التركي الدقيق كاملًا."
        }
        if (model.exists()) model.delete()
        check(partial.renameTo(model)) { "تعذر تثبيت نموذج الاستماع التركي الدقيق." }
        deleteLegacy(modelDir)
        model
    }

    private fun deleteLegacy(modelDir: File) {
        LEGACY_MODELS.forEach { name ->
            runCatching { File(modelDir, name).delete() }
            runCatching { File(modelDir, "$name.part").delete() }
        }
    }

    private suspend fun download(target: File) {
        val existing = target.takeIf { it.isFile }?.length() ?: 0L
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept-Encoding", "identity")
            if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
        }

        try {
            connection.connect()
            check(connection.responseCode in 200..299) {
                "فشل تنزيل نموذج الاستماع (${connection.responseCode})."
            }
            val resumed = connection.responseCode == HttpURLConnection.HTTP_PARTIAL && existing > 0L
            if (!resumed && existing > 0L) target.delete()
            FileOutputStream(target, resumed).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(192 * 1024)
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
