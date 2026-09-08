package com.manzl.movietranslator

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.UUID

internal enum class SmartRetention(val apiValue: String, val arabicLabel: String) {
    NONE("none", "بدون حفظ"), ONE_DAY("1d", "24 ساعة"), SEVEN_DAYS("7d", "7 أيام"),
    THIRTY_DAYS("30d", "30 يوم"), PERMANENT("permanent", "دائم")
}

internal data class SmartMediaRemoteResult(
    val jobId: String, val title: String, val playbackUrl: String, val subtitleUrl: String,
    val detectedLanguage: String, val asrRoute: String, val srtText: String, val vttText: String,
    val summary: String, val characters: List<String>, val events: List<String>,
    val providers: String, val processingMs: Long,
) {
    fun writeSubtitle(context: Context): File {
        val dir = File(context.filesDir, "remote-subtitles").apply { mkdirs() }
        return File(dir, "${jobId.ifBlank { UUID.randomUUID().toString() }}_ar.srt").apply { writeText(srtText, Charsets.UTF_8) }
    }
}

internal class SmartMediaGatewayClient(private val context: Context) {
    private val endpoint = CloudTranslationClient.ENDPOINT.substringBeforeLast('/') + "/media-gateway"
    private val prefs = context.getSharedPreferences("smart_media_gateway", Context.MODE_PRIVATE)
    private var accountKey: String = loadOrCreateAccountKey()

    fun personalSyncKey(): String = accountKey
    fun replacePersonalSyncKey(value: String) {
        val normalized = value.trim()
        require(ACCOUNT_KEY_REGEX.matches(normalized)) { "مفتاح المزامنة غير صالح." }
        if (normalized == accountKey) return
        accountKey = normalized
        prefs.edit().putString(KEY_ACCOUNT_KEY, normalized).remove(KEY_PENDING_JOB).apply()
    }
    fun lastPendingJobId(): String? = prefs.getString(KEY_PENDING_JOB, null)?.takeIf { it.isNotBlank() }

    fun translateUrl(sourceUrl: String, retention: SmartRetention, language: String = "auto", durationMs: Long = 0L,
        onProgress: (Float, String) -> Unit = { _, _ -> }): SmartMediaRemoteResult {
        val accepted = post(privatePayload("translate_url").put("source_url", sourceUrl.trim())
            .put("retention", retention.apiValue).put("language", language).put("duration_ms", durationMs.coerceAtLeast(0L)), 30_000)
        if (accepted.has("error")) throw IOException(accepted.optString("message", accepted.optString("error", "تعذر بدء ترجمة الرابط.")))
        val jobId = accepted.optString("job_id").ifBlank { throw IOException("لم تُرجع البوابة رقم المهمة.") }
        prefs.edit().putString(KEY_PENDING_JOB, jobId).apply()
        onProgress(0f, "تم إرسال المهمة للسحابة")
        return waitForJob(jobId, onProgress)
    }

    fun resumePending(onProgress: (Float, String) -> Unit = { _, _ -> }): SmartMediaRemoteResult? =
        lastPendingJobId()?.let { waitForJob(it, onProgress) }

    fun waitForJob(jobId: String, onProgress: (Float, String) -> Unit = { _, _ -> }): SmartMediaRemoteResult {
        val deadline = System.currentTimeMillis() + MAX_POLL_WINDOW_MS
        var missingCount = 0
        while (System.currentTimeMillis() < deadline) {
            val root = post(privatePayload("get_job").put("job_id", jobId), 20_000)
            val job = root.optJSONObject("job")
            if (job == null) {
                if (++missingCount >= 5) throw IOException("لم تعد مهمة الترجمة موجودة على الخادم أو أنها تخص مفتاح مزامنة آخر.")
                Thread.sleep(1_500L); continue
            }
            missingCount = 0
            val progress = job.optDouble("progress", 0.0).toFloat().coerceIn(0f, 1f)
            val stage = job.optString("stage", "المعالجة السحابية")
            onProgress(progress, stage)
            when (job.optString("status")) {
                "completed" -> { prefs.edit().remove(KEY_PENDING_JOB).apply(); return parseCompletedJob(job) }
                "failed", "cancelled" -> { prefs.edit().remove(KEY_PENDING_JOB).apply(); throw IOException(job.optString("error", stage.ifBlank { "تعذر إكمال المهمة." })) }
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        throw IOException("المهمة ما زالت تعمل في السحابة. افتح التطبيق لاحقًا لمتابعتها.")
    }

    fun listJobs(): JSONArray = post(privatePayload("list_jobs"), 20_000).optJSONArray("jobs") ?: JSONArray()
    fun plan(sourceKind: String, retention: SmartRetention, language: String = "auto", durationMs: Long = 0L): JSONObject =
        post(JSONObject().put("mode", "plan").put("source_kind", sourceKind).put("retention", retention.apiValue)
            .put("language", language).put("duration_ms", durationMs.coerceAtLeast(0L)), 20_000)

    private fun privatePayload(mode: String) = JSONObject().put("mode", mode).put("account_key", accountKey)

    private fun parseCompletedJob(job: JSONObject): SmartMediaRemoteResult {
        val summaryObject = job.optJSONObject("summary")
        val started = parseIsoMs(job.optString("started_at")); val completed = parseIsoMs(job.optString("completed_at"))
        return SmartMediaRemoteResult(
            job.optString("id"), job.optString("title", "Movie"), job.optString("playback_url", job.optString("source_url")),
            job.optString("subtitle_url"), job.optString("source_language", "auto").ifBlank { "auto" }, job.optString("asr_route"),
            job.optString("srt_text"), job.optString("vtt_text"), summaryObject?.optString("summary").orEmpty(),
            summaryObject?.optJSONArray("characters").toDisplayList("name", "role"), summaryObject?.optJSONArray("major_events").toStringList(),
            providerSummary(job.optJSONArray("provider_trace")), if (started > 0L && completed >= started) completed - started else 0L
        )
    }

    private fun post(body: JSONObject, timeoutMs: Int): JSONObject {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15_000; readTimeout = timeoutMs; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("apikey", CloudTranslationClient.PUBLISHABLE_KEY)
        }
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (text.isBlank()) throw IOException("استجابة البوابة فارغة ($code).")
            val root = JSONObject(text)
            if (code !in 200..299) throw IOException(root.optString("message", root.optString("error", "تعذر الاتصال ببوابة H AI ($code).")))
            root
        } finally { connection.disconnect() }
    }

    private fun loadOrCreateAccountKey(): String {
        val existing = prefs.getString(KEY_ACCOUNT_KEY, null)?.trim()
        if (!existing.isNullOrBlank() && ACCOUNT_KEY_REGEX.matches(existing)) return existing
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val created = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        prefs.edit().putString(KEY_ACCOUNT_KEY, created).remove(KEY_PENDING_JOB).apply(); return created
    }
    private fun providerSummary(array: JSONArray?): String = if (array == null) "Smart Router" else buildList {
        for (i in 0 until array.length()) array.optJSONObject(i)?.optString("provider")?.takeIf { it.isNotBlank() && it !in this }?.let { add(it) }
    }.joinToString(" + ").ifBlank { "Smart Router" }
    private fun parseIsoMs(value: String): Long = runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrDefault(0L)

    companion object {
        private val ACCOUNT_KEY_REGEX = Regex("^[A-Za-z0-9_-]{32,128}$")
        private const val KEY_ACCOUNT_KEY = "personal_account_key_v1"; private const val KEY_PENDING_JOB = "pending_job_id"
        private const val POLL_INTERVAL_MS = 2_000L; private const val MAX_POLL_WINDOW_MS = 4L * 60L * 60L * 1_000L
    }
}

private fun JSONArray?.toStringList(): List<String> = if (this == null) emptyList() else buildList {
    for (i in 0 until length()) optString(i).trim().takeIf { it.isNotBlank() }?.let { add(it) }
}
private fun JSONArray?.toDisplayList(primary: String, secondary: String): List<String> = if (this == null) emptyList() else buildList {
    for (i in 0 until length()) {
        val item = optJSONObject(i) ?: continue; val a = item.optString(primary).trim(); val b = item.optString(secondary).trim()
        if (a.isNotBlank()) add(if (b.isBlank()) a else "$a — $b")
    }
}
