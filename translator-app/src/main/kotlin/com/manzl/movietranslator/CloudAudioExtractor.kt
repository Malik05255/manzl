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

internal data class CloudAudioPart(
    val file: File,
    val offsetMs: Long,
    val durationMs: Long,
)

/**
 * Produces tiny speech-optimized Opus files for the cloud path.
 *
 * A movie up to two hours becomes one file. A movie between two and three hours becomes exactly two
 * files: a two-hour first part and the remaining tail. This keeps the Groq part under the free-tier
 * upload limit at 24 kbps while respecting the product requirement of at most two hidden parts.
 */
class CloudAudioExtractor(private val context: Context) {
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
                val output = File(outputDir, "cloud_${System.currentTimeMillis()}_${index + 1}.ogg")
                transcodePart(
                    input = safInput,
                    output = output,
                    offsetMs = spec.first,
                    durationMs = spec.second,
                )
                check(output.isFile && output.length() > 0L) { "تعذر تجهيز الصوت للسحابة." }
                check(output.length() <= MAX_PART_BYTES) {
                    "الصوت المضغوط أكبر من حد الرفع المجاني. جرّب الملف مرة أخرى بعد تحديث التطبيق."
                }
                outputs += CloudAudioPart(output, spec.first, spec.second)
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
            append("-ac 1 -ar 16000 -c:a libopus -b:a 24k -vbr off ")
            append("-application voip -compression_level 5 -f ogg ")
            append(quote(output.absolutePath))
        }

        var sessionId: Long? = null
        val session = FFmpegKit.executeAsync(command) { completed ->
            if (!continuation.isActive) return@executeAsync
            if (ReturnCode.isSuccess(completed.returnCode)) {
                continuation.resume(Unit)
            } else {
                continuation.resumeWithException(
                    IllegalStateException("تعذر ضغط صوت الفيلم للمسار السحابي.")
                )
            }
        }
        sessionId = session.sessionId
        continuation.invokeOnCancellation {
            sessionId?.let { FFmpegKit.cancel(it) }
            output.delete()
        }
    }

    companion object {
        private const val HOUR_MS = 60L * 60_000L
        private const val TWO_HOURS_MS = 2L * HOUR_MS
        private const val MAX_MOVIE_DURATION_MS = 3L * HOUR_MS
        private const val MAX_PART_BYTES = 24L * 1024L * 1024L

        internal fun planParts(durationMs: Long): List<Pair<Long, Long>> {
            require(durationMs in 1..MAX_MOVIE_DURATION_MS)
            return if (durationMs <= TWO_HOURS_MS) {
                listOf(0L to durationMs)
            } else {
                listOf(
                    0L to TWO_HOURS_MS,
                    TWO_HOURS_MS to (durationMs - TWO_HOURS_MS),
                )
            }
        }

        private fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"
    }
}
