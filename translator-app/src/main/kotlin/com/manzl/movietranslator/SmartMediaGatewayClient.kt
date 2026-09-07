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
    private val prefs = context.getSharedPreferences("smart_media_gateway", Context.MODE_PRIVATE)
    private val clientId: String by lazy { stableClientId(context) }

    fun clientId(): String = clientId
    fun lastPendingJobId(): String? = prefs.getString(KEY_PENDING_JOB, null)?.takeIf { it.isNotBlank() }

    fun translateUrl(
        sourceUrl: String,
        retention: SmartRetention,
        language: String = "auto",
        durationMs: Long = 0L,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): SmartMediaRemoteResult {
        val accepted = post(
            JSONObject()
                .put("mode", "translate_url")
                .put("client_id", clientId)
                .put("source_url", sourceUrl.trim())
                .put("retention", retention.apiValue)
                .put("language", language)
                .put("duration_ms", durationMs.coerceAtLeast(0L)),
            30_000,
        )
        if (accepted.has("error")) {
            throw IOException(accepted.optString("message", accepted.optString("error", "تعذر بدء ترجمة الرابط.")))
        }
        val jobId = accepted.optString("job_id").ifBlank { throw IOException("لم تُرجع البوابة رقم المهمة.") }
        prefs.edit().putString(KEY_PENDING_JOB, jobId).apply()
        onProgress(0f, "تم إرسال المهمة للسحابة")
        return waitForJob(jobId, onProgress)
    }

    fun resumePending(onProgress: (Float, String) -> Unit = { _, _ -> }): SmartMediaRemoteResult? {
        val id = lastPendingJobId() ?: return null
        return waitForJob(id, onProgress)
    }

    fun waitForJob(
        jobId: String,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): SmartMediaRemoteResult {
        val deadline = System.currentTimeMillis() + MAX_POLL_WINDOW_MS
        var missingCount = 0
        while (System.currentTimeMillis() < deadline) {
            val root = post(
                JSONObject()
                    .put("mode", "get_job")
                    .put("client_id", clientId)
                    .put("job_id", jobId),
                20_000,
            )
            val job = root.optJSONObject("job")
            if (job == null) {
                missingCount += 1
                if (missingCount >= 5) throw IOException("لم تعد مهمة الترجمة موجودة على الخادم.")
                Thread.sleep(1_500L)
                continue
            }
            missingCount = 0
            val progress = job.optDouble("progress", 0.0).toFloat().coerceIn(0f, 1f)
            val stage = job.optString("stage", "المعالجة السحابية")
            onProgress(progress, stage)
            when (job.optString("status")) {
                "completed" -> {
                    prefs.edit().remove(KEY_PENDING_JOB).apply()
                    return parseCompletedJob(job)
                }
                "failed", "cancelled" -> {
                    prefs.edit().remove(KEY_PENDING_JOB).apply()
                    throw IOException(job.optString("error", stage.ifBlank { "تعذر إكمال المهمة." }))
                }
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        // The cloud job continues even if this local wait window expires.
        throw IOException("المهمة ما زالت تعمل في السحابة. افتح التطبيق لاحقًا لمتابعتها.")
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

    private fun parseCompletedJob(job: JSONObject): SmartMediaRemoteResult {
        val summaryObject = job.optJSONObject("summary")
        val started = parseIsoMs(job.optString("started_at"))
        val completed = parseIsoMs(job.optString("completed_at"))
        return SmartMediaRemoteResult(
            jobId = job.optString("id"),
            title = job.optString("title", "Movie"),
            playbackUrl = job.optString("playback_url", job.optString("source_url")),
            detectedLanguage = job.optString("source_language", "auto").ifBlank { "auto" },
            srtText = job.optString("srt_text"),
            vttText = job.optString("vtt_text"),
            summary = summaryObject?.optString("summary").orEmpty(),
            characters = summaryObject?.optJSONArray("characters").toDisplayList("name", "role"),
            events = summaryObject?.optJSONArray("major_events").toStringList(),
            providers = providerSummary(job.optJSONArray("provider_trace")),
            processingMs = if (started > 0L && completed >= started) completed - started else 0L,
        )
    }

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
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (text.isBlank()) throw IOException("استجابة البوابة فارغة ($code).")
            val root = JSONObject(text)
            if (code !in 200..299) {
                throw IOException(root.optString("message", root.optString("error", "تعذر الاتصال ببوابة H AI ($code).")))
            }
            root
        } finally {
            connection.disconnect()
        }
    }

    private fun stableClientId(context: Context): String {
        val existing = prefs.getString(KEY_CLIENT_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = "android-" + UUID.randomUUID().toString().replace("-", "").take(20)
        prefs.edit().putString(KEY_CLIENT_ID, created).apply()
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

    private fun parseIsoMs(value: String): Long = runCatching {
        java.time.Instant.parse(value).toEpochMilli()
    }.getOrDefault(0L)

    companion object {
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_PENDING_JOB = "pending_job_id"
        private const val POLL_INTERVAL_MS = 2_000L
        private const val MAX_POLL_WINDOW_MS = 4L * 60L * 60L * 1_000L
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
