package com.manzl.movietranslator

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/** Fast path: copy the existing compressed audio track into a small M4A container without decoding. */
class CloudAudioExtractor(private val context: Context) {
    suspend fun extract(uri: Uri): File = withContext(Dispatchers.IO) {
        val outputDir = File(context.cacheDir, "cloud_audio").apply { mkdirs() }
        val output = File(outputDir, "audio_${System.currentTimeMillis()}.m4a")
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("لم أجد مسارًا صوتيًا داخل الفيديو.")

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            require(mime in SUPPORTED_MUX_MIMES) {
                "صيغة الصوت $mime غير مدعومة في المسار السريع بعد."
            }
            extractor.selectTrack(trackIndex)

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxTrack = muxer.addTrack(format)
            muxer.start()

            val maxInput = format.getIntegerOrDefault(MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024)
                .coerceIn(64 * 1024, 2 * 1024 * 1024)
            val buffer = ByteBuffer.allocateDirect(maxInput)
            val info = MediaCodec.BufferInfo()

            while (true) {
                coroutineContext.ensureActive()
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(muxTrack, buffer, info)
                extractor.advance()
            }
            check(output.isFile && output.length() > 0L) { "تعذر استخراج الصوت من الفيديو." }
            check(output.length() <= MAX_CLOUD_AUDIO_BYTES) {
                "حجم الصوت المستخرج أكبر من الحد السحابي الحالي (${output.length() / 1024 / 1024}MB)."
            }
            output
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    companion object {
        private const val MAX_CLOUD_AUDIO_BYTES = 25L * 1024L * 1024L
        private val SUPPORTED_MUX_MIMES = setOf(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            MediaFormat.MIMETYPE_AUDIO_AMR_NB,
            MediaFormat.MIMETYPE_AUDIO_AMR_WB,
        )
    }
}
