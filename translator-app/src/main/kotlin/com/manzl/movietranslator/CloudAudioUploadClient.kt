package com.manzl.movietranslator

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.UUID

/**
 * Upload-only transport for prepared audio chunks.
 *
 * Native audio remuxing can create more than three chunks because the source audio bitrate is
 * preserved. Uploads are therefore bounded by count and by a small concurrency limit instead of
 * assuming one-hour Opus files. The real filename extension and MIME type are sent to the backend
 * so Groq can decode M4A/WebM/OGG correctly.
 */
internal class CloudAudioUploadClient(context: Context) {
    private val deviceHash = CloudIdentity.deviceHash(context.applicationContext)

    suspend fun submitForBackground(
        parts: List<CloudAudioPart>,
        onUploadProgress: (Float) -> Unit = {},
    ): CloudSubmission = withContext(Dispatchers.IO) {
        require(parts.size in 1..MAX_AUDIO_PARTS) { "عدد أجزاء الصوت غير مدعوم." }

        val totalBytes = parts.sumOf { it.file.length() }.coerceAtLeast(1L)
        val sentByPart = LongArray(parts.size)
        val progressLock = Any()
        val uploadSlots = Semaphore(MAX_PARALLEL_UPLOADS)

        val results = coroutineScope {
            parts.mapIndexed { index, part ->
                async {
                    uploadSlots.withPermit {
                        currentCoroutineContext().ensureActive()
                        require(part.file.isFile && part.file.length() > 0L)
                        submitPart(part, index, parts.size) { partBytes ->
                            val ratio = synchronized(progressLock) {
                                sentByPart[index] = partBytes.coerceAtMost(part.file.length())
                                sentByPart.sum().toDouble() / totalBytes.toDouble()
                            }
                            onUploadProgress(ratio.toFloat().coerceIn(0f, 1f))
                        }
                    }
                }
            }.awaitAll()
        }

        onUploadProgress(1f)
        CloudSubmission(
            segments = results.flatMap { it.segments },
            pendingAsr = results.mapNotNull { it.pending },
            providers = results.map { it.provider }.filter { it.isNotBlank() }.distinct(),
        )
    }

    private data class PartSubmission(
        val segments: List<StoredSegment>,
        val pending: PendingAsr?,
        val provider: String,
    )

    private data class UploadMedia(
        val filename: String,
        val contentType: String,
    )

    private fun submitPart(
        part: CloudAudioPart,
        index: Int,
        totalParts: Int,
        onBytes: (Long) -> Unit,
    ): PartSubmission {
        val boundary = "----Manzl${UUID.randomUUID()}"
        val provider = if (totalParts >= 3 && index == totalParts - 1) "groq_turbo" else "groq"
        val media = uploadMedia(part.file, index)
        val connection = openConnection(135_000).apply {
            requestMethod = "POST"
            doOutput = true
            setChunkedStreamingMode(256 * 1024)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        try {
            connection.outputStream.use { raw ->
                val output = BufferedOutputStream(raw, 256 * 1024)
                fun text(value: String) = output.write(value.toByteArray(Charsets.UTF_8))
                fun field(name: String, value: String) {
                    text("--$boundary\r\n")
                    text("Content-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                }

                field("mode", "asr")
                field("device_hash", deviceHash)
                field("provider", provider)
                field("offset_ms", part.offsetMs.toString())
                field("duration_ms", part.durationMs.toString())
                text("--$boundary\r\n")
                text("Content-Disposition: form-data; name=\"audio\"; filename=\"${media.filename}\"\r\n")
                text("Content-Type: ${media.contentType}\r\n\r\n")

                var sent = 0L
                part.file.inputStream().buffered(256 * 1024).use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        sent += read
                        onBytes(sent)
                    }
                }
                text("\r\n--$boundary--\r\n")
                output.flush()
            }

            val root = readJson(connection)
            val parsedProvider = parseProvider(root).ifBlank { provider }
            return if (root.optString("status", "completed") == "in_progress") {
                PartSubmission(
                    segments = emptyList(),
                    pending = PendingAsr(root.getString("job_id"), parsedProvider),
                    provider = parsedProvider,
                )
            } else {
                PartSubmission(
                    segments = parseAsrSegments(root),
                    pending = null,
                    provider = parsedProvider,
                )
            }
        } catch (error: CloudTransientException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw CloudTransientException("خدمة الترجمة تأخرت في الاستجابة. ستتم إعادة المحاولة تلقائيًا.", error)
        } catch (error: UnknownHostException) {
            throw CloudTransientException("تعذر الوصول إلى خدمة الترجمة. تحقق من اتصال الإنترنت.", error)
        } catch (error: IOException) {
            throw CloudTransientException("تعذر الاتصال بخدمة الترجمة أثناء رفع الصوت.", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun uploadMedia(file: File, index: Int): UploadMedia {
        val extension = file.extension.lowercase()
        return when (extension) {
            "m4a", "mp4" -> UploadMedia("part_${index + 1}.m4a", "audio/mp4")
            "webm" -> UploadMedia("part_${index + 1}.webm", "audio/webm")
            "ogg", "opus" -> UploadMedia("part_${index + 1}.ogg", "audio/ogg")
            "wav" -> UploadMedia("part_${index + 1}.wav", "audio/wav")
            "mp3" -> UploadMedia("part_${index + 1}.mp3", "audio/mpeg")
            else -> UploadMedia("part_${index + 1}.bin", "application/octet-stream")
        }
    }

    private fun parseAsrSegments(root: JSONObject): List<StoredSegment> {
        val array = root.optJSONArray("segments") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val text = item.optString("tr").trim()
                if (text.isBlank()) continue
                add(
                    StoredSegment(
                        id = item.optInt("id", i),
                        startMs = item.optLong("start_ms"),
                        endMs = item.optLong("end_ms"),
                        text = text,
                    )
                )
            }
        }
    }

    private fun parseProvider(root: JSONObject): String =
        root.optJSONObject("metrics")?.optString("provider")?.takeIf { it.isNotBlank() }
            ?: root.optString("provider", "")

    private fun openConnection(timeoutMs: Int): HttpURLConnection =
        (URL(CloudTranslationClient.ENDPOINT).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = timeoutMs
            setRequestProperty("Authorization", "Bearer ${CloudTranslationClient.ANON_JWT}")
            setRequestProperty("apikey", CloudTranslationClient.PUBLISHABLE_KEY)
            setRequestProperty("Accept", "application/json")
        }

    private fun readJson(connection: HttpURLConnection): JSONObject {
        val status = connection.responseCode
        val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        val root = runCatching { JSONObject(body) }.getOrElse { JSONObject() }

        if (status !in 200..299) {
            val message = root.optString("message").takeIf { it.isNotBlank() }
                ?: root.optString("error").takeIf { it.isNotBlank() }
                ?: "فشل الاتصال بخدمة الترجمة ($status)."
            if (status == 408 || status == 429 || status >= 500) {
                throw CloudTransientException(message)
            }
            error(message)
        }
        return root
    }

    companion object {
        private const val MAX_AUDIO_PARTS = 36
        private const val MAX_PARALLEL_UPLOADS = 2
    }
}
