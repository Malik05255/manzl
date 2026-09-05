package com.manzl.movietranslator

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToInt

class MediaAudioTranscriber(private val context: Context) {
    suspend fun transcribe(
        uri: Uri,
        modelDir: File,
        onProgress: (Float) -> Unit,
    ): List<SubtitleCue> = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var decoderForCleanup: MediaCodec? = null
        val model = Model(modelDir.absolutePath)
        val recognizer = Recognizer(model, 16_000f).apply { setWords(true) }
        val cues = mutableListOf<SubtitleCue>()

        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("لم أجد مسارًا صوتيًا داخل الفيلم.")

            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("صيغة الصوت غير معروفة.")
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1L)
            } else 1L

            val decoder = MediaCodec.createDecoderByType(mime).also { decoderForCleanup = it }
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var outputSampleRate = inputFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 48_000)
            var outputChannels = inputFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler = StreamingPcmResampler(outputSampleRate, outputChannels, pcmEncoding)

            while (!outputDone) {
                currentCoroutineContext().ensureActive()

                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                            ?: error("تعذر قراءة مخزن الصوت.")
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime.coerceAtLeast(0L),
                                0,
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = decoder.outputFormat
                        outputSampleRate = outputFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, outputSampleRate)
                        outputChannels = outputFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, outputChannels)
                        pcmEncoding = outputFormat.getIntegerOrDefault(
                            MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT,
                        )
                        resampler = StreamingPcmResampler(outputSampleRate, outputChannels, pcmEncoding)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER,
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        if (info.size > 0) {
                            val outputBuffer = decoder.getOutputBuffer(outputIndex)
                                ?: error("تعذر فك الصوت.")
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            val decoded = ByteArray(info.size)
                            outputBuffer.get(decoded)
                            val pcm16k = resampler.convert(decoded)
                            if (pcm16k.isNotEmpty() && recognizer.acceptWaveForm(pcm16k, pcm16k.size)) {
                                parseResult(recognizer.result)?.let(cues::add)
                            }
                            onProgress(
                                (info.presentationTimeUs.toDouble() / durationUs.toDouble())
                                    .toFloat()
                                    .coerceIn(0f, 1f)
                            )
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            parseResult(recognizer.finalResult)?.let { finalCue ->
                if (cues.lastOrNull()?.sourceText != finalCue.sourceText || cues.lastOrNull()?.startMs != finalCue.startMs) {
                    cues += finalCue
                }
            }
            onProgress(1f)
            cues
        } finally {
            runCatching { decoderForCleanup?.stop() }
            runCatching { decoderForCleanup?.release() }
            runCatching { extractor.release() }
            runCatching { recognizer.close() }
            runCatching { model.close() }
        }
    }

    private fun parseResult(json: String): SubtitleCue? {
        val root = JSONObject(json)
        val text = root.optString("text").trim()
        if (text.isBlank()) return null
        val words = root.optJSONArray("result")
        if (words == null || words.length() == 0) return null
        val first = words.getJSONObject(0)
        val last = words.getJSONObject(words.length() - 1)
        val startMs = (first.optDouble("start", 0.0) * 1000.0).toLong()
        val endMs = (last.optDouble("end", first.optDouble("start", 0.0) + 1.0) * 1000.0).toLong()
        return SubtitleCue(startMs, endMs.coerceAtLeast(startMs + 350L), text)
    }
}

private fun MediaFormat.getIntegerOrDefault(key: String, fallback: Int): Int =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback

private class StreamingPcmResampler(
    private val sourceRate: Int,
    private val channels: Int,
    private val encoding: Int,
) {
    private var inputFramesSeen = 0L
    private var nextOutputFrameAt = 0.0
    private val sourceFramesPerOutput = sourceRate.toDouble() / 16_000.0

    fun convert(data: ByteArray): ByteArray {
        if (data.isEmpty() || sourceRate <= 0 || channels <= 0) return ByteArray(0)
        val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val frameBytes = bytesPerSample * channels
        val frameCount = data.size / frameBytes
        if (frameCount <= 0) return ByteArray(0)

        val chunkStart = inputFramesSeen.toDouble()
        val chunkEnd = chunkStart + frameCount
        val input = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val output = ByteArrayOutputStream((frameCount * 16_000.0 / sourceRate * 2.0).roundToInt().coerceAtLeast(32))

        if (nextOutputFrameAt < chunkStart) nextOutputFrameAt = chunkStart
        while (nextOutputFrameAt < chunkEnd) {
            val localFrame = floor(nextOutputFrameAt - chunkStart).toInt().coerceIn(0, frameCount - 1)
            var sum = 0.0
            for (channel in 0 until channels) {
                val offset = localFrame * frameBytes + channel * bytesPerSample
                val sample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                    input.getFloat(offset).toDouble().coerceIn(-1.0, 1.0)
                } else {
                    input.getShort(offset).toDouble() / Short.MAX_VALUE.toDouble()
                }
                sum += sample
            }
            val mono = (sum / channels).coerceIn(-1.0, 1.0)
            val pcm = (mono * Short.MAX_VALUE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output.write(pcm and 0xff)
            output.write((pcm ushr 8) and 0xff)
            nextOutputFrameAt += sourceFramesPerOutput
        }

        inputFramesSeen += frameCount
        return output.toByteArray()
    }
}
