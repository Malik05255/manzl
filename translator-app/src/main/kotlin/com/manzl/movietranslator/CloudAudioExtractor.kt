package com.manzl.movietranslator

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil

internal data class CloudAudioPart(
    val file: File,
    val offsetMs: Long,
    val durationMs: Long,
)

/**
 * Prepares an audio-only derivative for cloud speech recognition.
 *
 * The primary path deliberately avoids FFmpeg. Android's MediaExtractor/MediaMuxer remuxes the
 * existing compressed audio track into small audio-only chunks without decoding the video and
 * without transcoding the audio. This is substantially faster and avoids vendor-specific SAF /
 * FFmpeg failures seen on long movies. FFmpeg remains only as a compatibility fallback for source
 * audio codecs that Android cannot remux directly.
 */
internal class CloudAudioExtractor(private val context: Context) {
    suspend fun prepare(
        uri: Uri,
        durationMs: Long,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<CloudAudioPart> = withContext(Dispatchers.IO) {
        require(durationMs in 1..MAX_MOVIE_DURATION_MS) {
            "يدعم المسار السحابي أفلامًا حتى 3 ساعات حاليًا."
        }

        val outputDir = File(context.cacheDir, "cloud_audio").apply { mkdirs() }
        outputDir.listFiles()?.filter { it.name.startsWith("cloud_") }?.forEach { it.delete() }

        val nativeAttempt = runCatching {
            prepareWithNativeRemux(uri, durationMs, outputDir, onProgress)
        }
        if (nativeAttempt.isSuccess) return@withContext nativeAttempt.getOrThrow()

        Log.w(TAG, "Native audio remux failed; falling back to FFmpeg", nativeAttempt.exceptionOrNull())
        outputDir.listFiles()?.filter { it.name.startsWith("cloud_") }?.forEach { it.delete() }

        val ffmpegAttempt = runCatching {
            prepareWithFfmpeg(uri, durationMs, outputDir, onProgress)
        }
        if (ffmpegAttempt.isSuccess) return@withContext ffmpegAttempt.getOrThrow()

        Log.e(TAG, "Native remux and FFmpeg audio preparation both failed", ffmpegAttempt.exceptionOrNull())
        outputDir.listFiles()?.filter { it.name.startsWith("cloud_") }?.forEach { it.delete() }
        throw IllegalStateException(
            "تعذر تجهيز صوت الفيلم محليًا. [AUDIO-PREP-3]",
            ffmpegAttempt.exceptionOrNull() ?: nativeAttempt.exceptionOrNull(),
        )
    }

    private fun prepareWithNativeRemux(
        uri: Uri,
        durationMs: Long,
        outputDir: File,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<CloudAudioPart> {
        val extractor = MediaExtractor()
        val outputs = mutableListOf<CloudAudioPart>()
        var activeWriter: NativePartWriter? = null

        try {
            extractor.setDataSource(context, uri, null)
            val audioTrack = findAudioTrack(extractor)
            check(audioTrack >= 0) { "audio_track_missing" }

            val format = extractor.getTrackFormat(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            val container = nativeContainerForMime(mime)
                ?: error("native_audio_container_unsupported:$mime")

            extractor.selectTrack(audioTrack)
            val bufferSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(DEFAULT_NATIVE_BUFFER_BYTES)
            } else {
                DEFAULT_NATIVE_BUFFER_BYTES
            }
            val sampleBuffer = ByteBuffer.allocateDirect(bufferSize.coerceAtMost(MAX_NATIVE_BUFFER_BYTES))
            val info = MediaCodec.BufferInfo()
            var partIndex = 0
            var sampleCounter = 0

            while (true) {
                sampleBuffer.clear()
                val sampleSize = extractor.readSampleData(sampleBuffer, 0)
                if (sampleSize < 0) break
                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs < 0L) break
                check(sampleSize <= sampleBuffer.capacity()) { "native_audio_sample_too_large:$sampleSize" }

                val current = activeWriter
                val shouldSplit = current != null && current.sampleCount > 0 && (
                    current.payloadBytes + sampleSize > NATIVE_TARGET_PAYLOAD_BYTES ||
                        sampleTimeUs - current.originalStartUs >= NATIVE_MAX_PART_DURATION_US
                    )
                if (shouldSplit) {
                    outputs += finishNativeWriter(current)
                    activeWriter = null
                    continue
                }

                val writer = activeWriter ?: createNativeWriter(
                    outputDir = outputDir,
                    partIndex = ++partIndex,
                    sourceFormat = format,
                    container = container,
                    originalStartUs = sampleTimeUs,
                ).also { activeWriter = it }

                sampleBuffer.position(0)
                sampleBuffer.limit(sampleSize)
                info.set(
                    0,
                    sampleSize,
                    (sampleTimeUs - writer.originalStartUs).coerceAtLeast(0L),
                    extractor.sampleFlags,
                )
                writer.muxer.writeSampleData(writer.muxerTrack, sampleBuffer, info)
                writer.payloadBytes += sampleSize.toLong()
                writer.lastOriginalUs = sampleTimeUs
                writer.sampleCount += 1

                extractor.advance()
                sampleCounter += 1
                if (sampleCounter % 96 == 0) {
                    val ratio = (sampleTimeUs / 1000.0 / durationMs.toDouble()).coerceIn(0.0, 1.0)
                    onProgress((ratio * 100).toInt().coerceIn(1, 99), 100)
                }
            }

            activeWriter?.let {
                outputs += finishNativeWriter(it)
                activeWriter = null
            }

            check(outputs.isNotEmpty()) { "native_audio_empty" }
            outputs.forEach { part ->
                check(part.file.isFile && part.file.length() > 0L) { "native_audio_part_empty" }
                check(part.file.length() <= MAX_PART_BYTES) { "native_audio_part_too_large" }
            }
            onProgress(100, 100)
            Log.i(TAG, "Native audio remux prepared ${outputs.size} part(s), mime=$mime")
            return outputs
        } catch (error: Throwable) {
            activeWriter?.let { abortNativeWriter(it) }
            outputs.forEach { runCatching { it.file.delete() } }
            throw error
        } finally {
            extractor.release()
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) return index
        }
        return -1
    }

    private fun createNativeWriter(
        outputDir: File,
        partIndex: Int,
        sourceFormat: MediaFormat,
        container: NativeContainer,
        originalStartUs: Long,
    ): NativePartWriter {
        val file = File(
            outputDir,
            "cloud_native_${System.currentTimeMillis()}_${partIndex}.${container.extension}",
        )
        val muxer = MediaMuxer(file.absolutePath, container.outputFormat)
        return try {
            val muxerTrack = muxer.addTrack(sourceFormat)
            muxer.start()
            NativePartWriter(
                file = file,
                muxer = muxer,
                muxerTrack = muxerTrack,
                originalStartUs = originalStartUs,
                lastOriginalUs = originalStartUs,
            )
        } catch (error: Throwable) {
            runCatching { muxer.release() }
            file.delete()
            throw error
        }
    }

    private fun finishNativeWriter(writer: NativePartWriter): CloudAudioPart {
        try {
            writer.muxer.stop()
        } finally {
            writer.muxer.release()
        }
        val durationMs = ((writer.lastOriginalUs - writer.originalStartUs + NATIVE_SAMPLE_TAIL_US) / 1000L)
            .coerceAtLeast(1L)
        return CloudAudioPart(
            file = writer.file,
            offsetMs = (writer.originalStartUs / 1000L).coerceAtLeast(0L),
            durationMs = durationMs,
        )
    }

    private fun abortNativeWriter(writer: NativePartWriter) {
        if (writer.sampleCount > 0) runCatching { writer.muxer.stop() }
        runCatching { writer.muxer.release() }
        writer.file.delete()
    }

    private suspend fun prepareWithFfmpeg(
        uri: Uri,
        durationMs: Long,
        outputDir: File,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<CloudAudioPart> {
        val plan = planParts(durationMs)
        val safInput = FFmpegKitConfig.getSafParameterForRead(context, uri)
        val outputs = ArrayList<CloudAudioPart>(plan.size)

        try {
            plan.forEachIndexed { index, spec ->
                val overlapMs = if (index == 0) 0L else minOf(PART_OVERLAP_MS, spec.first)
                val actualOffset = spec.first - overlapMs
                val actualDuration = spec.second + overlapMs
                val output = File(outputDir, "cloud_ffmpeg_${System.currentTimeMillis()}_${index + 1}.ogg")

                transcodePart(
                    uri = uri,
                    safInput = safInput,
                    output = output,
                    offsetMs = actualOffset,
                    durationMs = actualDuration,
                )
                check(output.isFile && output.length() > 0L) { "تعذر تجهيز الصوت للسحابة." }
                check(output.length() <= MAX_PART_BYTES) {
                    "الصوت المضغوط أكبر من حد الرفع المجاني. جرّب الملف مرة أخرى بعد تحديث التطبيق."
                }
                outputs += CloudAudioPart(output, actualOffset, actualDuration)
                onProgress(index + 1, plan.size)
            }
            return outputs
        } catch (error: Throwable) {
            outputs.forEach { it.file.delete() }
            throw error
        }
    }

    private suspend fun transcodePart(
        uri: Uri,
        safInput: String,
        output: File,
        offsetMs: Long,
        durationMs: Long,
    ) {
        val directAttempt = runCatching {
            transcodeUsingFileDescriptor(uri, output, offsetMs, durationMs)
        }
        if (directAttempt.isSuccess) return

        Log.w(TAG, "Direct descriptor path failed; retrying FFmpegKit SAF", directAttempt.exceptionOrNull())
        output.delete()

        val safAttempt = runCatching {
            transcodeUsingInput(safInput, output, offsetMs, durationMs)
        }
        if (safAttempt.isSuccess) return

        Log.e(TAG, "Both direct-descriptor and SAF audio preparation failed", safAttempt.exceptionOrNull())
        output.delete()
        throw safAttempt.exceptionOrNull()
            ?: directAttempt.exceptionOrNull()
            ?: IllegalStateException("audio_prepare_failed")
    }

    private suspend fun transcodeUsingFileDescriptor(
        uri: Uri,
        output: File,
        offsetMs: Long,
        durationMs: Long,
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        val descriptor: ParcelFileDescriptor = try {
            context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("audio_input_descriptor_unavailable")
        } catch (error: Throwable) {
            continuation.resumeWithException(error)
            return@suspendCancellableCoroutine
        }

        val command = buildFfmpegCommand(
            input = "/proc/self/fd/${descriptor.fd}",
            output = output,
            offsetMs = offsetMs,
            durationMs = durationMs,
        )

        var sessionId: Long? = null
        val session = FFmpegKit.executeAsync(command) { completed ->
            runCatching { descriptor.close() }
            if (!continuation.isActive) return@executeAsync
            if (ReturnCode.isSuccess(completed.returnCode)) {
                continuation.resume(Unit)
            } else {
                val diagnostic = completed.allLogs
                    .takeLast(8)
                    .joinToString(" | ") { it.message.trim() }
                    .take(800)
                Log.e(TAG, "FFmpeg fd failed (${completed.returnCode}): $diagnostic")
                continuation.resumeWithException(
                    IllegalStateException("ffmpeg_fd_failed:${completed.returnCode}")
                )
            }
        }
        sessionId = session.sessionId

        continuation.invokeOnCancellation {
            sessionId?.let { FFmpegKit.cancel(it) }
            runCatching { descriptor.close() }
            output.delete()
        }
    }

    private suspend fun transcodeUsingInput(
        input: String,
        output: File,
        offsetMs: Long,
        durationMs: Long,
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        val command = buildFfmpegCommand(input, output, offsetMs, durationMs)
        var sessionId: Long? = null
        val session = FFmpegKit.executeAsync(command) { completed ->
            if (!continuation.isActive) return@executeAsync
            if (ReturnCode.isSuccess(completed.returnCode)) {
                continuation.resume(Unit)
            } else {
                val diagnostic = completed.allLogs
                    .takeLast(8)
                    .joinToString(" | ") { it.message.trim() }
                    .take(800)
                Log.e(TAG, "FFmpeg SAF failed (${completed.returnCode}): $diagnostic")
                continuation.resumeWithException(
                    IllegalStateException("ffmpeg_saf_failed:${completed.returnCode}")
                )
            }
        }
        sessionId = session.sessionId

        continuation.invokeOnCancellation {
            sessionId?.let { FFmpegKit.cancel(it) }
            output.delete()
        }
    }

    private fun buildFfmpegCommand(
        input: String,
        output: File,
        offsetMs: Long,
        durationMs: Long,
    ): String {
        val startSeconds = String.format(Locale.US, "%.3f", offsetMs / 1000.0)
        val durationSeconds = String.format(Locale.US, "%.3f", durationMs / 1000.0)
        return buildString {
            append("-hide_banner -loglevel error -y ")
            if (offsetMs > 0L) append("-ss $startSeconds ")
            append("-i ${quote(input)} -t $durationSeconds -vn -sn -dn -map 0:a:0 ")
            append("-ac 1 -ar 16000 -c:a libopus -b:a 24k -f ogg ")
            append(quote(output.absolutePath))
        }
    }

    private data class NativeContainer(
        val outputFormat: Int,
        val extension: String,
    )

    private data class NativePartWriter(
        val file: File,
        val muxer: MediaMuxer,
        val muxerTrack: Int,
        val originalStartUs: Long,
        var lastOriginalUs: Long,
        var payloadBytes: Long = 0L,
        var sampleCount: Int = 0,
    )

    companion object {
        private const val TAG = "CloudAudioExtractor"
        private const val HOUR_MS = 60L * 60_000L
        private const val MAX_MOVIE_DURATION_MS = 3L * HOUR_MS
        private const val MAX_PART_BYTES = 24L * 1024L * 1024L
        private const val PART_OVERLAP_MS = 2_500L
        private const val NATIVE_TARGET_PAYLOAD_BYTES = 20L * 1024L * 1024L
        private const val NATIVE_MAX_PART_DURATION_US = 60L * 60L * 1_000_000L
        private const val NATIVE_SAMPLE_TAIL_US = 40_000L
        private const val DEFAULT_NATIVE_BUFFER_BYTES = 1024 * 1024
        private const val MAX_NATIVE_BUFFER_BYTES = 4 * 1024 * 1024

        private fun nativeContainerForMime(mime: String): NativeContainer? = when (mime.lowercase()) {
            "audio/opus", "audio/vorbis" -> NativeContainer(
                MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM,
                "webm",
            )
            "audio/mp4a-latm", "audio/aac", "audio/3gpp", "audio/amr-wb",
            "audio/mpeg", "audio/ac3", "audio/eac3", "audio/ac4" -> NativeContainer(
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                "m4a",
            )
            else -> null
        }

        internal fun planParts(durationMs: Long): List<Pair<Long, Long>> {
            require(durationMs in 1..MAX_MOVIE_DURATION_MS)
            val partCount = ceil(durationMs.toDouble() / HOUR_MS.toDouble())
                .toInt()
                .coerceIn(1, 3)
            val baseDuration = durationMs / partCount
            val remainder = durationMs % partCount
            var offset = 0L
            return List(partCount) { index ->
                val length = baseDuration + if (index < remainder) 1L else 0L
                (offset to length).also { offset += length }
            }
        }

        private fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"
    }
}
