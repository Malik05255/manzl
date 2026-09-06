package com.manzl.movietranslator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal data class CloudTranslationResult(
    val cues: List<SubtitleCue>,
    val asrMs: Long,
    val translationMs: Long,
    val totalMs: Long,
)

class CloudTranslationClient {
    suspend fun translate(audioFile: File): CloudTranslationResult = withContext(Dispatchers.IO) {
        require(audioFile.isFile && audioFile.length() > 0L)
        val boundary = "----Manzl${UUID.randomUUID()}"
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 45_000
            setRequestProperty("Authorization", "Bearer $ANON_KEY")
            setRequestProperty("apikey", ANON_KEY)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
        }

        try {
            connection.outputStream.use { raw ->
                val output = BufferedOutputStream(raw, 256 * 1024)
                fun text(value: String) = output.write(value.toByteArray(Charsets.UTF_8))
                text("--$boundary\r\n")
                text("Content-Disposition: form-data; name=\"audio\"; filename=\"audio.m4a\"\r\n")
                text("Content-Type: audio/mp4\r\n\r\n")
                audioFile.inputStream().buffered(256 * 1024).use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                }
                text("\r\n--$boundary--\r\n")
                output.flush()
            }

            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(body).optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: runCatching { JSONObject(body).optString("error") }.getOrNull()
                        ?.takeIf { it.isNotBlank() }
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
                cues = cues,
                asrMs = metrics?.optLong("asr_ms") ?: 0L,
                translationMs = metrics?.optLong("translation_ms") ?: 0L,
                totalMs = metrics?.optLong("total_ms") ?: 0L,
            )
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val ENDPOINT = "https://lbgcjmsqqhrpceijdqng.supabase.co/functions/v1/movie-translate"
        private const val ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJIUzI1NiIsInJlZiI6ImxiZ2NqbXNxcWhycGNlaWpkcW5nIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODgyMDM1MDEsImV4cCI6MjEwMzc3OTUwMX0.sl2j-iBmb_swQlZ-qlTZ5c5nDIXrO2w6tRHYeNAoF5o"
    }
}
