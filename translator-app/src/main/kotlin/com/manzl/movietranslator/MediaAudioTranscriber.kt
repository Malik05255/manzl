package com.manzl.movietranslator

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.PowerManager
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToInt

data class SpeechWindow(
    val startMs: Long,
    val endMs: Long,
    val wavFile: File,
)

/**
 * Extracts actual speech from a movie soundtrack without trusting an ASR model to decide coverage.
 * Silero VAD is intentionally used instead of an amplitude gate/WebRTC because movie music and
 * effects were causing the reference clip to be treated as nearly continuous speech.
 */
class MediaAudioTranscriber(private val context: Context) {
    suspend fun extractSpeech(
        uri: Uri,
        onProgress: (Float) -> Unit,
    ): List<SpeechWindow> = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var decoderForCleanup: MediaCodec? = null
        var processorForCleanup: SileroSpeechProcessor? = null
        val windows = mutableListOf<SpeechWindow>()
        val thermalGuard = ThermalGuard(context)

        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = chooseBestAudioTrack(extractor)
                ?: error("لم أجد مسارًا صوتيًا داخل الفيلم.")
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
            val processor = SileroSpeechProcessor(context, context.cacheDir, windows)
                .also { processorForCleanup = it }
            var lastProgress = -1f

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
                                inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
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
                        outputSampleRate = outputFormat.getIntegerOrDefault(
                            MediaFormat.KEY_SAMPLE_RATE, outputSampleRate
                        )
                        outputChannels = outputFormat.getIntegerOrDefault(
                            MediaFormat.KEY_CHANNEL_COUNT, outputChannels
                        )
                        pcmEncoding = outputFormat.getIntegerOrDefault(
                            MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT
                        )
                        resampler = StreamingPcmResampler(
                            outputSampleRate, outputChannels, pcmEncoding
                        )
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
                            if (pcm16k.isNotEmpty()) processor.consume(pcm16k)

                            val progress = (info.presentationTimeUs.toDouble() / durationUs.toDouble())
                                .toFloat().coerceIn(0f, 1f)
                            if (progress - lastProgress >= 0.005f) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
                thermalGuard.yieldIfNeeded()
            }

            processor.finish()
            onProgress(1f)
            windows.sortedBy { it.startMs }
        } catch (error: Throwable) {
            windows.forEach { it.wavFile.delete() }
            throw error
        } finally {
            runCatching { processorForCleanup?.close() }
            runCatching { decoderForCleanup?.stop() }
            runCatching { decoderForCleanup?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun chooseBestAudioTrack(extractor: MediaExtractor): Int? {
        var bestIndex: Int? = null
        var bestScore = Int.MIN_VALUE
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (!mime.startsWith("audio/")) continue

            val language = format.getString(MediaFormat.KEY_LANGUAGE)?.lowercase().orEmpty()
            val channels = format.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var score = 0
            if (language == "tr" || language == "tur" || language.startsWith("tr-")) score += 100
            if (format.getIntegerOrDefault(MediaFormat.KEY_IS_DEFAULT, 0) == 1) score += 15
            if (language.isNotBlank() && language != "tr" && language != "tur" && !language.startsWith("tr-")) {
                score -= 25
            }
            if (channels >= 3) score += 8
            if (channels == 2) score += 4
            if (bestIndex == null || score > bestScore) {
                bestIndex = index
                bestScore = score
            }
        }
        return bestIndex
    }
}

private class SileroSpeechProcessor(
    context: Context,
    private val cacheDir: File,
    private val output: MutableList<SpeechWindow>,
) : AutoCloseable {
    private val vad = VadSilero(
        context = context,
        sampleRate = SampleRate.SAMPLE_RATE_16K,
        frameSize = FrameSize.FRAME_SIZE_512,
        mode = Mode.NORMAL,
        speechDurationMs = 64,
        silenceDurationMs = 320,
    )
    private val segment = ByteArrayOutputStream(512 * 1024)
    private var remainder = ByteArray(0)
    private var preRoll = ByteArray(0)
    private var mediaClockSamples = 0L
    private var segmentStartSample = 0L
    private var active = false
    private var sequence = 0

    fun consume(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        val source = if (remainder.isEmpty()) pcm else ByteArray(remainder.size + pcm.size).also {
            remainder.copyInto(it)
            pcm.copyInto(it, remainder.size)
        }

        var offset = 0
        while (offset + FRAME_BYTES <= source.size) {
            val frame = source.copyOfRange(offset, offset + FRAME_BYTES)
            consumeFrame(frame, vad.isSpeech(frame))
            offset += FRAME_BYTES
        }
        remainder = if (offset < source.size) source.copyOfRange(offset, source.size) else ByteArray(0)
    }

    fun finish() {
        if (remainder.isNotEmpty()) {
            val even = remainder.size and -2
            if (even > 0) {
                val tail = remainder.copyOf(even)
                if (active) segment.write(tail) else appendPreRoll(tail)
                mediaClockSamples += (tail.size / 2).toLong()
            }
            remainder = ByteArray(0)
        }
        if (active && segment.size() >= MIN_WINDOW_BYTES) flushWindow()
    }

    private fun consumeFrame(frame: ByteArray, speech: Boolean) {
        if (!active) {
            if (speech) {
                active = true
                val preSamples = (preRoll.size / 2).toLong()
                segmentStartSample = (mediaClockSamples - preSamples).coerceAtLeast(0L)
                if (preRoll.isNotEmpty()) segment.write(preRoll)
                segment.write(frame)
                preRoll = ByteArray(0)
            } else {
                appendPreRoll(frame)
            }
        } else {
            segment.write(frame)
            val segmentSamples = (segment.size() / 2).toLong()
            if (!speech || segmentSamples >= MAX_WINDOW_SAMPLES) {
                // Silero's silenceDurationMs already provides hangover, so false here means a real
                // pause rather than a one-frame gap. Keep windows long enough for Whisper context.
                flushWindow()
                if (!speech) appendPreRoll(frame)
            }
        }
        mediaClockSamples += FRAME_SAMPLES
    }

    private fun flushWindow() {
        val bytes = segment.toByteArray()
        if (bytes.size >= MIN_WINDOW_BYTES) {
            val startMs = samplesToMs(segmentStartSample)
            val endMs = startMs + samplesToMs((bytes.size / 2).toLong())
            val file = File(cacheDir, "speech_${System.currentTimeMillis()}_${sequence++}.wav")
            writePcm16MonoWav(file, bytes, 16_000)
            output += SpeechWindow(startMs, endMs, file)
        }
        segment.reset()
        active = false
    }

    private fun appendPreRoll(bytes: ByteArray) {
        val combined = if (preRoll.isEmpty()) bytes else ByteArray(preRoll.size + bytes.size).also {
            preRoll.copyInto(it)
            bytes.copyInto(it, preRoll.size)
        }
        preRoll = if (combined.size <= PRE_ROLL_BYTES) combined
        else combined.copyOfRange(combined.size - PRE_ROLL_BYTES, combined.size)
    }

    private fun samplesToMs(samples: Long): Long = samples * 1_000L / 16_000L

    override fun close() {
        runCatching { vad.close() }
    }

    private companion object {
        const val FRAME_SAMPLES = 512L
        const val FRAME_BYTES = 1_024
        const val PRE_ROLL_BYTES = 10_240 // 320 ms mono PCM16 @ 16 kHz
        const val MIN_WINDOW_BYTES = 5_120 // 160 ms
        const val MAX_WINDOW_SAMPLES = 24L * 16_000L
    }
}

private fun writePcm16MonoWav(file: File, pcm: ByteArray, sampleRate: Int) {
    file.parentFile?.mkdirs()
    FileOutputStream(file).use { out ->
        val dataSize = pcm.size
        val byteRate = sampleRate * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
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
    private var remainder = ByteArray(0)

    fun convert(data: ByteArray): ByteArray {
        if (data.isEmpty() || sourceRate <= 0 || channels <= 0) return ByteArray(0)
        val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val frameBytes = bytesPerSample * channels
        val source = if (remainder.isEmpty()) data else ByteArray(remainder.size + data.size).also {
            remainder.copyInto(it)
            data.copyInto(it, remainder.size)
        }
        val frameCount = source.size / frameBytes
        val usedBytes = frameCount * frameBytes
        remainder = if (usedBytes < source.size) source.copyOfRange(usedBytes, source.size) else ByteArray(0)
        if (frameCount <= 0) return ByteArray(0)

        val chunkStart = inputFramesSeen.toDouble()
        val chunkEnd = chunkStart + frameCount
        val input = ByteBuffer.wrap(source, 0, usedBytes).order(ByteOrder.LITTLE_ENDIAN)
        val output = ByteArrayOutputStream(
            (frameCount * 16_000.0 / sourceRate * 2.0).roundToInt().coerceAtLeast(32)
        )
        if (nextOutputFrameAt < chunkStart) nextOutputFrameAt = chunkStart
        while (nextOutputFrameAt < chunkEnd) {
            val localFrame = floor(nextOutputFrameAt - chunkStart).toInt().coerceIn(0, frameCount - 1)
            val mono = mixedMonoSample(input, localFrame, frameBytes, bytesPerSample)
            val pcm = (mono * Short.MAX_VALUE).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output.write(pcm and 0xff)
            output.write((pcm ushr 8) and 0xff)
            nextOutputFrameAt += sourceFramesPerOutput
        }
        inputFramesSeen += frameCount
        return output.toByteArray()
    }

    private fun mixedMonoSample(
        input: ByteBuffer,
        frame: Int,
        frameBytes: Int,
        bytesPerSample: Int,
    ): Double {
        fun sample(channel: Int): Double {
            val offset = frame * frameBytes + channel * bytesPerSample
            return if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                input.getFloat(offset).toDouble().coerceIn(-1.0, 1.0)
            } else {
                input.getShort(offset).toDouble() / Short.MAX_VALUE.toDouble()
            }
        }
        if (channels == 1) return sample(0)
        if (channels == 2) return ((sample(0) + sample(1)) * 0.5).coerceIn(-1.0, 1.0)

        val center = sample(2)
        var others = 0.0
        var count = 0
        for (channel in 0 until channels) {
            if (channel == 2) continue
            others += sample(channel)
            count++
        }
        val ambient = if (count > 0) others / count else 0.0
        return (center * 0.78 + ambient * 0.22).coerceIn(-1.0, 1.0)
    }
}

private class ThermalGuard(context: Context) {
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private var lastCheckMs = 0L

    suspend fun yieldIfNeeded() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastCheckMs < 2_000L) return
        lastCheckMs = now
        when {
            powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL -> delay(750L)
            powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> delay(180L)
        }
    }
}
