package com.manzl.movietranslator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal enum class SmartRetention(val apiValue: String, val arabicLabel: String) {
    NONE("none", "بدون حفظ"),
    ONE_DAY("1d", "24 ساعة"),
    SEVEN_DAYS("7d", "7 أيام"),
    THIRTY_DAYS("30d", "30 يوم"),
    PERMANENT("permanent", "دائم"),
}

internal data class SmartMediaRemoteResult(
    val jobId: String,
    val title: String,
    val playbackUrl: String,
    val detectedLanguage: String,
    val srtText: String,
    val vttText: String,
    val summary: String,
    val characters: List<String>,
    val events: List<String>,
    val providers: String,
    val processingMs: Long,
) {
    fun writeSubtitle(context: Context): File {
        val dir = File(context.filesDir, "remote-subtitles").apply { mkdirs() }
        return File(dir, "${jobId.ifBlank { UUID.randomUUID().toString() }}_ar.srt").apply {
            writeText(srtText, Charsets.UTF_8)
        }
    }
}

internal class SmartMediaGatewayClient(private val context: Context) {
    private val endpoint = CloudTranslationClient.ENDPOINT.substringBeforeLast('/') + "/media-gateway"
    private val clientId: String by lazy { stableClientId(context) }

    fun clientId(): String = clientId

    fun translateUrl(
        sourceUrl: String,
        retention: SmartRetention,
        language: String = "auto",
        durationMs: Long = 0L,
    ): SmartMediaRemoteResult {
        val body = JSONObject()
            .put("mode", "translate_url")
            .put("client_id", clientId)
            .put("source_url", sourceUrl.trim())
            .put("retention", retention.apiValue)
            .put("language", language)
            .put("duration_ms", durationMs.coerceAtLeast(0L))

        val root = post(body, 145_000)
        if (root.has("error")) {
            throw IOException(root.optString("message", root.optString("error", "تعذر ترجمة الرابط.")))
        }

        val summaryObject = root.optJSONObject("summary")
        return SmartMediaRemoteResult(
            jobId = root.optString("job_id"),
            title = root.optString("title", "Movie"),
            playbackUrl = root.optString("playback_url", sourceUrl),
            detectedLanguage = root.optString("detected_language", "auto"),
            srtText = root.optString("srt"),
            vttText = root.optString("vtt"),
            summary = summaryObject?.optString("summary").orEmpty(),
            characters = summaryObject?.optJSONArray("characters").toDisplayList("name", "role").orEmpty(),
            events = summaryObject?.optJSONArray("major_events").toStringList(),
            providers = providerSummary(root.optJSONArray("providers")),
            processingMs = root.optLong("processing_ms", 0L),
        )
    }

    fun plan(
        sourceKind: String,
        retention: SmartRetention,
        language: String = "auto",
        durationMs: Long = 0L,
    ): JSONObject = post(
        JSONObject()
            .put("mode", "plan")
            .put("source_kind", sourceKind)
            .put("retention", retention.apiValue)
            .put("language", language)
            .put("duration_ms", durationMs.coerceAtLeast(0L)),
        20_000,
    )

    private fun post(body: JSONObject, timeoutMs: Int): JSONObject {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("apikey", CloudTranslationClient.PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer ${CloudTranslationClient.PUBLISHABLE_KEY}")
        }
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (text.isBlank()) throw IOException("استجابة البوابة فارغة ($code).")
            val root = JSONObject(text)
            if (code !in 200..299 && !root.has("error")) {
                throw IOException("تعذر الاتصال ببوابة H AI ($code).")
            }
            root
        } finally {
            connection.disconnect()
        }
    }

    private fun stableClientId(context: Context): String {
        val prefs = context.getSharedPreferences("smart_media_gateway", Context.MODE_PRIVATE)
        val existing = prefs.getString("client_id", null)
        if (!existing.isNullOrBlank()) return existing
        val created = "android-" + UUID.randomUUID().toString().replace("-", "").take(20)
        prefs.edit().putString("client_id", created).apply()
        return created
    }

    private fun providerSummary(array: JSONArray?): String {
        if (array == null) return "Smart Router"
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val provider = item.optString("provider")
                if (provider.isNotBlank() && provider !in this) add(provider)
            }
        }.joinToString(" + ").ifBlank { "Smart Router" }
    }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            val value = optString(i).trim()
            if (value.isNotBlank()) add(value)
        }
    }
}

private fun JSONArray?.toDisplayList(primary: String, secondary: String): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            val item = optJSONObject(i) ?: continue
            val a = item.optString(primary).trim()
            val b = item.optString(secondary).trim()
            if (a.isNotBlank()) add(if (b.isBlank()) a else "$a — $b")
        }
    }
}
