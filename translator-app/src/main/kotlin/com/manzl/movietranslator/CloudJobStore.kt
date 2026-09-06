package com.manzl.movietranslator

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class StoredSegment(
    val id: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

internal data class PendingAsr(
    val jobId: String,
    val provider: String,
)

internal data class BackgroundCloudJob(
    val movieKey: String,
    val movieName: String,
    val videoUri: String,
    val durationMs: Long,
    val uploadedBytes: Long,
    val startedAtEpochMs: Long,
    val segments: List<StoredSegment>,
    val pendingAsr: List<PendingAsr>,
    val providers: List<String>,
    val translationJobId: String? = null,
    val draft: Map<Int, String> = emptyMap(),
    val reviewJobId: String? = null,
    val reviewed: Map<Int, String> = emptyMap(),
    val stage: String = "تم رفع الصوت للمنصة",
    val progress: Float = 0.45f,
)

internal class CloudJobStore(context: Context) {
    private val file = File(context.filesDir, "cloud_translation_job.json")

    @Synchronized
    fun save(job: BackgroundCloudJob) {
        file.writeText(job.toJson().toString(), Charsets.UTF_8)
    }

    @Synchronized
    fun load(): BackgroundCloudJob? = runCatching {
        if (!file.isFile || file.length() <= 0L) return@runCatching null
        JSONObject(file.readText(Charsets.UTF_8)).toBackgroundJob()
    }.getOrNull()

    @Synchronized
    fun clear() {
        runCatching { file.delete() }
    }

    fun restoreUiState(): TranslatorUiState? {
        val job = load() ?: return null
        return TranslatorUiState(
            videoUri = runCatching { Uri.parse(job.videoUri) }.getOrNull(),
            videoName = job.movieName,
            movieKey = job.movieKey,
            videoDurationMs = job.durationMs,
            isRunning = true,
            progress = job.progress,
            stage = job.stage,
            uploadedBytes = job.uploadedBytes,
            partCount = if (job.durationMs > 2L * 60L * 60_000L) 2 else 1,
        )
    }
}

private fun BackgroundCloudJob.toJson(): JSONObject = JSONObject()
    .put("movie_key", movieKey)
    .put("movie_name", movieName)
    .put("video_uri", videoUri)
    .put("duration_ms", durationMs)
    .put("uploaded_bytes", uploadedBytes)
    .put("started_at", startedAtEpochMs)
    .put("segments", JSONArray().apply {
        segments.forEach { s ->
            put(JSONObject().put("id", s.id).put("start_ms", s.startMs).put("end_ms", s.endMs).put("tr", s.text))
        }
    })
    .put("pending_asr", JSONArray().apply {
        pendingAsr.forEach { p -> put(JSONObject().put("job_id", p.jobId).put("provider", p.provider)) }
    })
    .put("providers", JSONArray(providers))
    .put("translation_job_id", translationJobId ?: JSONObject.NULL)
    .put("draft", mapToArray(draft))
    .put("review_job_id", reviewJobId ?: JSONObject.NULL)
    .put("reviewed", mapToArray(reviewed))
    .put("stage", stage)
    .put("progress", progress.toDouble())

private fun JSONObject.toBackgroundJob(): BackgroundCloudJob {
    val segmentArray = optJSONArray("segments") ?: JSONArray()
    val segments = buildList {
        for (i in 0 until segmentArray.length()) {
            val s = segmentArray.getJSONObject(i)
            add(
                StoredSegment(
                    id = s.optInt("id", i),
                    startMs = s.optLong("start_ms"),
                    endMs = s.optLong("end_ms"),
                    text = s.optString("tr"),
                )
            )
        }
    }
    val pendingArray = optJSONArray("pending_asr") ?: JSONArray()
    val pending = buildList {
        for (i in 0 until pendingArray.length()) {
            val p = pendingArray.getJSONObject(i)
            add(PendingAsr(p.getString("job_id"), p.optString("provider", "Cloud ASR")))
        }
    }
    val providerArray = optJSONArray("providers") ?: JSONArray()
    val providers = buildList {
        for (i in 0 until providerArray.length()) add(providerArray.optString(i))
    }.filter { it.isNotBlank() }

    return BackgroundCloudJob(
        movieKey = getString("movie_key"),
        movieName = getString("movie_name"),
        videoUri = getString("video_uri"),
        durationMs = optLong("duration_ms"),
        uploadedBytes = optLong("uploaded_bytes"),
        startedAtEpochMs = optLong("started_at", System.currentTimeMillis()),
        segments = segments,
        pendingAsr = pending,
        providers = providers,
        translationJobId = optString("translation_job_id").takeIf { it.isNotBlank() && it != "null" },
        draft = arrayToMap(optJSONArray("draft") ?: JSONArray()),
        reviewJobId = optString("review_job_id").takeIf { it.isNotBlank() && it != "null" },
        reviewed = arrayToMap(optJSONArray("reviewed") ?: JSONArray()),
        stage = optString("stage", "المنصة تكمل الترجمة"),
        progress = optDouble("progress", 0.45).toFloat().coerceIn(0f, 1f),
    )
}

private fun mapToArray(values: Map<Int, String>): JSONArray = JSONArray().apply {
    values.toSortedMap().forEach { (id, ar) -> put(JSONObject().put("id", id).put("ar", ar)) }
}

private fun arrayToMap(array: JSONArray): Map<Int, String> = buildMap {
    for (i in 0 until array.length()) {
        val item = array.getJSONObject(i)
        val text = item.optString("ar").trim()
        if (text.isNotBlank()) put(item.getInt("id"), text)
    }
}
