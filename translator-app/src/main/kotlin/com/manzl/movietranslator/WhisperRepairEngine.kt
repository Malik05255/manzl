package com.manzl.movietranslator

import android.content.Context
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Primary Turkish speech recognizer. Every Silero-confirmed speech window is transcribed; there is
 * no fast ASR gate allowed to discard dialogue before Whisper sees it.
 *
 * Whisper's encoder has a substantial fixed cost per call. Silero can produce many short windows in
 * movie dialogue, so nearby windows are coalesced into timeline-preserving chunks before inference.
 * This keeps the accurate Small model while avoiding dozens of expensive Whisper invocations for a
 * short clip.
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

        val original = windows.sortedBy { it.startMs }
        val ordered = coalesceSpeechWindows(original, context.cacheDir)
        val modelFile = WhisperModelManager(context).ensureModel()
        val model = Whisper.loadModel(context, modelFile.absolutePath)
        val raw = mutableListOf<SubtitleCue>()
        val threads = whisperThreadCount(Runtime.getRuntime().availableProcessors())

        try {
            ordered.forEachIndexed { index, window ->
                currentCoroutineContext().ensureActive()
                val result = runCatching {
                    Whisper.transcribe(
                        model,
                        window.wavFile.absolutePath,
                        WhisperConfig(
                            language = "tr",
                            threads = threads,
                        ),
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
            original.forEach { it.wavFile.delete() }
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

internal fun planWhisperBatches(windows: List<SpeechWindow>): List<List<SpeechWindow>> {
    if (windows.isEmpty()) return emptyList()
    val sorted = windows.sortedBy { it.startMs }
    val batches = mutableListOf<MutableList<SpeechWindow>>()

    for (window in sorted) {
        val current = batches.lastOrNull()
        if (current == null) {
            batches += mutableListOf(window)
            continue
        }

        val first = current.first()
        val previous = current.last()
        val gapMs = (window.startMs - previous.endMs).coerceAtLeast(0L)
        val spanMs = window.endMs - first.startMs
        if (gapMs <= MAX_WHISPER_BATCH_GAP_MS && spanMs <= MAX_WHISPER_BATCH_SPAN_MS) {
            current += window
        } else {
            batches += mutableListOf(window)
        }
    }
    return batches
}

private fun coalesceSpeechWindows(windows: List<SpeechWindow>, cacheDir: File): List<SpeechWindow> {
    val planned = planWhisperBatches(windows)
    return planned.mapIndexed { index, batch ->
        if (batch.size == 1) return@mapIndexed batch.first()

        val target = File(cacheDir, "whisper_batch_${System.nanoTime()}_$index.wav")
        val pcm = ByteArrayOutputStream()
        var cursorMs = batch.first().startMs

        try {
            batch.forEach { window ->
                val gapMs = window.startMs - cursorMs
                if (gapMs > 0L) {
                    val silenceBytes = ((gapMs * PCM_BYTES_PER_SECOND) / 1_000L)
                        .coerceAtMost(Int.MAX_VALUE.toLong())
                        .toInt()
                    if (silenceBytes > 0) pcm.write(ByteArray(silenceBytes))
                }

                val source = readPcm16MonoWav(window.wavFile)
                val overlapMs = (-gapMs).coerceAtLeast(0L)
                val skipBytes = ((overlapMs * PCM_BYTES_PER_SECOND) / 1_000L)
                    .coerceAtMost(source.size.toLong())
                    .toInt()
                if (skipBytes < source.size) {
                    pcm.write(source, skipBytes, source.size - skipBytes)
                }
                cursorMs = maxOf(cursorMs, window.endMs)
            }

            writePcm16MonoWav(target, pcm.toByteArray(), WHISPER_SAMPLE_RATE)
            batch.forEach { it.wavFile.delete() }
            SpeechWindow(
                startMs = batch.first().startMs,
                endMs = batch.last().endMs,
                wavFile = target,
            )
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }
}

private fun readPcm16MonoWav(file: File): ByteArray = file.inputStream().use { input ->
    val header = ByteArray(WAV_HEADER_BYTES)
    var read = 0
    while (read < header.size) {
        val count = input.read(header, read, header.size - read)
        check(count > 0) { "ملف الصوت المؤقت غير مكتمل." }
        read += count
    }
    check(String(header, 0, 4, Charsets.US_ASCII) == "RIFF") { "ملف الصوت المؤقت غير صالح." }
    check(String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { "ملف الصوت المؤقت غير صالح." }
    input.readBytes()
}

private fun writePcm16MonoWav(file: File, pcm: ByteArray, sampleRate: Int) {
    file.parentFile?.mkdirs()
    FileOutputStream(file).use { out ->
        val dataSize = pcm.size
        val byteRate = sampleRate * 2
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(byteRate)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()
        out.write(header)
        out.write(pcm)
    }
}

internal fun whisperThreadCount(availableProcessors: Int): Int = when {
    availableProcessors >= 8 -> 4
    availableProcessors >= 6 -> 3
    else -> 2
}

private const val WHISPER_SAMPLE_RATE = 16_000
private const val PCM_BYTES_PER_SECOND = WHISPER_SAMPLE_RATE * 2L
private const val WAV_HEADER_BYTES = 44
private const val MAX_WHISPER_BATCH_GAP_MS = 2_500L
private const val MAX_WHISPER_BATCH_SPAN_MS = 28_000L

private class WhisperModelManager(private val context: Context) {
    companion object {
        // Keep Small Q5_1 for Turkish accuracy. Performance is recovered by batching nearby speech
        // into near-Whisper-native windows instead of invoking the encoder for every short VAD cut.
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
