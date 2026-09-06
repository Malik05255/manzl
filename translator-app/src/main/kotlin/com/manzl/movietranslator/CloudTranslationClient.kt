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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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

private data class GeminiUploadedFile(
    val name: String,
    val uri: String,
    val mimeType: String,
)

/**
 * Personal-app cloud client.
 *
 * The movie itself never leaves the phone. The app uploads only the compressed audio directly to
 * Groq/Gemini over HTTPS. The user's API keys are read from SecureApiKeyStore (Android Keystore)
 * and never depend on Supabase or any developer-owned backend.
 */
internal class CloudTranslationClient(context: Context) {
    private val keyStore = SecureApiKeyStore(context.applicationContext)

    suspend fun translate(
        parts: List<CloudAudioPart>,
        onUploadProgress: (Float) -> Unit = {},
        onStage: (String, Float) -> Unit = { _, _ -> },
    ): CloudTranslationResult = withContext(Dispatchers.IO) {
        require(parts.size in 1..2)
        parts.forEach { require(it.file.isFile && it.file.length() > 0L) }

        val groqKey = keyStore.requireGroqKey()
        val geminiKey = keyStore.requireGeminiKey()
        val totalStarted = System.currentTimeMillis()
        val totalFileBytes = parts.sumOf { it.file.length() }.coerceAtLeast(1L)
        val uploadedBytes = AtomicLong(0L)

        onStage("إرسال الصوت مباشرة لخدمات الذكاء الاصطناعي…", 0.18f)
        val asrStarted = System.currentTimeMillis()
        val asrResults = coroutineScope {
            parts.mapIndexed { index, part ->
                async(Dispatchers.IO) {
                    if (index == 0) {
                        transcribeWithGroq(
                            part = part,
                            apiKey = groqKey,
                            totalFileBytes = totalFileBytes,
                            uploadedBytes = uploadedBytes,
                            onUploadProgress = onUploadProgress,
                        )
                    } else {
                        transcribeWithGemini(
                            part = part,
                            apiKey = geminiKey,
                            totalFileBytes = totalFileBytes,
                            uploadedBytes = uploadedBytes,
                            onUploadProgress = onUploadProgress,
                            onStage = onStage,
                        )
                    }
                }
            }.awaitAll()
        }
        val asrMs = System.currentTimeMillis() - asrStarted

        val ordered = asrResults
            .flatMap { it.segments }
            .sortedBy { it.startMs }
            .mapIndexed { index, segment -> segment.copy(id = index) }
        check(ordered.isNotEmpty()) { "لم تتعرف السحابة على حوار تركي في هذا المقطع." }

        onStage("Gemini يصيغ الترجمة العربية كسياق فيلم واحد…", 0.74f)
        val translationStarted = System.currentTimeMillis()
        val translated = translateWholeTranscript(ordered, geminiKey, onStage)
        val translationMs = System.currentTimeMillis() - translationStarted

        val arabicById = translated.associate { it.first to it.second }
        val cues = ordered.mapNotNull { segment ->
            val arabic = arabicById[segment.id]?.trim().orEmpty()
            if (arabic.isBlank()) null else SubtitleCue(
                startMs = segment.startMs,
                endMs = segment.endMs,
                sourceText = segment.text,
                translatedText = arabic,
                confidence = 1f,
            )
        }
        check(cues.size == ordered.size) {
            "الترجمة السحابية لم تُرجع جميع أسطر الحوار (${cues.size}/${ordered.size})."
        }

        CloudTranslationResult(
            cues = cues,
            asrMs = asrMs,
            translationMs = translationMs,
            totalMs = System.currentTimeMillis() - totalStarted,
            providers = asrResults.joinToString(" + ") { it.provider } + " → Gemini 3.8 Flash",
        )
    }

    private suspend fun transcribeWithGroq(
        part: CloudAudioPart,
        apiKey: String,
        totalFileBytes: Long,
        uploadedBytes: AtomicLong,
        onUploadProgress: (Float) -> Unit,
    ): AsrResult {
        val boundary = "----Manzl${UUID.randomUUID()}"
        val connection = open(GROQ_URL, 95_000).apply {
            requestMethod = "POST"
            doOutput = true
            setChunkedStreamingMode(256 * 1024)
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
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
                field("model", GROQ_MODEL)
                field("language", "tr")
                field("response_format", "verbose_json")
                field("temperature", "0")
                field("timestamp_granularities[]", "segment")
                text("--$boundary\r\n")
                text("Content-Disposition: form-data; name=\"file\"; filename=\"audio.ogg\"\r\n")
                text("Content-Type: audio/ogg\r\n\r\n")
                copyWithProgress(part.file, output, totalFileBytes, uploadedBytes, onUploadProgress)
                text("\r\n--$boundary--\r\n")
                output.flush()
            }

            val root = readJson(connection, "Groq")
            val source = root.optJSONArray("segments") ?: JSONArray()
            val segments = buildList {
                for (i in 0 until source.length()) {
                    val item = source.getJSONObject(i)
                    val text = cleanTurkish(item.optString("text"))
                    if (text.isBlank()) continue
                    val start = part.offsetMs + (item.optDouble("start", 0.0) * 1000.0).toLong().coerceAtLeast(0L)
                    val end = part.offsetMs + (item.optDouble("end", 0.0) * 1000.0).toLong().coerceAtLeast(1L)
                    add(RemoteSegment(0, start, end.coerceAtLeast(start + 120L), text))
                }
            }
            check(segments.isNotEmpty()) { "Groq لم يتعرف على كلام تركي واضح." }
            return AsrResult(segments.sortedBy { it.startMs }, GROQ_MODEL)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun transcribeWithGemini(
        part: CloudAudioPart,
        apiKey: String,
        totalFileBytes: Long,
        uploadedBytes: AtomicLong,
        onUploadProgress: (Float) -> Unit,
        onStage: (String, Float) -> Unit,
    ): AsrResult {
        onStage("Gemini السحابي يتعرف على الجزء الثاني…", 0.52f)
        val uploaded = uploadGeminiFile(
            file = part.file,
            apiKey = apiKey,
            totalFileBytes = totalFileBytes,
            uploadedBytes = uploadedBytes,
            onUploadProgress = onUploadProgress,
        )
        try {
            waitForGeminiFile(uploaded.name, apiKey)
            val interaction = createGeminiAsrInteraction(uploaded, apiKey)
            val completed = if (interaction.optString("status") == "completed") {
                interaction
            } else {
                val id = interaction.optString("id").takeIf { it.isNotBlank() }
                    ?: error("Gemini لم يُرجع رقم مهمة الاستماع.")
                pollGeminiInteraction(id, apiKey, "الاستماع", onStage, 0.52f, 0.66f)
            }
            return AsrResult(parseGeminiAsr(completed, part.offsetMs), "$GEMINI_MODEL-audio")
        } finally {
            deleteGeminiFile(uploaded.name, apiKey)
        }
    }

    private suspend fun translateWholeTranscript(
        segments: List<RemoteSegment>,
        apiKey: String,
        onStage: (String, Float) -> Unit,
    ): List<Pair<Int, String>> {
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put(
                "subtitles",
                JSONObject()
                    .put("type", "array")
                    .put("items", JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject()
                            .put("id", JSONObject().put("type", "integer"))
                            .put("ar", JSONObject().put("type", "string")))
                        .put("required", JSONArray().put("id").put("ar")))
            ))
            .put("required", JSONArray().put("subtitles"))

        val source = JSONArray()
        segments.forEach { source.put(JSONObject().put("id", it.id).put("tr", it.text)) }
        val prompt = """
            You are the senior Arabic subtitle translator for a Turkish feature film.
            Read the ENTIRE ordered Turkish dialogue first so character relationships, names,
            pronouns, jokes, threats, idioms, callbacks and tone stay consistent across the movie.

            Translate every segment into polished, natural Modern Standard Arabic that feels
            professionally subtitled, not machine-translated.

            Rules:
            - Preserve the exact intended meaning and emotional tone.
            - Prefer natural cinematic Arabic over literal Turkish word order.
            - Preserve names and recurring terminology consistently.
            - Translate Turkish idioms by meaning, not word-for-word.
            - Keep concise subtitle phrasing without deleting information.
            - Never omit, merge, reorder, summarize, censor, explain or add dialogue.
            - Return every id exactly once and only the Arabic for that id.
            - Do not output timestamps; the app preserves the verified ASR timeline.

            Full movie dialogue:
            $source
        """.trimIndent()

        val body = JSONObject()
            .put("model", GEMINI_MODEL)
            .put("input", prompt)
            .put("response_format", JSONObject()
                .put("type", "text")
                .put("mime_type", "application/json")
                .put("schema", schema))
            .put("generation_config", JSONObject()
                .put("thinking_level", "low")
                .put("max_output_tokens", 65536))
            .put("background", true)
            .put("store", true)

        val started = createGeminiInteraction(body, apiKey)
        val completed = if (started.optString("status") == "completed") {
            started
        } else {
            val id = started.optString("id").takeIf { it.isNotBlank() }
                ?: error("Gemini لم يُرجع رقم مهمة الترجمة.")
            pollGeminiInteraction(id, apiKey, "الترجمة", onStage, 0.76f, 0.89f)
        }
        return parseTranslation(completed)
    }

    private fun createGeminiAsrInteraction(uploaded: GeminiUploadedFile, apiKey: String): JSONObject {
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put(
                "segments",
                JSONObject()
                    .put("type", "array")
                    .put("items", JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject()
                            .put("start_ms", JSONObject().put("type", "integer"))
                            .put("end_ms", JSONObject().put("type", "integer"))
                            .put("tr", JSONObject().put("type", "string")))
                        .put("required", JSONArray().put("start_ms").put("end_ms").put("tr")))
            ))
            .put("required", JSONArray().put("segments"))

        val input = JSONArray()
            .put(JSONObject().put("type", "text").put("text", """
                Transcribe this Turkish movie audio accurately. Return subtitle-ready Turkish
                dialogue segments with start_ms and end_ms relative to THIS audio file only.
                Turkish transcription only; do not translate. Preserve every intelligible spoken
                line, names, slang, particles and short replies. Do not invent dialogue from music
                or sound effects. Keep segments naturally sized for subtitles, normally 1-8 seconds.
                Return only the requested structured JSON.
            """.trimIndent()))
            .put(JSONObject()
                .put("type", "audio")
                .put("uri", uploaded.uri)
                .put("mime_type", uploaded.mimeType))

        val body = JSONObject()
            .put("model", GEMINI_MODEL)
            .put("input", input)
            .put("response_format", JSONObject()
                .put("type", "text")
                .put("mime_type", "application/json")
                .put("schema", schema))
            .put("generation_config", JSONObject()
                .put("thinking_level", "low")
                .put("max_output_tokens", 65536))
            .put("background", true)
            .put("store", true)
        return createGeminiInteraction(body, apiKey)
    }

    private fun createGeminiInteraction(body: JSONObject, apiKey: String): JSONObject {
        val connection = open(GEMINI_INTERACTIONS_URL, 40_000).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey)
            setRequestProperty("Api-Revision", API_REVISION)
        }
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            readJson(connection, "Gemini")
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun pollGeminiInteraction(
        id: String,
        apiKey: String,
        stageName: String,
        onStage: (String, Float) -> Unit,
        fromProgress: Float,
        toProgress: Float,
    ): JSONObject {
        repeat(MAX_POLL_ATTEMPTS) { attempt ->
            currentCoroutineContext().ensureActive()
            val encodedId = URLEncoder.encode(id, Charsets.UTF_8.name()).replace("+", "%20")
            val connection = open("$GEMINI_INTERACTIONS_URL/$encodedId", 25_000).apply {
                setRequestProperty("x-goog-api-key", apiKey)
                setRequestProperty("Api-Revision", API_REVISION)
                setRequestProperty("Accept", "application/json")
            }
            val root = try {
                readJson(connection, "Gemini")
            } finally {
                connection.disconnect()
            }
            when (root.optString("status", "completed")) {
                "completed" -> return root
                "failed", "cancelled" -> error("فشلت مهمة Gemini في مرحلة $stageName.")
            }
            val fraction = attempt.coerceAtMost(60) / 60f
            onStage(
                if (stageName == "الترجمة") "Gemini يصيغ الترجمة العربية كسياق فيلم واحد…" else "Gemini السحابي يتعرف على الحوار…",
                fromProgress + (toProgress - fromProgress) * fraction,
            )
            delay(POLL_DELAY_MS)
        }
        error("استغرقت مرحلة $stageName وقتًا أطول من المتوقع.")
    }

    private fun uploadGeminiFile(
        file: File,
        apiKey: String,
        totalFileBytes: Long,
        uploadedBytes: AtomicLong,
        onUploadProgress: (Float) -> Unit,
    ): GeminiUploadedFile {
        val start = open(GEMINI_UPLOAD_URL, 30_000).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("x-goog-api-key", apiKey)
            setRequestProperty("X-Goog-Upload-Protocol", "resumable")
            setRequestProperty("X-Goog-Upload-Command", "start")
            setRequestProperty("X-Goog-Upload-Header-Content-Length", file.length().toString())
            setRequestProperty("X-Goog-Upload-Header-Content-Type", "audio/ogg")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        val uploadUrl = try {
            start.outputStream.use {
                it.write(JSONObject().put("file", JSONObject().put("display_name", "manzl-movie-audio")).toString().toByteArray(Charsets.UTF_8))
            }
            val status = start.responseCode
            if (status !in 200..299) readJson(start, "Gemini")
            start.getHeaderField("x-goog-upload-url") ?: error("Gemini لم يُرجع رابط رفع الصوت.")
        } finally {
            start.disconnect()
        }

        val upload = open(uploadUrl, 100_000).apply {
            requestMethod = "POST"
            doOutput = true
            setFixedLengthStreamingMode(file.length())
            setRequestProperty("Content-Length", file.length().toString())
            setRequestProperty("X-Goog-Upload-Offset", "0")
            setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
            setRequestProperty("Content-Type", "audio/ogg")
        }
        return try {
            upload.outputStream.use { raw ->
                val output = BufferedOutputStream(raw, 256 * 1024)
                copyWithProgress(file, output, totalFileBytes, uploadedBytes, onUploadProgress)
                output.flush()
            }
            val root = readJson(upload, "Gemini")
            val info = root.optJSONObject("file") ?: error("Gemini لم يُرجع بيانات ملف الصوت.")
            GeminiUploadedFile(
                name = info.getString("name"),
                uri = info.getString("uri"),
                mimeType = info.optString("mimeType", info.optString("mime_type", "audio/ogg")),
            )
        } finally {
            upload.disconnect()
        }
    }

    private suspend fun waitForGeminiFile(name: String, apiKey: String) {
        repeat(40) {
            currentCoroutineContext().ensureActive()
            val connection = open("$GEMINI_API_BASE/$name", 15_000).apply {
                setRequestProperty("x-goog-api-key", apiKey)
                setRequestProperty("Accept", "application/json")
            }
            val root = try {
                readJson(connection, "Gemini")
            } finally {
                connection.disconnect()
            }
            when (root.optString("state", "ACTIVE")) {
                "ACTIVE", "STATE_UNSPECIFIED" -> return
                "FAILED" -> error("تعذر تجهيز الصوت لدى Gemini.")
            }
            delay(500L)
        }
        error("تأخر تجهيز ملف الصوت لدى Gemini.")
    }

    private fun deleteGeminiFile(name: String, apiKey: String) {
        if (!name.startsWith("files/")) return
        runCatching {
            val connection = open("$GEMINI_API_BASE/$name", 15_000).apply {
                requestMethod = "DELETE"
                setRequestProperty("x-goog-api-key", apiKey)
            }
            try {
                connection.responseCode
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun parseGeminiAsr(interaction: JSONObject, offsetMs: Long): List<RemoteSegment> {
        val parsed = JSONObject(extractInteractionText(interaction))
        val source = parsed.optJSONArray("segments") ?: JSONArray()
        val result = buildList {
            for (i in 0 until source.length()) {
                val item = source.getJSONObject(i)
                val text = cleanTurkish(item.optString("tr"))
                if (text.isBlank()) continue
                val start = offsetMs + item.optLong("start_ms", 0L).coerceAtLeast(0L)
                val end = offsetMs + item.optLong("end_ms", 1L).coerceAtLeast(1L)
                add(RemoteSegment(0, start, end.coerceAtLeast(start + 120L), text))
            }
        }
        check(result.isNotEmpty()) { "Gemini لم يتعرف على كلام تركي واضح." }
        return result.sortedBy { it.startMs }
    }

    private fun parseTranslation(interaction: JSONObject): List<Pair<Int, String>> {
        val parsed = JSONObject(extractInteractionText(interaction))
        val source = parsed.optJSONArray("subtitles") ?: JSONArray()
        return buildList {
            for (i in 0 until source.length()) {
                val item = source.getJSONObject(i)
                val arabic = cleanArabic(item.optString("ar"))
                if (arabic.isNotBlank()) add(item.getInt("id") to arabic)
            }
        }
    }

    private fun extractInteractionText(interaction: JSONObject): String {
        interaction.optString("output_text").takeIf { it.isNotBlank() }?.let { return it.trim() }
        val steps = interaction.optJSONArray("steps") ?: JSONArray()
        for (i in steps.length() - 1 downTo 0) {
            val step = steps.optJSONObject(i) ?: continue
            if (step.optString("type") != "model_output") continue
            val content = step.optJSONArray("content") ?: continue
            val text = StringBuilder()
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") == "text") text.append(part.optString("text"))
            }
            if (text.isNotBlank()) return text.toString().trim()
        }
        error("Gemini لم يُرجع نتيجة قابلة للقراءة.")
    }

    private suspend fun copyWithProgress(
        file: File,
        output: BufferedOutputStream,
        totalFileBytes: Long,
        uploadedBytes: AtomicLong,
        onUploadProgress: (Float) -> Unit,
    ) {
        file.inputStream().buffered(256 * 1024).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                val now = uploadedBytes.addAndGet(read.toLong())
                onUploadProgress((now.toDouble() / totalFileBytes.toDouble()).toFloat().coerceIn(0f, 1f))
            }
        }
    }

    private fun open(url: String, readTimeoutMs: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = readTimeoutMs
            useCaches = false
        }

    private fun readJson(connection: HttpURLConnection, provider: String): JSONObject {
        val status = connection.responseCode
        val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            val parsed = runCatching { JSONObject(body) }.getOrNull()
            val serverMessage = parsed?.optJSONObject("error")?.optString("message")
                ?.takeIf { it.isNotBlank() }
                ?: parsed?.optString("message")?.takeIf { it.isNotBlank() }
                ?: parsed?.optString("error")?.takeIf { it.isNotBlank() }
            error(serverMessage ?: "فشل اتصال $provider ($status).")
        }
        return if (body.isBlank()) JSONObject() else JSONObject(body)
    }

    private fun cleanTurkish(value: String): String =
        value.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()

    private fun cleanArabic(value: String): String = value
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .replace(",", "،")
        .replace(";", "؛")
        .replace("?", "؟")
        .trim()

    companion object {
        private const val GROQ_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        private const val GROQ_MODEL = "whisper-large-v3"
        private const val GEMINI_MODEL = "gemini-3.8-flash"
        private const val GEMINI_INTERACTIONS_URL = "https://generativelanguage.googleapis.com/v1beta/interactions"
        private const val GEMINI_UPLOAD_URL = "https://generativelanguage.googleapis.com/upload/v1beta/files"
        private const val GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta"
        private const val API_REVISION = "2026-05-20"
        private const val POLL_DELAY_MS = 2_000L
        private const val MAX_POLL_ATTEMPTS = 450
    }
}
