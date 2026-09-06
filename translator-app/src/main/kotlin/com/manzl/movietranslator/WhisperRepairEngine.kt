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
import java.util.Locale

/**
 * Two-pass Turkish ASR optimized for phone latency.
 *
 * Pass 1 transcribes every Silero-confirmed batch with quantized Whisper Base. Only suspicious
 * batches are re-run with Whisper Small, capped to a small fraction of the clip. This keeps Base
 * speed for most dialogue while spending Small compute where it can actually improve accuracy.
 */
class WhisperRepairEngine(private val context: Context) {
    suspend fun prepareModel(): Boolean = runCatching {
        WhisperModelManager(context).ensurePrimaryModel()
    }.isSuccess

    fun isModelInstalled(): Boolean = WhisperModelManager(context).isPrimaryInstalled()

    suspend fun transcribeAll(
        windows: List<SpeechWindow>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<SubtitleCue> = withContext(Dispatchers.Default) {
        if (windows.isEmpty()) return@withContext emptyList()

        val original = windows.sortedBy { it.startMs }
        val ordered = coalesceSpeechWindows(original, context.cacheDir)
        val manager = WhisperModelManager(context)
        val primaryFile = manager.ensurePrimaryModel()
        val primary = Whisper.loadModel(context, primaryFile.absolutePath)
        val threads = whisperThreadCount(Runtime.getRuntime().availableProcessors())
        val batches = ArrayList<WhisperBatchResult>(ordered.size)

        try {
            ordered.forEachIndexed { index, window ->
                currentCoroutineContext().ensureActive()
                val cues = transcribeWindow(primary, window, threads)
                val suspicion = whisperBatchSuspicion(window.endMs - window.startMs, cues)
                batches += WhisperBatchResult(window, cues, suspicion)
                onProgress(index + 1, ordered.size)
            }
        } finally {
            Whisper.releaseModel(primary)
        }

        val repairCandidates = batches
            .filter { it.suspicion >= REPAIR_SUSPICION_THRESHOLD }
            .sortedByDescending { it.suspicion }
            .take(maxRepairBatches(batches.size))

        if (repairCandidates.isNotEmpty()) {
            // Small is optional and never allowed to make the whole movie fail. If the model cannot
            // be downloaded, Base output remains usable and translation proceeds.
            val repairFile = runCatching { manager.ensureRepairModel() }.getOrNull()
            if (repairFile != null) {
                val repairModel = runCatching {
                    Whisper.loadModel(context, repairFile.absolutePath)
                }.getOrNull()
                if (repairModel != null) {
                    try {
                        repairCandidates.forEach { batch ->
                            currentCoroutineContext().ensureActive()
                            val repaired = runCatching {
                                transcribeWindow(repairModel, batch.window, threads)
                            }.getOrDefault(emptyList())
                            val repairedSuspicion = whisperBatchSuspicion(
                                batch.window.endMs - batch.window.startMs,
                                repaired,
                            )
                            if (
                                repaired.isNotEmpty() &&
                                (batch.cues.isEmpty() || repairedSuspicion + REPAIR_ACCEPT_MARGIN < batch.suspicion)
                            ) {
                                batch.cues = repaired
                                batch.suspicion = repairedSuspicion
                            }
                        }
                    } finally {
                        Whisper.releaseModel(repairModel)
                    }
                }
            }
        }

        try {
            normalizeWhisperTimeline(batches.flatMap { it.cues })
        } finally {
            ordered.forEach { it.wavFile.delete() }
            original.forEach { it.wavFile.delete() }
        }
    }

    private fun transcribeWindow(
        model: Long,
        window: SpeechWindow,
        threads: Int,
    ): List<SubtitleCue> {
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

        return result.segments.orEmpty().mapNotNull { segment ->
            val text = cleanTurkishTranscript(segment.text)
            if (text.isBlank()) return@mapNotNull null
            val start = (window.startMs + segment.startMs)
                .coerceIn(window.startMs, window.endMs)
            val end = (window.startMs + segment.endMs)
                .coerceIn(start + 250L, window.endMs.coerceAtLeast(start + 250L))
            SubtitleCue(
                startMs = start,
                endMs = end,
                sourceText = text,
                confidence = 0.99f,
            )
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
        .lowercase(Locale.forLanguageTag("tr"))
        .replace(Regex("[^\\p{L}\\p{N}]+"), "")

    private fun formatTime(ms: Long): String {
        val seconds = ms.coerceAtLeast(0L) / 1_000L
        return "%02d:%02d".format(seconds / 60L, seconds % 60L)
    }

    private data class WhisperBatchResult(
        val window: SpeechWindow,
        var cues: List<SubtitleCue>,
        var suspicion: Float,
    )

    companion object {
        private const val REPAIR_SUSPICION_THRESHOLD = 2.5f
        private const val REPAIR_ACCEPT_MARGIN = 0.35f

        internal fun maxRepairBatches(batchCount: Int): Int {
            if (batchCount <= 0) return 0
            return ((batchCount + 2) / 3).coerceIn(1, 4)
        }
    }
}

/**
 * Higher means the Base result deserves Small repair. The heuristic intentionally catches missing
 * dialogue and obvious looping without pretending to estimate linguistic correctness.
 */
internal fun whisperBatchSuspicion(
    windowDurationMs: Long,
    cues: List<SubtitleCue>,
): Float {
    if (cues.isEmpty()) return 10f

    val text = cues.joinToString(" ") { it.sourceText.trim() }.trim()
    val letters = text.count { it.isLetter() }
    val durationSeconds = (windowDurationMs.coerceAtLeast(1L) / 1_000f).coerceAtLeast(0.5f)
    val words = text
        .lowercase(Locale.forLanguageTag("tr"))
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotBlank() }

    var score = 0f
    val lettersPerSecond = letters.toFloat() / durationSeconds
    if (lettersPerSecond < 1.4f) score += 3.0f
    else if (lettersPerSecond < 2.2f) score += 1.5f

    if (words.size >= 6) {
        val uniqueRatio = words.toSet().size.toFloat() / words.size.toFloat()
        if (uniqueRatio < 0.42f) score += 2.5f
        else if (uniqueRatio < 0.58f) score += 1.0f
    }

    if (cues.size >= 3) {
        val normalized = cues.map { cue ->
            cue.sourceText.lowercase(Locale.forLanguageTag("tr"))
                .replace(Regex("[^\\p{L}\\p{N}]+"), "")
        }
        val distinctRatio = normalized.toSet().size.toFloat() / normalized.size.toFloat()
        if (distinctRatio <= 0.5f) score += 2.0f
    }

    if (letters < 5 && windowDurationMs > 2_000L) score += 2.0f
    return score
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
    private data class ModelSpec(
        val name: String,
        val url: String,
        val expectedBytes: Long,
        val minBytes: Long,
    )

    companion object {
        private val PRIMARY = ModelSpec(
            name = "ggml-base-q5_1.bin",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin?download=true",
            expectedBytes = 60_000_000L,
            minBytes = 54L * 1024L * 1024L,
        )
        private val REPAIR = ModelSpec(
            name = "ggml-small-q5_1.bin",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin?download=true",
            expectedBytes = 190_000_000L,
            minBytes = 170L * 1024L * 1024L,
        )
        private const val DOWNLOAD_HEADROOM_BYTES = 40L * 1024L * 1024L
    }

    fun isPrimaryInstalled(): Boolean = installed(PRIMARY)

    suspend fun ensurePrimaryModel(): File = ensure(PRIMARY)

    suspend fun ensureRepairModel(): File = ensure(REPAIR)

    private fun installed(spec: ModelSpec): Boolean {
        val model = File(File(context.filesDir, "models"), spec.name)
        return model.isFile && model.length() >= spec.minBytes
    }

    private suspend fun ensure(spec: ModelSpec): File = withContext(Dispatchers.IO) {
        val modelDir = File(context.filesDir, "models").apply { mkdirs() }
        val model = File(modelDir, spec.name)
        if (model.isFile && model.length() >= spec.minBytes) return@withContext model

        val partial = File(modelDir, "${spec.name}.part")
        val already = partial.takeIf { it.isFile }?.length() ?: 0L
        val missing = (spec.expectedBytes - already).coerceAtLeast(0L)
        check(modelDir.usableSpace >= missing + DOWNLOAD_HEADROOM_BYTES) {
            "المساحة الحرة غير كافية لتنزيل نموذج الاستماع."
        }
        download(spec, partial)
        check(partial.length() >= spec.minBytes) { "تعذر تنزيل نموذج الاستماع كاملًا." }
        if (model.exists()) model.delete()
        check(partial.renameTo(model)) { "تعذر تثبيت نموذج الاستماع." }
        model
    }

    private suspend fun download(spec: ModelSpec, target: File) {
        val existing = target.takeIf { it.isFile }?.length() ?: 0L
        val connection = (URL(spec.url).openConnection() as HttpURLConnection).apply {
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
