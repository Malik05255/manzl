package com.manzl.movietranslator

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Last-resort translation path used only after the normal whole-movie provider has failed
 * repeatedly. It keeps the complete ordered Turkish transcript as context on every request,
 * translates a small target set, persists the completed Arabic lines in BackgroundCloudJob,
 * and resumes from the first missing line after process death, network loss, or provider throttling.
 */
internal class ResumableTranslationFallbackClient(context: Context) {
    private val appContext = context.applicationContext
    private val deviceHash = CloudIdentity.deviceHash(appContext)

    suspend fun advance(job: BackgroundCloudJob): CloudAdvance = withContext(Dispatchers.IO) {
        val ordered = job.segments
            .filter { it.text.isNotBlank() && it.endMs > it.startMs }
            .sortedBy { it.startMs }
        check(ordered.isNotEmpty()) { "لم تتعرف المنصة على حوار تركي واضح." }

        val existing = job.draft.filterKeys { id -> ordered.any { it.id == id } }
        val missing = ordered.filter { it.id !in existing }.take(CHUNK_SIZE)
        if (missing.isEmpty()) {
            return@withContext CloudAdvance(
                job = job.copy(
                    translationJobId = null,
                    translationAttempts = FALLBACK_MARKER,
                    stage = "اكتملت الترجمة الأساسية",
                    progress = 0.84f,
                    providers = addMarker(job.providers),
                ),
                nextDelayMs = 250L,
            )
        }

        val root = translateChunk(ordered, missing.map { it.id })
        val translated = parseTranslation(root)
        val missingIds = missing.map { it.id }.toSet()
        check(translated.keys.containsAll(missingIds)) {
            "لم تُرجع خدمة الترجمة جميع أسطر الدفعة. ستتم إعادة المحاولة من نفس النقطة."
        }

        val mergedDraft = existing + translated.filterKeys { it in missingIds }
        val ratio = mergedDraft.size.toFloat() / ordered.size.coerceAtLeast(1).toFloat()
        val provider = parseProvider(root)
        val providers = addMarker(
            if (provider.isBlank()) job.providers else (job.providers + provider).distinct()
        )
        val finished = mergedDraft.size >= ordered.size

        CloudAdvance(
            job = job.copy(
                translationJobId = null,
                translationAttempts = FALLBACK_MARKER,
                draft = mergedDraft,
                providers = providers,
                stage = if (finished) "اكتملت الترجمة الأساسية" else "استكمال الترجمة من آخر نقطة",
                progress = if (finished) 0.84f else 0.70f + ratio * 0.14f,
            ),
            nextDelayMs = if (finished) 250L else 2_500L,
        )
    }

    private fun translateChunk(
        allSegments: List<StoredSegment>,
        targetIds: List<Int>,
    ): JSONObject {
        val segments = JSONArray()
        allSegments.forEach { segment ->
            segments.put(
                JSONObject()
                    .put("id", segment.id)
                    .put("start_ms", segment.startMs)
                    .put("end_ms", segment.endMs)
                    .put("tr", segment.text)
            )
        }

        return postJson(
            JSONObject()
                .put("mode", "translate_chunk")
                .put("device_hash", deviceHash)
                .put("segments", segments)
                .put("target_ids", JSONArray(targetIds)),
            55_000,
        )
    }

    private fun parseTranslation(root: JSONObject): Map<Int, String> {
        val array = root.optJSONArray("subtitles") ?: JSONArray()
        return buildMap {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optInt("id", -1)
                val text = item.optString("ar").trim()
                if (id >= 0 && text.isNotBlank()) put(id, text)
            }
        }
    }

    private fun parseProvider(root: JSONObject): String =
        root.optJSONObject("metrics")?.optString("provider")?.takeIf { it.isNotBlank() }
            ?: root.optString("provider", "")

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
            throw CloudTransientException(
                "خدمة الترجمة تأخرت في الاستجابة. ستتم إعادة المحاولة من نفس النقطة تلقائيًا.",
                error,
            )
        } catch (error: UnknownHostException) {
            throw CloudTransientException("تعذر الوصول إلى خدمة الترجمة. تحقق من اتصال الإنترنت.", error)
        } catch (error: IOException) {
            throw CloudTransientException("الاتصال بخدمة الترجمة متوقف مؤقتًا.", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(timeoutMs: Int): HttpURLConnection =
        (URL(FALLBACK_ENDPOINT).openConnection() as HttpURLConnection).apply {
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
        private const val CHUNK_SIZE = 55
        private const val FALLBACK_MARKER = 100
        private const val PROVIDER_MARKER = "resumable-full-context"
        private const val FALLBACK_ENDPOINT =
            "https://abavsspydbpkudhswmzp.supabase.co/functions/v1/movie-translate-chunk"

        fun isActive(job: BackgroundCloudJob): Boolean =
            job.providers.any { it == PROVIDER_MARKER } &&
                job.draft.size < job.segments.size

        fun canRecover(job: BackgroundCloudJob, error: Throwable): Boolean {
            if (error is CloudTransientException || job.pendingAsr.isNotEmpty()) return false
            if (job.translationAttempts >= 3) return true

            val message = generateSequence(error as Throwable?) { it.cause }
                .mapNotNull { it.message }
                .joinToString(" ")
            return message.contains("المسار الاحتياطي") ||
                message.contains("فشلت الترجمة السحابية") ||
                message.contains("لم تُرجع جميع الأسطر") ||
                message.contains("الترجمة لم تُرجع جميع الأسطر")
        }

        private fun addMarker(providers: List<String>): List<String> =
            if (providers.contains(PROVIDER_MARKER)) providers else providers + PROVIDER_MARKER
    }
}
