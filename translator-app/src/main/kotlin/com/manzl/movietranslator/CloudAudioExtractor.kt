package com.manzl.movietranslator

import android.content.Context
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
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil

internal data class CloudAudioPart(
    val file: File,
    val offsetMs: Long,
    val durationMs: Long,
)

/** Produces speech-optimized Opus for the cloud path without ever uploading the video. */
internal class CloudAudioExtractor(private val context: Context) {
    suspend fun prepare(
        uri: Uri,
        durationMs: Long,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<CloudAudioPart> = withContext(Dispatchers.IO) {
        require(durationMs in 1..MAX_MOVIE_DURATION_MS) {
            "يدعم المسار السحابي أفلامًا حتى 3 ساعات حاليًا."
        }

        val plan = planParts(durationMs)
        val outputDir = File(context.cacheDir, "cloud_audio").apply { mkdirs() }
        outputDir.listFiles()?.filter { it.name.startsWith("cloud_") }?.forEach { it.delete() }

        val safInput = FFmpegKitConfig.getSafParameterForRead(context, uri)
        val outputs = ArrayList<CloudAudioPart>(plan.size)
        try {
            plan.forEachIndexed { index, spec ->
                coroutineContext.ensureActive()

                val overlapMs = if (index == 0) 0L else minOf(PART_OVERLAP_MS, spec.first)
                val actualOffset = spec.first - overlapMs
                val actualDuration = spec.second + overlapMs

                val output = File(outputDir, "cloud_${System.currentTimeMillis()}_${index + 1}.ogg")
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
            outputs
        } catch (error: Throwable) {
            outputs.forEach { it.file.delete() }
            throw IllegalStateException(
                "تعذر تجهيز صوت الفيلم محليًا. [AUDIO-PREP-2]",
                error,
            )
        }
    }

    /**
     * Some vendor Android builds expose document-provider movies in a way that FFmpegKit's SAF
     * protocol cannot keep open for a long-running transcode. We therefore try the already-open
     * Android descriptor first (/proc/self/fd/N), then retry the official SAF URL automatically.
     */
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
        private const val PART_OVERLAP_MS = 2_500L

        /**
         * Splits the movie into balanced parts with a target maximum of one hour.
         * 1:45 => ~52:30 + 52:30, 2:00 => 60 + 60, 3:00 => 60 + 60 + 60.
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
