package com.vibe.app.feature.translator

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sqrt

/** A small temporary WAV chunk. It is deleted immediately after transcription. */
data class AudioChunk(
    val file: File,
    val startMs: Long,
    val endMs: Long,
    val mostlySilent: Boolean,
)

/**
 * Decodes only the movie's audio track. Video frames are never decoded or copied.
 *
 * Output is mono PCM WAV in 30-second chunks, which keeps peak RAM/storage bounded. For 5.1
 * sources the center channel is weighted heavily because movie dialogue is commonly mixed there.
 */
class AudioChunkExtractor(
    private val context: Context,
    private val source: Uri,
    private val tempDir: File,
) {
    suspend fun extract(
        onChunk: suspend (AudioChunk) -> Unit,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        tempDir.mkdirs()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(context, source, null)
            val audioTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("لم يتم العثور على مسار صوت داخل الفيلم")

            val inputFormat = extractor.getTrackFormat(audioTrack)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("صيغة الصوت غير معروفة")
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                -1L
            }

            // Ask decoders for 16-bit PCM when supported. Android audio decoders normally expose it.
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            extractor.selectTrack(audioTrack)

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var outputFormat: MediaFormat? = null
            var writer: MonoWavChunkWriter? = null

            while (!outputDone) {
                coroutineContext.ensureActive()

                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: error("تعذر قراءة مخزن الصوت")
                        inputBuffer.clear()
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                size,
                                extractor.sampleTime.coerceAtLeast(0L),
                                extractor.sampleFlags,
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = codec.outputFormat
                        val sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        writer = MonoWavChunkWriter(tempDir, sampleRate)
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (outputIndex >= 0) {
                        var completed = emptyList<AudioChunk>()
                        if (info.size > 0) {
                            val currentFormat = outputFormat ?: codec.outputFormat.also { outputFormat = it }
                            val sampleRate = currentFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val channels = currentFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            val pcmEncoding = if (currentFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                currentFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else {
                                AudioFormat.ENCODING_PCM_16BIT
                            }
                            val chunkWriter = writer ?: MonoWavChunkWriter(tempDir, sampleRate).also {
                                writer = it
                            }

                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                                ?: error("تعذر قراءة الصوت بعد فك الضغط")
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            outputBuffer.get(bytes)
                            val mono = decodeToMono(bytes, channels, pcmEncoding)
                            completed = chunkWriter.append(mono)
                        }

                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)

                        completed.forEach { chunk ->
                            onChunk(chunk)
                            if (durationUs > 0L) {
                                onProgress((chunk.endMs * 1000f / durationUs).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }

            writer?.finish()?.let { chunk ->
                onChunk(chunk)
                onProgress(1f)
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun decodeToMono(bytes: ByteArray, channels: Int, encoding: Int): ShortArray {
        require(channels > 0) { "Invalid channel count: $channels" }
        return when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> pcm16ToMono(bytes, channels)
            AudioFormat.ENCODING_PCM_FLOAT -> pcmFloatToMono(bytes, channels)
            AudioFormat.ENCODING_PCM_8BIT -> pcm8ToMono(bytes, channels)
            else -> error("صيغة PCM غير مدعومة: $encoding")
        }
    }

    private fun pcm16ToMono(bytes: ByteArray, channels: Int): ShortArray {
        val source = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frameCount = source.remaining() / channels
        val output = ShortArray(frameCount)
        val frame = ShortArray(channels)
        repeat(frameCount) { frameIndex ->
            repeat(channels) { channel -> frame[channel] = source.get() }
            output[frameIndex] = mixFrame(frame, channels)
        }
        return output
    }

    private fun pcmFloatToMono(bytes: ByteArray, channels: Int): ShortArray {
        val source = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val frameCount = source.remaining() / channels
        val output = ShortArray(frameCount)
        val frame = ShortArray(channels)
        repeat(frameCount) { frameIndex ->
            repeat(channels) { channel ->
                frame[channel] = (source.get().coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            }
            output[frameIndex] = mixFrame(frame, channels)
        }
        return output
    }

    private fun pcm8ToMono(bytes: ByteArray, channels: Int): ShortArray {
        val frameCount = bytes.size / channels
        val output = ShortArray(frameCount)
        val frame = ShortArray(channels)
        var cursor = 0
        repeat(frameCount) { frameIndex ->
            repeat(channels) { channel ->
                frame[channel] = (((bytes[cursor++].toInt() and 0xff) - 128) shl 8).toShort()
            }
            output[frameIndex] = mixFrame(frame, channels)
        }
        return output
    }

    private fun mixFrame(frame: ShortArray, channels: Int): Short {
        if (channels == 1) return frame[0]
        if (channels >= 3) {
            // Standard multichannel order begins FL, FR, FC. Dialogue is usually strong in FC.
            val value = frame[2] * 0.60 + frame[0] * 0.20 + frame[1] * 0.20
            return value.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return ((frame[0].toInt() + frame[1].toInt()) / 2).toShort()
    }

    private class MonoWavChunkWriter(
        private val directory: File,
        private val sampleRate: Int,
    ) {
        private val maxSamples = sampleRate * CHUNK_SECONDS
        private var index = 0
        private var totalSamples = 0L
        private var chunkStartSample = 0L
        private var chunkSamples = 0
        private var energy = 0.0
        private var file: File? = null
        private var output: RandomAccessFile? = null

        fun append(samples: ShortArray): List<AudioChunk> {
            val completed = mutableListOf<AudioChunk>()
            var offset = 0
            while (offset < samples.size) {
                ensureOpen()
                val count = min(samples.size - offset, maxSamples - chunkSamples)
                val bytes = ByteArray(count * 2)
                var byteOffset = 0
                for (i in offset until offset + count) {
                    val sample = samples[i].toInt()
                    bytes[byteOffset++] = (sample and 0xff).toByte()
                    bytes[byteOffset++] = ((sample ushr 8) and 0xff).toByte()
                    val normalized = sample / 32768.0
                    energy += normalized * normalized
                }
                output!!.write(bytes)
                chunkSamples += count
                totalSamples += count
                offset += count

                if (chunkSamples >= maxSamples) {
                    completed += finalizeChunk()
                }
            }
            return completed
        }

        fun finish(): AudioChunk? = if (chunkSamples > 0) finalizeChunk() else null

        private fun ensureOpen() {
            if (output != null) return
            chunkStartSample = totalSamples
            file = File(directory, "audio_%05d.wav".format(index++))
            output = RandomAccessFile(file, "rw").apply {
                setLength(0L)
                write(ByteArray(WAV_HEADER_SIZE))
            }
        }

        private fun finalizeChunk(): AudioChunk {
            val currentFile = requireNotNull(file)
            val currentOutput = requireNotNull(output)
            writeWavHeader(currentOutput, sampleRate, chunkSamples)
            currentOutput.close()

            val rms = sqrt(energy / chunkSamples.coerceAtLeast(1))
            val db = 20.0 * log10(rms.coerceAtLeast(1e-9))
            val result = AudioChunk(
                file = currentFile,
                startMs = chunkStartSample * 1000L / sampleRate,
                endMs = (chunkStartSample + chunkSamples) * 1000L / sampleRate,
                mostlySilent = db < SILENCE_DB,
            )

            file = null
            output = null
            chunkSamples = 0
            energy = 0.0
            return result
        }

        private fun writeWavHeader(file: RandomAccessFile, sampleRate: Int, sampleCount: Int) {
            val dataSize = sampleCount * 2
            file.seek(0L)
            file.writeBytes("RIFF")
            writeIntLE(file, 36 + dataSize)
            file.writeBytes("WAVE")
            file.writeBytes("fmt ")
            writeIntLE(file, 16)
            writeShortLE(file, 1)
            writeShortLE(file, 1)
            writeIntLE(file, sampleRate)
            writeIntLE(file, sampleRate * 2)
            writeShortLE(file, 2)
            writeShortLE(file, 16)
            file.writeBytes("data")
            writeIntLE(file, dataSize)
        }

        private fun writeIntLE(file: RandomAccessFile, value: Int) {
            file.write(value and 0xff)
            file.write((value ushr 8) and 0xff)
            file.write((value ushr 16) and 0xff)
            file.write((value ushr 24) and 0xff)
        }

        private fun writeShortLE(file: RandomAccessFile, value: Int) {
            file.write(value and 0xff)
            file.write((value ushr 8) and 0xff)
        }
    }

    companion object {
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val CHUNK_SECONDS = 30
        private const val WAV_HEADER_SIZE = 44
        private const val SILENCE_DB = -48.0
    }
}