package com.manzl.movietranslator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal data class CloudTranslationResult(
    val cues: List<SubtitleCue>,
    val asrMs: Long,
    val translationMs: Long,
    val totalMs: Long,
    val providers: String,
)

class CloudTranslationClient {
    suspend fun translate(
        parts: List<CloudAudioPart>,
        onUploadProgress: (Float) -> Unit = {},
    ): CloudTranslationResult = withContext(Dispatchers.IO) {
        require(parts.size in 1..2)
        parts.forEach { require(it.file.isFile && it.file.length() > 0L) }

        val boundary = "----Manzl${UUID.randomUUID()}"
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 150_000
            setChunkedStreamingMode(256 * 1024)
            setRequestProperty("Authorization", "Bearer $ANON_JWT")
            setRequestProperty("apikey", PUBLISHABLE_KEY)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
        }

        val totalFileBytes = parts.sumOf { it.file.length() }.coerceAtLeast(1L)
        var uploadedFileBytes = 0L

        try {
            connection.outputStream.use { raw ->
                val output = BufferedOutputStream(raw, 256 * 1024)
                fun text(value: String) = output.write(value.toByteArray(Charsets.UTF_8))
                fun field(name: String, value: String) {
                    text("--$boundary\r\n")
                    text("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    text(value)
                    text("\r\n")
                }

                field("part_count", parts.size.toString())
                field("client_mode", "cloud_hybrid_v1")

                parts.forEachIndexed { index, part ->
                    field("offset${index}_ms", part.offsetMs.toString())
                    field("duration${index}_ms", part.durationMs.toString())
                    text("--$boundary\r\n")
                    text("Content-Disposition: form-data; name=\"audio$index\"; filename=\"part_${index + 1}.ogg\"\r\n")
                    text("Content-Type: audio/ogg\r\n\r\n")
                    part.file.inputStream().buffered(256 * 1024).use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            uploadedFileBytes += read
                            onUploadProgress(
                                (uploadedFileBytes.toDouble() / totalFileBytes.toDouble())
                                    .toFloat()
                                    .coerceIn(0f, 1f)
                            )
                        }
                    }
                    text("\r\n")
                }
                text("--$boundary--\r\n")
                output.flush()
                onUploadProgress(1f)
            }

            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val parsed = runCatching { JSONObject(body) }.getOrNull()
                val message = parsed?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: parsed?.optString("error")?.takeIf { it.isNotBlank() }
                    ?: "فشل الاتصال بخدمة الترجمة السحابية ($status)."
                error(message)
            }

            val root = JSONObject(body)
            val subtitles = root.getJSONArray("subtitles")
            val cues = ArrayList<SubtitleCue>(subtitles.length())
            for (index in 0 until subtitles.length()) {
                val item = subtitles.getJSONObject(index)
                val source = item.optString("tr").trim()
                val arabic = item.optString("ar").trim()
                if (source.isBlank() || arabic.isBlank()) continue
                cues += SubtitleCue(
                    startMs = item.getLong("start_ms"),
                    endMs = item.getLong("end_ms"),
                    sourceText = source,
                    translatedText = arabic,
                    confidence = 1f,
                )
            }
            check(cues.isNotEmpty()) { "لم تُرجع الخدمة أي ترجمة عربية." }

            val metrics = root.optJSONObject("metrics")
            CloudTranslationResult(
                cues = cues.sortedBy { it.startMs },
                asrMs = metrics?.optLong("asr_ms") ?: 0L,
                translationMs = metrics?.optLong("translation_ms") ?: 0L,
                totalMs = metrics?.optLong("total_ms") ?: 0L,
                providers = metrics?.optString("providers").orEmpty(),
            )
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val ENDPOINT = "https://lbgcjmsqqhrpceijdqng.supabase.co/functions/v1/movie-translate"
        private const val PUBLISHABLE_KEY = "sb_publishable_TllPSeKhRJx_IegHMxkZmA_Q9FLBUR_"
        private const val ANON_JWT = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJIUzI1NiIsInJlZiI6ImxiZ2NqbXNxcWhycGNlaWpkcW5nIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODgyMDM1MDEsImV4cCI6MjEwMzc3OTUwMX0.sl2j-iBmb_swQlZ-qlTZ5c5nDIXrO2w6tRHYeNAoF5o"
    }
}
