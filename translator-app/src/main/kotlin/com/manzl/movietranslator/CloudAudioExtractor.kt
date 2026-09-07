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
import kotlinx.coroutines.ensureActive
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
    val mimeType: String = "audio/ogg",
    val extension: String = "ogg",
)

/**
 * Prepares uploadable audio without ever uploading or copying the video itself.
 *
 * The first path is a native Android AAC passthrough: it copies compressed AAC samples directly
 * into small M4A parts, so long MP4 movies do not depend on a multi-hour FFmpeg transcode. This is
 * both faster and more reliable on vendor Android builds. Non-AAC sources fall back to FFmpeg.
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

        val nativeResult = runCatching {
            prepareNativeAacParts(uri, durationMs)
        }.onFailure { error ->
            Log.w(TAG, "Native AAC passthrough unavailable; falling back to FFmpeg", error)
        }.getOrNull()

        if (!nativeResult.isNullOrEmpty()) {
            onProgress(1, 1)
            return@withContext nativeResult
        }

        val plan = planParts(durationMs)
        val outputDir = File(context.cacheDir, "cloud_audio").apply { mkdirs() }
        outputDir.listFiles()?.filter {
            it.name.startsWith("cloud_") || it.name.startsWith("fast_")
        }?.forEach { it.delete() }

        val safInput = FFmpegKitConfig.getSafParameterForRead(context, uri)
        val outputs = ArrayList<CloudAudioPart>(plan.size)
        try {
            plan.forEachIndexed { index, spec ->
                coroutineContext.ensureActive()

                val overlapMs = if (index == 0) 0L else minOf(PART_OVERLAP_MS, spec.first)
                val actualOffset = spec.first - overlapMs
                val actualDuration = spec.second + overlapMs

                val output = File(outputDir, "cloud_${System.currentTimeMillis()}_${index + 1}.ogg")
                transcodePart(uri, safInput, output, actualOffset, actualDuration)
                check(output.isFile && output.length() > 0L) { "تعذر تجهيز الصوت للسحابة." }
                check(output.length() <= MAX_PART_BYTES) {
                    "الصوت المضغوط أكبر من حد الرفع المجاني. جرّب الملف مرة أخرى بعد تحديث التطبيق."
                }
                outputs += CloudAudioPart(output, actualOffset, actualDuration)
                onProgress(index + 1, plan.size)
            }
            outputs
        } catch (error: Throwable) {
            outputs.forEach { it.file.delete() }
            throw IllegalStateException(
                "تعذر تجهيز صوت الفيلم محليًا بعد المحاولة التلقائية. [AUDIO-PREP-2]",
                error,
            )
        }
    }

    /**
     * Fast path for the common MP4/AAC case. Samples are copied as-is; there is no decoding or
     * re-encoding. Files are split by actual byte count, keeping every upload safely below the
     * provider's 24 MB limit regardless of the movie's original AAC bitrate.
     */
    private fun prepareNativeAacParts(uri: Uri, movieDurationMs: Long): List<CloudAudioPart> {
        val extractor = MediaExtractor()
        val outputDir = File(context.cacheDir, "cloud_audio").apply { mkdirs() }
        val outputs = mutableListOf<CloudAudioPart>()

        var muxer: MediaMuxer? = null
        var muxerTrack = -1
        var currentFile: File? = null
        var currentStartUs = -1L
        var currentLastUs = -1L
        var currentBytes = 0L
        var partIndex = 0

        fun closeCurrent(nextStartUs: Long? = null) {
            val active = muxer ?: return
            runCatching { active.stop() }
            runCatching { active.release() }
            muxer = null

            val file = currentFile
            if (file != null && file.isFile && file.length() > 0L && currentStartUs >= 0L) {
                check(file.length() <= MAX_PART_BYTES) { "native_audio_part_too_large" }
                val offsetMs = currentStartUs / 1_000L
                val inferredEndUs = nextStartUs
                    ?: if (currentLastUs >= currentStartUs) currentLastUs + 100_000L else currentStartUs + 100_000L
                val rawDurationMs = ((inferredEndUs - currentStartUs).coerceAtLeast(1_000L)) / 1_000L
                val remainingMs = (movieDurationMs - offsetMs).coerceAtLeast(1L)
                outputs += CloudAudioPart(
                    file = file,
                    offsetMs = offsetMs,
                    durationMs = rawDurationMs.coerceAtMost(remainingMs),
                    mimeType = "audio/mp4",
                    extension = "m4a",
                )
            } else {
                file?.delete()
            }

            currentFile = null
            currentStartUs = -1L
            currentLastUs = -1L
            currentBytes = 0L
            muxerTrack = -1
        }

        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return emptyList()

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime != MediaFormat.MIMETYPE_AUDIO_AAC) return emptyList()

            extractor.selectTrack(trackIndex)
            val maxInput = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                512 * 1024
            }.coerceIn(64 * 1024, 2 * 1024 * 1024)
            val buffer = ByteBuffer.allocateDirect(maxInput)
            val info = MediaCodec.BufferInfo()

            fun openPart(startUs: Long) {
                partIndex += 1
                check(partIndex <= MAX_NATIVE_PARTS) { "native_audio_too_many_parts" }
                val file = File(outputDir, "fast_${System.currentTimeMillis()}_$partIndex.m4a")
                val active = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val track = active.addTrack(format)
                active.start()
                muxer = active
                muxerTrack = track
                currentFile = file
                currentStartUs = startUs
                currentLastUs = startUs
                currentBytes = 0L
            }

            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                val sampleTimeUs = extractor.sampleTime
                if (size < 0 || sampleTimeUs < 0L) break

                if (muxer == null) openPart(sampleTimeUs)
                if (currentBytes > 0L && currentBytes + size > NATIVE_TARGET_PART_BYTES) {
                    closeCurrent(sampleTimeUs)
                    openPart(sampleTimeUs)
                }

                info.offset = 0
                info.size = size
                info.presentationTimeUs = (sampleTimeUs - currentStartUs).coerceAtLeast(0L)
                info.flags = extractor.sampleFlags
                muxer?.writeSampleData(muxerTrack, buffer, info)
                currentLastUs = sampleTimeUs
                currentBytes += size.toLong()
                extractor.advance()
            }

            closeCurrent()
            return outputs.takeIf { it.isNotEmpty() } ?: emptyList()
        } catch (error: Throwable) {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            outputs.forEach { runCatching { it.file.delete() } }
            currentFile?.let { runCatching { it.delete() } }
            throw error
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * FFmpeg fallback. We first expose the already-open Android file descriptor directly through
     * /proc/self/fd, avoiding SAF protocol quirks seen on some vendor ROMs. If that fails, the
     * official FFmpegKit SAF URL is tried as a second path.
     */
    private suspend fun transcodePart(
        uri: Uri,
        safInput: String,
        output: File,
        offsetMs: Long,
        durationMs: Long,
    ) {
        val direct = runCatching {
            transcodeUsingFileDescriptor(uri, output, offsetMs, durationMs)
        }
        if (direct.isSuccess) return

        Log.w(TAG, "Direct descriptor FFmpeg path failed; retrying SAF", direct.exceptionOrNull())
        output.delete()
        transcodeUsingInput(safInput, output, offsetMs, durationMs)
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

        val input = "/proc/self/fd/${descriptor.fd}"
        val command = buildFfmpegCommand(input, output, offsetMs, durationMs)
        var sessionId: Long? = null
        val session = FFmpegKit.executeAsync(command) { completed ->
            runCatching { descriptor.close() }
            if (!continuation.isActive) return@executeAsync
            if (ReturnCode.isSuccess(completed.returnCode)) continuation.resume(Unit)
            else continuation.resumeWithException(
                IllegalStateException("ffmpeg_fd_failed:${completed.returnCode}")
            )
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
            if (ReturnCode.isSuccess(completed.returnCode)) continuation.resume(Unit)
            else continuation.resumeWithException(
                IllegalStateException("ffmpeg_saf_failed:${completed.returnCode}")
            )
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
            // -ac/-ar are normal FFmpeg output options and are more portable than relying on a
            // vendor-specific filter graph. FFmpegKit's audio artifact includes libopus.
            append("-ac 1 -ar 16000 -c:a libopus -b:a 24k -vbr off ")
            append("-application voip -compression_level 5 -f ogg ")
            append(quote(output.absolutePath))
        }
    }

    companion object {
        private const val TAG = "CloudAudioExtractor"
        private const val HOUR_MS = 60L * 60_000L
        private const val MAX_MOVIE_DURATION_MS = 3L * HOUR_MS
        private const val MAX_PART_BYTES = 24L * 1024L * 1024L
        private const val NATIVE_TARGET_PART_BYTES = 20L * 1024L * 1024L
        private const val MAX_NATIVE_PARTS = 24
        private const val PART_OVERLAP_MS = 2_500L

        /**
         * FFmpeg fallback plan. Native AAC passthrough may create more smaller parts based on actual
         * source byte size so it never exceeds the cloud upload limit.
         */
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
