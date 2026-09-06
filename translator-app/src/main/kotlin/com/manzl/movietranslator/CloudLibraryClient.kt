package com.manzl.movietranslator

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal data class CloudMovieItem(
    val movieKey: String,
    val movieName: String,
    val videoUri: String?,
    val durationMs: Long,
    val srtText: String?,
    val cueCount: Int,
    val processingMs: Long,
    val updatedAt: String,
    val localAvailable: Boolean = false,
)

internal data class PlatformQuota(
    val id: String,
    val title: String,
    val remainingPercent: Int,
    val resetAtEpochMs: Long,
    val detail: String,
)

internal class CloudLibraryClient(private val context: Context) {
    private val deviceHash = CloudIdentity.deviceHash(context.applicationContext)

    suspend fun listMovies(): List<CloudMovieItem> = withContext(Dispatchers.IO) {
        val root = post(JSONObject().put("mode", "library_list").put("device_hash", deviceHash))
        val array = root.optJSONArray("movies") ?: JSONArray()
        buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val uriText = item.optString("video_uri").takeIf { it.isNotBlank() }
                add(
                    CloudMovieItem(
                        movieKey = item.getString("movie_key"),
                        movieName = item.getString("movie_name"),
                        videoUri = uriText,
                        durationMs = item.optLong("duration_ms", 0L),
                        srtText = item.optString("srt_text").takeIf { it.isNotBlank() },
                        cueCount = item.optInt("cue_count", 0),
                        processingMs = item.optLong("processing_ms", 0L),
                        updatedAt = item.optString("updated_at"),
                        localAvailable = uriText?.let(::isReadable) == true,
                    )
                )
            }
        }
    }

    suspend fun upsertPath(movieKey: String, movieName: String, videoUri: Uri, durationMs: Long) = withContext(Dispatchers.IO) {
        post(
            JSONObject()
                .put("mode", "library_upsert_path")
                .put("device_hash", deviceHash)
                .put("movie_key", movieKey)
                .put("movie_name", movieName)
                .put("video_uri", videoUri.toString())
                .put("duration_ms", durationMs)
        )
        Unit
    }

    suspend fun saveTranslation(
        movieKey: String,
        movieName: String,
        videoUri: Uri,
        durationMs: Long,
        srtText: String,
        cueCount: Int,
        processingMs: Long,
    ) = withContext(Dispatchers.IO) {
        post(
            JSONObject()
                .put("mode", "library_save_translation")
                .put("device_hash", deviceHash)
                .put("movie_key", movieKey)
                .put("movie_name", movieName)
                .put("video_uri", videoUri.toString())
                .put("duration_ms", durationMs)
                .put("srt_text", srtText)
                .put("cue_count", cueCount)
                .put("processing_ms", processingMs)
        )
        Unit
    }

    suspend fun recordUsage(durationMs: Long, providers: String) = withContext(Dispatchers.IO) {
        val totalSeconds = (durationMs.coerceAtLeast(0L) / 1000L).coerceAtMost(10_800L)
        val lower = providers.lowercase()
        val hasGroq = "whisper" in lower || "groq" in lower
        val hasGeminiAudio = "audio" in lower && "gemini" in lower
        val groqSeconds = when {
            hasGroq && hasGeminiAudio && totalSeconds > 7_200L -> 7_200L
            hasGroq -> totalSeconds
            else -> 0L
        }
        val geminiAudioSeconds = when {
            hasGeminiAudio -> (totalSeconds - groqSeconds).coerceAtLeast(0L)
            hasGroq -> 0L
            else -> totalSeconds
        }
        post(
            JSONObject()
                .put("mode", "usage_record")
                .put("device_hash", deviceHash)
                .put("groq_audio_seconds", groqSeconds)
                .put("gemini_audio_seconds", geminiAudioSeconds)
                .put("gemini_requests", 2)
        )
        Unit
    }

    suspend fun deleteTranslation(movieKey: String) = withContext(Dispatchers.IO) {
        post(JSONObject().put("mode", "library_delete_translation").put("device_hash", deviceHash).put("movie_key", movieKey))
        Unit
    }

    suspend fun platformStatus(): List<PlatformQuota> = withContext(Dispatchers.IO) {
        val root = post(JSONObject().put("mode", "platform_status").put("device_hash", deviceHash))
        val array = root.optJSONArray("platforms") ?: JSONArray()
        buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(
                    PlatformQuota(
                        id = item.getString("id"),
                        title = item.getString("title"),
                        remainingPercent = item.optInt("remaining_percent", 100).coerceIn(0, 100),
                        resetAtEpochMs = item.optLong("reset_at_ms", 0L),
                        detail = item.optString("detail"),
                    )
                )
            }
        }
    }

    fun prepareSubtitleFile(movie: CloudMovieItem): java.io.File? {
        val text = movie.srtText?.takeIf { it.isNotBlank() } ?: return null
        val dir = java.io.File(context.cacheDir, "cloud_library_subtitles").apply { mkdirs() }
        return java.io.File(dir, "${movie.movieKey}_ar.srt").apply { writeText(text, Charsets.UTF_8) }
    }

    private fun isReadable(value: String): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(Uri.parse(value), "r")?.use { true } ?: false
    }.getOrDefault(false)

    private fun post(payload: JSONObject, timeoutMs: Int = 25_000): JSONObject {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = timeoutMs
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${CloudTranslationClient.ANON_JWT}")
            setRequestProperty("apikey", CloudTranslationClient.PUBLISHABLE_KEY)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val root = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
            if (status !in 200..299) {
                val message = root.optString("message").takeIf { it.isNotBlank() }
                    ?: root.optString("error").takeIf { it.isNotBlank() }
                    ?: "تعذر الاتصال بمكتبة المنصة ($status)."
                error(message)
            }
            root
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val ENDPOINT = "https://lbgcjmsqqhrpceijdqng.supabase.co/functions/v1/movie-library"

        fun movieKey(name: String, durationMs: Long): String {
            val normalized = name.trim().lowercase() + "|" + durationMs
            val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(32)
        }
    }
}
