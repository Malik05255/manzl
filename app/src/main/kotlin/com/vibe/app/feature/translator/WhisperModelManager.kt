package com.vibe.app.feature.translator

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Downloads the multilingual Whisper base model once, verifies it, then reuses it offline.
 *
 * The model is intentionally not bundled in the APK: keeping the APK smaller makes installs
 * and updates cheap, while the model remains cached under app-private storage after first use.
 */
object WhisperModelManager {
    private const val MODEL_FILE = "ggml-base.bin"
    private const val PART_FILE = "ggml-base.bin.part"
    private const val VERIFIED_FILE = "ggml-base.bin.verified"
    private const val MODEL_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"
    private const val EXPECTED_SIZE = 147_951_465L
    private const val EXPECTED_SHA256 =
        "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"

    suspend fun ensureModel(
        context: Context,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val modelDir = File(context.filesDir, "translator/models").apply { mkdirs() }
        val model = File(modelDir, MODEL_FILE)
        val verified = File(modelDir, VERIFIED_FILE)

        if (model.length() == EXPECTED_SIZE && verified.readTextOrNull() == EXPECTED_SHA256) {
            onProgress(100)
            return@withContext model
        }

        if (model.exists() && model.length() != EXPECTED_SIZE) {
            model.delete()
            verified.delete()
        }

        val partial = File(modelDir, PART_FILE)
        if (partial.length() > EXPECTED_SIZE) partial.delete()
        downloadWithResume(partial, onProgress)

        check(partial.length() == EXPECTED_SIZE) {
            "Whisper model download is incomplete (${partial.length()} / $EXPECTED_SIZE bytes)"
        }

        val digest = sha256(partial)
        check(digest == EXPECTED_SHA256) {
            partial.delete()
            "Whisper model integrity check failed"
        }

        if (model.exists()) model.delete()
        check(partial.renameTo(model)) { "Unable to finalize Whisper model file" }
        verified.writeText(EXPECTED_SHA256)
        onProgress(100)
        model
    }

    private suspend fun downloadWithResume(partial: File, onProgress: (Int) -> Unit) {
        var existing = partial.length()
        if (existing == EXPECTED_SIZE) {
            onProgress(100)
            return
        }

        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept-Encoding", "identity")
            if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
        }

        try {
            connection.connect()
            val response = connection.responseCode
            check(response == HttpURLConnection.HTTP_OK || response == HttpURLConnection.HTTP_PARTIAL) {
                "Model download failed with HTTP $response"
            }

            // Some CDNs ignore Range. Restart rather than silently appending a second full file.
            if (existing > 0L && response == HttpURLConnection.HTTP_OK) {
                partial.delete()
                existing = 0L
            }

            val total = if (response == HttpURLConnection.HTTP_PARTIAL) {
                existing + connection.contentLengthLong.coerceAtLeast(0L)
            } else {
                connection.contentLengthLong.coerceAtLeast(EXPECTED_SIZE)
            }.takeIf { it > 0L } ?: EXPECTED_SIZE

            RandomAccessFile(partial, "rw").use { output ->
                if (existing == 0L) output.setLength(0L)
                output.seek(existing)
                connection.inputStream.buffered(1024 * 1024).use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    var downloaded = existing
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onProgress(((downloaded * 100L) / total.coerceAtLeast(1L)).toInt().coerceIn(0, 99))
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun File.readTextOrNull(): String? = runCatching {
        if (exists()) readText().trim() else null
    }.getOrNull()
}