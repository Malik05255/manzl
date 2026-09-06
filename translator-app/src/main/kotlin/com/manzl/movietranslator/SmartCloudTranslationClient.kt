package com.manzl.movietranslator

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.UUID
import kotlin.math.abs

internal data class SmartCloudAdvance(
    val job: BackgroundCloudJob,
    val result: CloudTranslationResult? = null,
    val nextDelayMs: Long = 2_500L,
)

/**
 * Smart mode keeps the proven cloud transport but changes orchestration:
 * balanced parallel ASR, Azure-first translation on the backend, and selective Groq review.
 */
internal class SmartCloudTranslationClient(context: Context) {
    private val deviceHash = CloudIdentity.deviceHash(context.applicationContext)

    suspend fun submitForBackground(
        parts: List<CloudAudioPart>,
        onUploadProgress: (Float) -> Unit = {},
    ): CloudSubmission = withContext(Dispatchers.IO) {
        require(parts.size in 1..3)
        val totalBytes = parts.sumOf { it.file.length() }.coerceAtLeast(1L)
        val sentByPart = LongArray(parts.size)
        val progressLock = Any()

        val results = coroutineScope {
            parts.mapIndexed { index, part ->
                async {
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
            }.awaitAll()
        }

        onUploadProgress(1f)
        CloudSubmission(
            segments = results.flatMap { it.segments },
            pendingAsr = results.mapNotNull { it.pending },
            providers = results.map { it.provider }.filter { it.isNotBlank() }.distinct(),
        )
    }

    suspend fun advance(job: BackgroundCloudJob): SmartCloudAdvance = withContext(Dispatchers.IO) {
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
                stage = if (remaining.isEmpty()) "اكتمل فهم الحوار التركي" else "فهم الحوار التركي",
                progress = if (remaining.isEmpty()) 0.68f else 0.48f + ratio * 0.18f,
            )
            if (remaining.isNotEmpty()) return@withContext SmartCloudAdvance(current)
        }

        val ordered = normalizeSegments(current.segments)
        check(ordered.isNotEmpty()) { "لم تتعرف المنصة على حوار تركي واضح." }
        if (ordered != current.segments) current = current.copy(segments = ordered)

        if (current.draft.isEmpty() && current.translationJobId == null) {
            val started = startTranslation(ordered)
            val providers = addProvider(current.providers, parseProvider(started))
            when (started.optString("status", "completed")) {
                "completed" -> current = current.copy(
                    draft = parseTranslation(started),
                    providers = providers,
                    stage = "اكتملت الترجمة الأساسية",
                    progress = 0.84f,
                )
                "failed", "cancelled" -> {
                    if (current.translationAttempts >= MAX_TRANSLATION_ATTEMPTS) {
                        error(started.optString("message", "فشلت الترجمة السحابية."))
                    }
                    return@withContext SmartCloudAdvance(
                        current.copy(
                            translationJobId = null,
                            translationAttempts = current.translationAttempts + 1,
                            providers = providers,
                            stage = "إعادة تشغيل الترجمة",
                            progress = 0.70f,
                        ),
                        nextDelayMs = 2_000L,
                    )
                }
                else -> {
                    current = current.copy(
                        translationJobId = started.getString("job_id"),
                        translationAttempts = current.translationAttempts + 1,
                        providers = providers,
                        stage = "الترجمة العربية",
                        progress = 0.70f,
                    )
                    return@withContext SmartCloudAdvance(current)
                }
            }
        }

        if (current.translationJobId != null) {
            val root = pollTranslation(current.translationJobId)
            val providers = addProvider(current.providers, parseProvider(root))
            when (root.optString("status")) {
                "completed" -> current = current.copy(
                    translationJobId = null,
                    draft = parseTranslation(root),
                    providers = providers,
                    stage = "اكتملت الترجمة الأساسية",
                    progress = 0.84f,
                )
                "failed", "cancelled" -> {
                    if (current.translationAttempts >= MAX_TRANSLATION_ATTEMPTS) {
                        error(root.optString("message", "فشلت الترجمة السحابية."))
                    }
                    return@withContext SmartCloudAdvance(
                        current.copy(
                            translationJobId = null,
                            providers = providers,
                            stage = "إعادة تشغيل الترجمة",
                            progress = 0.70f,
                        ),
                        nextDelayMs = 2_000L,
                    )
                }
                else -> return@withContext SmartCloudAdvance(
                    current.copy(providers = providers, stage = "الترجمة العربية", progress = 0.76f)
                )
            }
        }

        check(current.draft.size == ordered.size) {
            "الترجمة لم تُرجع جميع الأسطر (${current.draft.size}/${ordered.size})."
        }

        // A long Gemini review job from an older APK must never keep smart mode stuck.
        if (current.reviewJobId != null) current = current.copy(reviewJobId = null)

        val reviewQueue = buildReviewQueue(ordered, current.draft)
        if (current.reviewCursor < reviewQueue.size) {
            val targetIds = reviewQueue.drop(current.reviewCursor).take(REVIEW_CHUNK_SIZE)
            val contextIds = buildSet {
                targetIds.forEach { id ->
                    add(id)
                    if (id > 0) add(id - 1)
                    if (id + 1 < ordered.size) add(id + 1)
                }
            }
            val contextSegments = ordered.filter { it.id in contextIds }
            val root = reviewChunk(contextSegments, current.draft, targetIds)
            val corrections = parseTranslation(root).filterKeys { it in targetIds }
            val providers = addProvider(current.providers, parseProvider(root))
            val nextCursor = (current.reviewCursor + targetIds.size).coerceAtMost(reviewQueue.size)
            val ratio = nextCursor.toFloat() / reviewQueue.size.coerceAtLeast(1).toFloat()
            current = current.copy(
                reviewed = current.reviewed + corrections,
                reviewCursor = nextCursor,
                providers = providers,
                stage = "تحسين الترجمة",
                progress = 0.84f + ratio * 0.11f,
            )
            return@withContext SmartCloudAdvance(
                current,
                nextDelayMs = reviewDelayMs(contextSegments, current.draft),
            )
        }

        val finalMap = current.draft.toMutableMap().apply { putAll(current.reviewed) }
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
        SmartCloudAdvance(
            current.copy(stage = "تسليم الترجمة", progress = 0.97f),
            CloudTranslationResult(
                cues = cues,
                asrMs = 0L,
                translationMs = 0L,
                totalMs = totalMs,
                providers = current.providers.distinct().joinToString(" + ").ifBlank { "Cloud smart mode" },
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
        totalParts: Int,
        onBytes: (Long) -> Unit,
    ): PartSubmission {
        val boundary = "----Manzl${UUID.randomUUID()}"
        val provider = if (totalParts >= 3 && index == totalParts - 1) "groq_turbo" else "groq"
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
            55_000,
        )
    }

    private fun reviewChunk(
        segments: List<StoredSegment>,
        draft: Map<Int, String>,
        targetIds: List<Int>,
    ): JSONObject {
        val source = JSONArray()
        val draftArray = JSONArray()
        segments.forEach { s ->
            source.put(
                JSONObject()
                    .put("id", s.id)
                    .put("start_ms", s.startMs)
                    .put("end_ms", s.endMs)
                    .put("tr", s.text)
            )
            draftArray.put(JSONObject().put("id", s.id).put("ar", draft[s.id].orEmpty()))
        }
        return postJson(
            JSONObject()
                .put("mode", "review_chunk")
                .put("device_hash", deviceHash)
                .put("segments", source)
                .put("draft", draftArray)
                .put("target_ids", JSONArray(targetIds)),
            45_000,
        )
    }

    private fun pollTranslation(jobId: String): JSONObject = postJson(
        JSONObject()
            .put("mode", "poll")
            .put("kind", "translate")
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
                val item = array.optJSONObject(i) ?: continue
                val text = item.optString("ar").trim()
                val id = item.optInt("id", -1)
                if (id >= 0 && text.isNotBlank()) put(id, text)
            }
        }
    }

    private fun parseProvider(root: JSONObject): String =
        root.optJSONObject("metrics")?.optString("provider")?.takeIf { it.isNotBlank() }
            ?: root.optString("provider", "")

    private fun addProvider(existing: List<String>, provider: String): List<String> =
        if (provider.isBlank()) existing else (existing + provider).distinct()

    private fun normalizeSegments(source: List<StoredSegment>): List<StoredSegment> {
        val cleaned = source
            .filter { it.text.isNotBlank() && it.endMs > it.startMs }
            .sortedBy { it.startMs }
        val deduplicated = mutableListOf<StoredSegment>()
        cleaned.forEach { segment ->
            val normalized = normalizeDialogue(segment.text)
            val duplicate = deduplicated.takeLast(5).any { previous ->
                abs(previous.startMs - segment.startMs) <= OVERLAP_DEDUPE_WINDOW_MS &&
                    normalizeDialogue(previous.text) == normalized
            }
            if (!duplicate) deduplicated += segment
        }
        return deduplicated.mapIndexed { index, s -> s.copy(id = index) }
    }

    private fun buildReviewQueue(
        segments: List<StoredSegment>,
        draft: Map<Int, String>,
    ): List<Int> {
        if (segments.isEmpty()) return emptyList()
        val candidates = segments.filter { segment ->
            val source = segment.text.trim()
            val sourceLower = " ${source.lowercase()} "
            val translated = draft[segment.id].orEmpty().trim()
            val words = source.split(Regex("\\s+")).count { it.isNotBlank() }
            val lengthRatio = translated.length.toDouble() / source.length.coerceAtLeast(1).toDouble()
            val latinInArabic = translated.count { it in 'A'..'Z' || it in 'a'..'z' }
            val hasContextSensitivePhrase = CONTEXT_TERMS.any { sourceLower.contains(it) }
            words <= 4 ||
                source.length >= 72 ||
                source.contains('?') || source.contains('!') ||
                hasContextSensitivePhrase ||
                latinInArabic >= 3 ||
                lengthRatio < 0.32 || lengthRatio > 2.6
        }.map { it.id }.distinct()

        val maxReviewed = (segments.size * MAX_REVIEW_RATIO).toInt().coerceAtLeast(1)
        return candidates.take(maxReviewed)
    }

    private fun reviewDelayMs(
        contextSegments: List<StoredSegment>,
        draft: Map<Int, String>,
    ): Long {
        val chars = contextSegments.sumOf { it.text.length + draft[it.id].orEmpty().length }
        val estimatedTokens = (chars / 3.4).toLong().coerceAtLeast(250L)
        val targetTokensPerMinute = 7_000L
        return ((estimatedTokens * 60_000L) / targetTokensPerMinute)
            .coerceIn(6_000L, 35_000L)
    }

    private fun normalizeDialogue(value: String): String = value
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

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
        } catch (error: CloudTransientException) {
            throw error
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
        private const val MAX_TRANSLATION_ATTEMPTS = 3
        private const val REVIEW_CHUNK_SIZE = 80
        private const val MAX_REVIEW_RATIO = 0.40
        private const val OVERLAP_DEDUPE_WINDOW_MS = 3_500L

        private val CONTEXT_TERMS = listOf(
            " lan ", " abi ", " abla ", " hadi ", " vallahi ", " yani ", " işte ",
            " ulan ", " aman ", " tamam ", " hayır ", " neden ", " nasıl ", " neyse ",
            " boşver ", " hadi be ", " allah aşkına ", " ne olur ", " yok artık ",
        )
    }
}
