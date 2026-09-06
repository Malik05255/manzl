package com.manzl.movietranslator

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal data class CloudTranslationResult(
    val cues: List<SubtitleCue>,
    val asrMs: Long,
    val translationMs: Long,
    val totalMs: Long,
    val providers: String,
)

private data class RemoteSegment(
    val id: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

private data class AsrResult(
    val segments: List<RemoteSegment>,
    val provider: String,
)

/**
 * Cloud path used by the personal translator.
 *
 * Provider credentials stay on the cloud gateway. The Android app sends only the compressed audio,
 * a device-scoped identifier, and the Turkish transcript. The gateway performs Groq/Gemini calls
 * and returns only transcription/translation data.
 */
internal class CloudTranslationClient(context: Context) {
    private val deviceHash = CloudIdentity.deviceHash(context.applicationContext)

    suspend fun translate(
        parts: List<CloudAudioPart>,
        onUploadProgress: (Float) -> Unit = {},
        onStage: (String, Float) -> Unit = { _, _ -> },
    ): CloudTranslationResult = withContext(Dispatchers.IO) {
        require(parts.size in 1..2)
        parts.forEach { require(it.file.isFile && it.file.length() > 0L) }

        val totalStarted = System.currentTimeMillis()
        val totalFileBytes = parts.sumOf { it.file.length() }.coerceAtLeast(1L)
        val uploadedBytes = AtomicLong(0L)

        onStage("رفع الصوت المضغوط للسحابة…", 0.18f)
        val asrStarted = System.currentTimeMillis()
        val asrResults = coroutineScope {
            parts.mapIndexed { index, part ->
                async(Dispatchers.IO) {
                    transcribePart(
                        part = part,
                        index = index,
                        totalFileBytes = totalFileBytes,
                        uploadedBytes = uploadedBytes,
                        onUploadProgress = onUploadProgress,
                        onStage = onStage,
                    )
                }
            }.awaitAll()
        }
        val asrMs = System.currentTimeMillis() - asrStarted

        val ordered = asrResults
            .flatMap { it.segments }
            .sortedBy { it.startMs }
            .mapIndexed { index, segment -> segment.copy(id = index) }
        check(ordered.isNotEmpty()) { "لم تتعرف السحابة على حوار تركي في هذا المقطع." }

        onStage("Gemini يترجم الفيلم كسياق واحد…", 0.72f)
        val translationStarted = System.currentTimeMillis()
        val firstPass = translateWholeTranscript(ordered, onStage)
        val firstMap = firstPass.associate { it.first to it.second }
        check(firstMap.size == ordered.size) {
            "الترجمة السحابية لم تُرجع جميع أسطر الحوار (${firstMap.size}/${ordered.size})."
        }

        onStage("مراجعة الدقة والمعنى سطرًا بسطر…", 0.90f)
        val reviewed = runCatching {
            reviewWholeTranslation(ordered, firstPass, onStage)
        }.getOrNull()
        val reviewedMap = reviewed?.associate { it.first to it.second }.orEmpty()
        val finalMap = if (reviewedMap.size == ordered.size) reviewedMap else firstMap
        val translationMs = System.currentTimeMillis() - translationStarted

        val cues = ordered.map { segment ->
            SubtitleCue(
                startMs = segment.startMs,
                endMs = segment.endMs,
                sourceText = segment.text,
                translatedText = finalMap.getValue(segment.id).trim(),
                confidence = 1f,
            )
        }

        CloudTranslationResult(
            cues = cues,
            asrMs = asrMs,
            translationMs = translationMs,
            totalMs = System.currentTimeMillis() - totalStarted,
            providers = asrResults.joinToString(" + ") { it.provider } + " → Gemini + مراجعة دقة",
        )
    }

    private suspend fun transcribePart(
        part: CloudAudioPart,
        index: Int,
        totalFileBytes: Long,
        uploadedBytes: AtomicLong,
        onUploadProgress: (Float) -> Unit,
        onStage: (String, Float) -> Unit,
    ): AsrResult {
        val boundary = "----Manzl${UUID.randomUUID()}"
        val provider = if (index == 0) "groq" else "gemini"
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
                    text("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    text(value)
                    text("\r\n")
                }

                field("mode", "asr")
                field("device_hash", deviceHash)
                field("provider", provider)
                field("offset_ms", part.offsetMs.toString())
                field("duration_ms", part.durationMs.toString())
                text("--$boundary\r\n")
                text("Content-Disposition: form-data; name=\"audio\"; filename=\"part_${index + 1}.ogg\"\r\n")
                text("Content-Type: audio/ogg\r\n\r\n")
                part.file.inputStream().buffered(256 * 1024).use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        val now = uploadedBytes.addAndGet(read.toLong())
                        onUploadProgress(
                            (now.toDouble() / totalFileBytes.toDouble()).toFloat().coerceIn(0f, 1f)
                        )
                    }
                }
                text("\r\n--$boundary--\r\n")
                output.flush()
            }

            val root = readJson(connection)
            if (root.optString("status", "completed") == "in_progress") {
                val jobId = root.getString("job_id")
                onStage("السحابة تتعرف على الحوار التركي…", 0.52f)
                return pollAsr(jobId, onStage)
            }
            return parseAsr(root)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun pollAsr(jobId: String, onStage: (String, Float) -> Unit): AsrResult {
        repeat(MAX_POLL_ATTEMPTS) { attempt ->
            currentCoroutineContext().ensureActive()
            val root = postJson(
                JSONObject()
                    .put("mode", "poll")
                    .put("kind", "asr")
                    .put("device_hash", deviceHash)
                    .put("job_id", jobId),
                timeoutMs = 25_000,
            )
            when (root.optString("status")) {
                "completed" -> return parseAsr(root)
                "failed", "cancelled" -> error(root.optString("message", "فشل التعرف السحابي على الحوار."))
            }
            val progress = (0.52f + (attempt.coerceAtMost(40) / 40f) * 0.13f).coerceAtMost(0.65f)
            onStage("السحابة تتعرف على الحوار التركي…", progress)
            delay(POLL_DELAY_MS)
        }
        error("استغرقت مرحلة التعرف على الحوار وقتًا أطول من المتوقع.")
    }

    private fun parseAsr(root: JSONObject): AsrResult {
        val array = root.optJSONArray("segments") ?: JSONArray()
        val segments = ArrayList<RemoteSegment>(array.length())
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val text = item.optString("tr").trim()
            if (text.isBlank()) continue
            segments += RemoteSegment(
                id = item.optInt("id", 0),
                startMs = item.getLong("start_ms"),
                endMs = item.getLong("end_ms"),
                text = text,
            )
        }
        check(segments.isNotEmpty()) { "لم تتعرف السحابة على كلام تركي واضح." }
        val provider = root.optJSONObject("metrics")?.optString("provider")
            ?.takeIf { it.isNotBlank() }
            ?: root.optString("provider", "Cloud ASR")
        return AsrResult(segments.sortedBy { it.startMs }, provider)
    }

    private suspend fun translateWholeTranscript(
        segments: List<RemoteSegment>,
        onStage: (String, Float) -> Unit,
    ): List<Pair<Int, String>> {
        val segmentArray = JSONArray()
        segments.forEach { segment ->
            segmentArray.put(
                JSONObject()
                    .put("id", segment.id)
                    .put("start_ms", segment.startMs)
                    .put("end_ms", segment.endMs)
                    .put("tr", segment.text)
            )
        }
        val started = postJson(
            JSONObject()
                .put("mode", "translate_start")
                .put("device_hash", deviceHash)
                .put("segments", segmentArray),
            timeoutMs = 40_000,
        )
        if (started.optString("status") == "completed") return parseTranslation(started)
        val jobId = started.getString("job_id")
        return pollTranslationJob(jobId, "translate", "Gemini يترجم الفيلم كسياق واحد…", 0.75f, 0.88f, onStage)
    }

    private suspend fun reviewWholeTranslation(
        segments: List<RemoteSegment>,
        draft: List<Pair<Int, String>>,
        onStage: (String, Float) -> Unit,
    ): List<Pair<Int, String>> {
        val segmentArray = JSONArray()
        segments.forEach { segment ->
            segmentArray.put(
                JSONObject()
                    .put("id", segment.id)
                    .put("start_ms", segment.startMs)
                    .put("end_ms", segment.endMs)
                    .put("tr", segment.text)
            )
        }
        val draftArray = JSONArray()
        draft.forEach { (id, ar) -> draftArray.put(JSONObject().put("id", id).put("ar", ar)) }
        val started = postJson(
            JSONObject()
                .put("mode", "review_start")
                .put("device_hash", deviceHash)
                .put("segments", segmentArray)
                .put("draft", draftArray),
            timeoutMs = 40_000,
        )
        if (started.optString("status") == "completed") return parseTranslation(started)
        val jobId = started.getString("job_id")
        return pollTranslationJob(jobId, "review", "مراجعة الدقة والمعنى سطرًا بسطر…", 0.91f, 0.96f, onStage)
    }

    private suspend fun pollTranslationJob(
        jobId: String,
        kind: String,
        stage: String,
        from: Float,
        to: Float,
        onStage: (String, Float) -> Unit,
    ): List<Pair<Int, String>> {
        repeat(MAX_POLL_ATTEMPTS) { attempt ->
            currentCoroutineContext().ensureActive()
            val root = postJson(
                JSONObject()
                    .put("mode", "poll")
                    .put("kind", kind)
                    .put("device_hash", deviceHash)
                    .put("job_id", jobId),
                timeoutMs = 25_000,
            )
            when (root.optString("status")) {
                "completed" -> return parseTranslation(root)
                "failed", "cancelled" -> error(root.optString("message", "فشلت مرحلة الترجمة السحابية."))
            }
            val fraction = attempt.coerceAtMost(60) / 60f
            onStage(stage, from + (to - from) * fraction)
            delay(POLL_DELAY_MS)
        }
        error("استغرقت مرحلة الترجمة وقتًا أطول من المتوقع.")
    }

    private fun parseTranslation(root: JSONObject): List<Pair<Int, String>> {
        val array = root.optJSONArray("subtitles") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val arabic = item.optString("ar").trim()
                if (arabic.isNotBlank()) add(item.getInt("id") to arabic)
            }
        }
    }

    private fun postJson(payload: JSONObject, timeoutMs: Int): JSONObject {
        val connection = openConnection(timeoutMs).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            readJson(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(timeoutMs: Int): HttpURLConnection =
        (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = timeoutMs
            setRequestProperty("Authorization", "Bearer $ANON_JWT")
            setRequestProperty("apikey", PUBLISHABLE_KEY)
            setRequestProperty("Accept", "application/json")
        }

    private fun readJson(connection: HttpURLConnection): JSONObject {
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
        return JSONObject(body)
    }

    companion object {
        internal const val ENDPOINT = "https://lbgcjmsqqhrpceijdqng.supabase.co/functions/v1/movie-translate"
        internal const val PUBLISHABLE_KEY = "sb_publishable_TllPSeKhRJx_IegHMxkZmA_Q9FLBUR_"
        internal const val ANON_JWT = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImxiZ2NqbXNxcWhycGNlaWpkcW5nIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODgyMDM1MDEsImV4cCI6MjEwMzc3OTUwMX0.sl2j-iBmb_swQlZ-qlTZ5c5nDIXrO2w6tRHYeNAoF5o"
        private const val POLL_DELAY_MS = 2_000L
        private const val MAX_POLL_ATTEMPTS = 450
    }
}
