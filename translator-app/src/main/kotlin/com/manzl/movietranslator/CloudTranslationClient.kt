package com.manzl.movietranslator

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID

internal data class CloudTranslationResult(
    val cues: List<SubtitleCue>,
    val asrMs: Long,
    val translationMs: Long,
    val totalMs: Long,
    val providers: String,
)

internal data class CloudSubmission(
    val segments: List<StoredSegment>,
    val pendingAsr: List<PendingAsr>,
    val providers: List<String>,
)

internal data class CloudAdvance(
    val job: BackgroundCloudJob,
    val result: CloudTranslationResult? = null,
)

internal class CloudTransientException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal class CloudTranslationClient(context: Context) {
    private val deviceHash = CloudIdentity.deviceHash(context.applicationContext)

    suspend fun submitForBackground(
        parts: List<CloudAudioPart>,
        onUploadProgress: (Float) -> Unit = {},
    ): CloudSubmission = withContext(Dispatchers.IO) {
        require(parts.size in 1..2)
        val totalBytes = parts.sumOf { it.file.length() }.coerceAtLeast(1L)
        var completedBytes = 0L
        val segments = mutableListOf<StoredSegment>()
        val pending = mutableListOf<PendingAsr>()
        val providers = mutableListOf<String>()

        parts.forEachIndexed { index, part ->
            currentCoroutineContext().ensureActive()
            require(part.file.isFile && part.file.length() > 0L)
            val baseBytes = completedBytes
            val submitted = submitPart(part, index) { partBytes ->
                val overall = (baseBytes + partBytes).toDouble() / totalBytes.toDouble()
                onUploadProgress(overall.toFloat().coerceIn(0f, 1f))
            }
            completedBytes += part.file.length()
            onUploadProgress((completedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f))
            segments += submitted.segments
            submitted.pending?.let(pending::add)
            submitted.provider.takeIf { it.isNotBlank() }?.let(providers::add)
        }

        CloudSubmission(
            segments = segments,
            pendingAsr = pending,
            providers = providers.distinct(),
        )
    }

    suspend fun advance(job: BackgroundCloudJob): CloudAdvance = withContext(Dispatchers.IO) {
        var current = job

        if (current.pendingAsr.isNotEmpty()) {
            val completedSegments = current.segments.toMutableList()
            val remaining = mutableListOf<PendingAsr>()
            val providers = current.providers.toMutableList()

            for (pending in current.pendingAsr) {
                currentCoroutineContext().ensureActive()
                val root = postJson(
                    JSONObject()
                        .put("mode", "poll")
                        .put("kind", "asr")
                        .put("device_hash", deviceHash)
                        .put("job_id", pending.jobId),
                    25_000,
                )
                when (root.optString("status")) {
                    "completed" -> {
                        completedSegments += parseAsrSegments(root)
                        parseProvider(root).takeIf { it.isNotBlank() }?.let(providers::add)
                    }
                    "failed", "cancelled" -> error(root.optString("message", "فشل التعرف على الحوار."))
                    else -> remaining += pending
                }
            }

            val done = current.pendingAsr.size - remaining.size
            val ratio = if (current.pendingAsr.isEmpty()) 1f
            else done.toFloat() / current.pendingAsr.size.toFloat()

            current = current.copy(
                segments = completedSegments,
                pendingAsr = remaining,
                providers = providers.distinct(),
                stage = if (remaining.isEmpty()) "اكتمل فهم الحوار التركي" else "تحليل الحوار التركي على المنصة",
                progress = if (remaining.isEmpty()) 0.62f else 0.46f + ratio * 0.14f,
            )
            if (remaining.isNotEmpty()) return@withContext CloudAdvance(current)
        }

        val ordered = normalizeSegments(current.segments)
        check(ordered.isNotEmpty()) { "لم تتعرف المنصة على حوار تركي واضح." }
        if (ordered != current.segments) current = current.copy(segments = ordered)

        if (current.draft.isEmpty() && current.translationJobId == null) {
            val started = startTranslation(ordered)
            if (started.optString("status") == "completed") {
                current = current.copy(
                    draft = parseTranslation(started),
                    stage = "اكتملت الترجمة الأولية",
                    progress = 0.82f,
                )
            } else {
                current = current.copy(
                    translationJobId = started.getString("job_id"),
                    stage = "صياغة الترجمة العربية",
                    progress = 0.64f,
                )
                return@withContext CloudAdvance(current)
            }
        }

        if (current.translationJobId != null) {
            val root = pollTranslation(current.translationJobId, "translate")
            when (root.optString("status")) {
                "completed" -> current = current.copy(
                    translationJobId = null,
                    draft = parseTranslation(root),
                    stage = "اكتملت الترجمة الأولية",
                    progress = 0.82f,
                )
                "failed", "cancelled" -> error(root.optString("message", "فشلت الترجمة السحابية."))
                else -> return@withContext CloudAdvance(
                    current.copy(stage = "صياغة الترجمة العربية", progress = 0.72f)
                )
            }
        }

        check(current.draft.size == ordered.size) {
            "الترجمة لم تُرجع جميع الأسطر (${current.draft.size}/${ordered.size})."
        }

        if (current.reviewed.isEmpty() && current.reviewJobId == null) {
            val started = startReview(ordered, current.draft)
            if (started.optString("status") == "completed") {
                current = current.copy(
                    reviewed = parseTranslation(started),
                    stage = "اكتملت مراجعة الدقة",
                    progress = 0.95f,
                )
            } else {
                current = current.copy(
                    reviewJobId = started.getString("job_id"),
                    stage = "مراجعة المعنى والدقة",
                    progress = 0.84f,
                )
                return@withContext CloudAdvance(current)
            }
        }

        if (current.reviewJobId != null) {
            val root = pollTranslation(current.reviewJobId, "review")
            when (root.optString("status")) {
                "completed" -> current = current.copy(
                    reviewJobId = null,
                    reviewed = parseTranslation(root),
                    stage = "اكتملت مراجعة الدقة",
                    progress = 0.95f,
                )
                "failed", "cancelled" -> error(root.optString("message", "فشلت مراجعة الترجمة."))
                else -> return@withContext CloudAdvance(
                    current.copy(stage = "مراجعة المعنى والدقة", progress = 0.89f)
                )
            }
        }

        val finalMap = current.reviewed.takeIf { it.size == ordered.size } ?: current.draft
        val cues = ordered.map { segment ->
            SubtitleCue(
                startMs = segment.startMs,
                endMs = segment.endMs,
                sourceText = segment.text,
                translatedText = finalMap.getValue(segment.id).trim(),
                confidence = 1f,
            )
        }

        val totalMs = (System.currentTimeMillis() - current.startedAtEpochMs).coerceAtLeast(0L)
        CloudAdvance(
            current.copy(stage = "حفظ الترجمة", progress = 0.97f),
            CloudTranslationResult(
                cues = cues,
                asrMs = 0L,
                translationMs = 0L,
                totalMs = totalMs,
                providers = current.providers.joinToString(" + ").ifBlank { "Whisper + Gemini" } + " → مراجعة دقة",
            ),
        )
    }

    private data class PartSubmission(
        val segments: List<StoredSegment>,
        val pending: PendingAsr?,
        val provider: String,
    )

    private fun submitPart(
        part: CloudAudioPart,
        index: Int,
        onBytes: (Long) -> Unit,
    ): PartSubmission {
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
                    text("Content-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                }

                field("mode", "asr")
                field("device_hash", deviceHash)
                field("provider", provider)
                field("offset_ms", part.offsetMs.toString())
                field("duration_ms", part.durationMs.toString())
                text("--$boundary\r\n")
                text("Content-Disposition: form-data; name=\"audio\"; filename=\"part_${index + 1}.ogg\"\r\n")
                text("Content-Type: audio/ogg\r\n\r\n")

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

    private fun startTranslation(segments: List<StoredSegment>): JSONObject {
        val array = JSONArray()
        segments.forEach { s ->
            array.put(
                JSONObject()
                    .put("id", s.id)
                    .put("start_ms", s.startMs)
                    .put("end_ms", s.endMs)
                    .put("tr", s.text)
            )
        }
        return postJson(
            JSONObject()
                .put("mode", "translate_start")
                .put("device_hash", deviceHash)
                .put("segments", array),
            40_000,
        )
    }

    private fun startReview(
        segments: List<StoredSegment>,
        draft: Map<Int, String>,
    ): JSONObject {
        val source = JSONArray()
        segments.forEach { s ->
            source.put(
                JSONObject()
                    .put("id", s.id)
                    .put("start_ms", s.startMs)
                    .put("end_ms", s.endMs)
                    .put("tr", s.text)
            )
        }
        val draftArray = JSONArray()
        draft.toSortedMap().forEach { (id, ar) ->
            draftArray.put(JSONObject().put("id", id).put("ar", ar))
        }
        return postJson(
            JSONObject()
                .put("mode", "review_start")
                .put("device_hash", deviceHash)
                .put("segments", source)
                .put("draft", draftArray),
            40_000,
        )
    }

    private fun pollTranslation(jobId: String, kind: String): JSONObject = postJson(
        JSONObject()
            .put("mode", "poll")
            .put("kind", kind)
            .put("device_hash", deviceHash)
            .put("job_id", jobId),
        25_000,
    )

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

    private fun parseTranslation(root: JSONObject): Map<Int, String> {
        val array = root.optJSONArray("subtitles") ?: JSONArray()
        return buildMap {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val text = item.optString("ar").trim()
                if (text.isNotBlank()) put(item.getInt("id"), text)
            }
        }
    }

    private fun parseProvider(root: JSONObject): String =
        root.optJSONObject("metrics")?.optString("provider")?.takeIf { it.isNotBlank() }
            ?: root.optString("provider", "")

    private fun normalizeSegments(source: List<StoredSegment>): List<StoredSegment> = source
        .filter { it.text.isNotBlank() && it.endMs > it.startMs }
        .sortedBy { it.startMs }
        .mapIndexed { index, s -> s.copy(id = index) }

    private fun postJson(payload: JSONObject, timeoutMs: Int): JSONObject {
        val connection = openConnection(timeoutMs).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            connection.outputStream.use {
                it.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            return readJson(connection)
        } catch (error: SocketTimeoutException) {
            throw CloudTransientException("خدمة الترجمة تأخرت في الاستجابة. ستتم إعادة المحاولة تلقائيًا.", error)
        } catch (error: UnknownHostException) {
            throw CloudTransientException("تعذر الوصول إلى خدمة الترجمة. تحقق من اتصال الإنترنت.", error)
        } catch (error: IOException) {
            throw CloudTransientException("الاتصال بخدمة الترجمة متوقف مؤقتًا.", error)
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
        internal const val ENDPOINT = "https://abavsspydbpkudhswmzp.supabase.co/functions/v1/movie-translate"
        internal const val PUBLISHABLE_KEY = "sb_publishable_iuZnOH7ye1WITm-xc44TiQ_CNb2d2qB"
        internal const val ANON_JWT = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImFiYXZzc3B5ZGJwa3VkaHN3bXpwIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODgzNjAzODIsImV4cCI6MjEwMzkzNjM4Mn0.uBG_5xHNo760PUq2bZLeUqURo9cqIICTeiHpMh-kYxE"
    }
}
