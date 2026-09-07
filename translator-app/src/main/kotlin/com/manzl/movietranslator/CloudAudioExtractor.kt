package com.manzl.movietranslator

import android.content.Context
import android.net.Uri
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

                // Keep a tiny overlap after the first part so a sentence crossing the boundary is not lost.
                // Duplicate lines are removed after ASR using their absolute timestamp and normalized text.
                val overlapMs = if (index == 0) 0L else minOf(PART_OVERLAP_MS, spec.first)
                val actualOffset = spec.first - overlapMs
                val actualDuration = spec.second + overlapMs

                val output = File(outputDir, "cloud_${System.currentTimeMillis()}_${index + 1}.ogg")
                transcodePart(safInput, output, actualOffset, actualDuration)
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
            throw error
        }
    }

    private suspend fun transcodePart(
        input: String,
        output: File,
        offsetMs: Long,
        durationMs: Long,
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        val startSeconds = String.format(Locale.US, "%.3f", offsetMs / 1000.0)
        val durationSeconds = String.format(Locale.US, "%.3f", durationMs / 1000.0)
        val command = buildString {
            append("-hide_banner -loglevel error -y ")
            if (offsetMs > 0L) append("-ss $startSeconds ")
            append("-i $input -t $durationSeconds -vn -map 0:a:0 ")
            // FFmpeg 8.x no longer supports the legacy runtime -ac option in this pipeline.
            // Force mono + 16 kHz through aformat instead so FFmpegKit 8.1.7 can build the audio graph.
            append("-af \"aformat=sample_rates=16000:channel_layouts=mono\" ")
            append("-c:a libopus -b:a 24k -vbr off ")
            append("-application voip -compression_level 5 -f ogg ")
            append(quote(output.absolutePath))
        }

        var sessionId: Long? = null
        val session = FFmpegKit.executeAsync(command) { completed ->
            if (!continuation.isActive) return@executeAsync
            if (ReturnCode.isSuccess(completed.returnCode)) continuation.resume(Unit)
            else continuation.resumeWithException(IllegalStateException("تعذر ضغط صوت الفيلم للمسار السحابي."))
        }
        sessionId = session.sessionId
        continuation.invokeOnCancellation {
            sessionId?.let { FFmpegKit.cancel(it) }
            output.delete()
        }
    }

    companion object {
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
