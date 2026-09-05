package com.manzl.movietranslator

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.PowerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
        val recognizer = Recognizer(model, TARGET_SAMPLE_RATE.toFloat()).apply { setWords(true) }
        val cues = mutableListOf<SubtitleCue>()
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
            val speechProcessor = SilenceSkippingSpeechProcessor(recognizer, cues)
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
                            if (pcm16k.isNotEmpty()) {
                                speechProcessor.consume(pcm16k)
                            }

                            val progress = (info.presentationTimeUs.toDouble() / durationUs.toDouble())
                                .toFloat()
                                .coerceIn(0f, 1f)
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

            speechProcessor.finish()
            onProgress(1f)
            cues.sortedBy { it.startMs }
        } finally {
            runCatching { decoderForCleanup?.stop() }
            runCatching { decoderForCleanup?.release() }
            runCatching { extractor.release() }
            runCatching { recognizer.close() }
            runCatching { model.close() }
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
            if (channels >= 3) score += 8
            if (channels == 2) score += 4
            if (bestIndex == null || score > bestScore) {
                bestIndex = index
                bestScore = score
            }
        }
        return bestIndex
    }

    private companion object {
        const val TARGET_SAMPLE_RATE = 16_000
    }
}

private class SilenceSkippingSpeechProcessor(
    private val recognizer: Recognizer,
    private val output: MutableList<SubtitleCue>,
) {
    private val segment = ByteArrayOutputStream(256 * 1024)
    private val feedBuffer = ByteArray(FEED_CHUNK_BYTES)
    private var segmentStartSample = 0L
    private var mediaClockSamples = 0L
    private var trailingQuietSamples = 0L
    private var active = false

    fun consume(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        val sampleCount = (pcm.size / 2).toLong()
        val speechLike = hasAudibleSignal(pcm)

        if (!active) {
            if (speechLike) {
                active = true
                segmentStartSample = mediaClockSamples
                trailingQuietSamples = 0L
                segment.write(pcm)
            }
        } else {
            segment.write(pcm)
            if (speechLike) {
                trailingQuietSamples = 0L
            } else {
                trailingQuietSamples += sampleCount
            }

            val segmentSamples = (segment.size() / 2).toLong()
            if (trailingQuietSamples >= QUIET_END_SAMPLES || segmentSamples >= MAX_SEGMENT_SAMPLES) {
                processSegment()
            }
        }

        mediaClockSamples += sampleCount
    }

    fun finish() {
        if (active && segment.size() > 0) processSegment()
    }

    private fun processSegment() {
        val bytes = segment.toByteArray()
        if (bytes.isNotEmpty()) {
            val baseMs = samplesToMs(segmentStartSample)
            recognizer.reset()
            var offset = 0
            while (offset < bytes.size) {
                val count = minOf(feedBuffer.size, bytes.size - offset)
                bytes.copyInto(feedBuffer, destinationOffset = 0, startIndex = offset, endIndex = offset + count)
                if (recognizer.acceptWaveForm(feedBuffer, count)) {
                    parseResult(recognizer.result, baseMs)?.let(::addIfUseful)
                }
                offset += count
            }
            parseResult(recognizer.finalResult, baseMs)?.let(::addIfUseful)
        }
        segment.reset()
        active = false
        trailingQuietSamples = 0L
    }

    private fun addIfUseful(cue: SubtitleCue) {
        if (cue.sourceText.isBlank()) return
        val previous = output.lastOrNull()
        if (previous?.sourceText == cue.sourceText && previous.startMs == cue.startMs) return
        output += cue
    }

    private fun hasAudibleSignal(data: ByteArray): Boolean {
        if (data.size < 2) return false
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0L
        var samples = 0
        var index = 0
        while (index + 1 < data.size) {
            val value = kotlin.math.abs(buffer.getShort(index).toInt())
            sum += value
            samples++
            index += 16
        }
        if (samples == 0) return false
        return (sum / samples) >= MIN_MEAN_ABS_SIGNAL
    }

    private fun parseResult(json: String, baseMs: Long): SubtitleCue? {
        val root = JSONObject(json)
        val text = root.optString("text").trim()
        if (text.isBlank()) return null
        val words = root.optJSONArray("result")
        if (words == null || words.length() == 0) return null

        val first = words.getJSONObject(0)
        val last = words.getJSONObject(words.length() - 1)
        val startMs = baseMs + (first.optDouble("start", 0.0) * 1000.0).toLong()
        val endMs = baseMs + (last.optDouble("end", first.optDouble("start", 0.0) + 1.0) * 1000.0).toLong()
        var confidenceSum = 0.0
        for (i in 0 until words.length()) {
            confidenceSum += words.getJSONObject(i).optDouble("conf", 1.0)
        }
        val confidence = (confidenceSum / words.length()).toFloat().coerceIn(0f, 1f)
        return SubtitleCue(
            startMs = startMs,
            endMs = endMs.coerceAtLeast(startMs + 350L),
            sourceText = text,
            confidence = confidence,
        )
    }

    private fun samplesToMs(samples: Long): Long = samples * 1_000L / TARGET_RATE

    private companion object {
        const val TARGET_RATE = 16_000L
        const val MIN_MEAN_ABS_SIGNAL = 95L
        const val QUIET_END_SAMPLES = 10_400L // 650 ms at 16 kHz
        const val MAX_SEGMENT_SAMPLES = 384_000L // 24 seconds at 16 kHz
        const val FEED_CHUNK_BYTES = 3_200
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
            remainder.copyInto(it, 0)
            data.copyInto(it, remainder.size)
        }
        val frameCount = source.size / frameBytes
        val usedBytes = frameCount * frameBytes
        remainder = if (usedBytes < source.size) source.copyOfRange(usedBytes, source.size) else ByteArray(0)
        if (frameCount <= 0) return ByteArray(0)

        val chunkStart = inputFramesSeen.toDouble()
        val chunkEnd = chunkStart + frameCount
        val input = ByteBuffer.wrap(source, 0, usedBytes).order(ByteOrder.LITTLE_ENDIAN)
        val output = ByteArrayOutputStream((frameCount * 16_000.0 / sourceRate * 2.0).roundToInt().coerceAtLeast(32))

        if (nextOutputFrameAt < chunkStart) nextOutputFrameAt = chunkStart
        while (nextOutputFrameAt < chunkEnd) {
            val localFrame = floor(nextOutputFrameAt - chunkStart).toInt().coerceIn(0, frameCount - 1)
            val mono = mixedMonoSample(input, localFrame, frameBytes, bytesPerSample)
            val pcm = (mono * Short.MAX_VALUE)
                .roundToInt()
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

        // Most 5.1 movie mixes place dialogue primarily in the center channel (index 2).
        // Keep the rest of the mix at a lower weight so unusual channel layouts still work.
        val center = sample(2)
        var others = 0.0
        var count = 0
        for (channel in 0 until channels) {
            if (channel == 2) continue
            others += sample(channel)
            count++
        }
        val ambient = if (count > 0) others / count else 0.0
        return (center * 0.62 + ambient * 0.38).coerceIn(-1.0, 1.0)
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
