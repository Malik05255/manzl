package com.manzl.movietranslator

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages the compact local language model used for direct Turkish -> Arabic subtitle translation.
 * The model is downloaded once, resumed when possible, and kept outside the APK.
 */
class DirectTranslationModelManager(private val context: Context) {
    companion object {
        private const val MODEL_NAME = "qwen3.5-0.8b-q4_0.gguf"
        private const val MODEL_URL =
            "https://huggingface.co/ggml-org/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_0.gguf?download=true"

        // Official Q4_0 is about 563 MB. A lower bound catches interrupted/corrupt files
        // without hashing hundreds of MB on every launch.
        private const val MIN_VALID_BYTES = 500L * 1024L * 1024L
        private const val REQUIRED_FREE_BYTES = 700L * 1024L * 1024L
    }

    suspend fun ensureModel(onProgress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val modelDir = File(context.filesDir, "models").apply { mkdirs() }
        val model = File(modelDir, MODEL_NAME)
        if (isReady(model)) {
            onProgress(1f)
            return@withContext model
        }

        val partial = File(modelDir, "$MODEL_NAME.part")
        val alreadyDownloaded = partial.takeIf { it.isFile }?.length() ?: 0L
        val extraNeeded = (MIN_VALID_BYTES - alreadyDownloaded).coerceAtLeast(0L)
        check(modelDir.usableSpace >= maxOf(extraNeeded, REQUIRED_FREE_BYTES - alreadyDownloaded)) {
            "المساحة الحرة غير كافية لتنزيل نموذج الترجمة العربية المباشرة."
        }

        download(partial, onProgress)
        check(partial.length() >= MIN_VALID_BYTES) {
            "تعذر تنزيل نموذج الترجمة العربية المباشرة كاملًا."
        }

        if (model.exists()) model.delete()
        check(partial.renameTo(model)) {
            "تعذر تثبيت نموذج الترجمة العربية المباشرة."
        }
        onProgress(1f)
        model
    }

    fun isInstalled(): Boolean = isReady(File(File(context.filesDir, "models"), MODEL_NAME))

    private fun isReady(file: File): Boolean = file.isFile && file.length() >= MIN_VALID_BYTES

    private suspend fun download(target: File, onProgress: (Float) -> Unit) {
        target.parentFile?.mkdirs()
        val existing = target.takeIf { it.isFile }?.length() ?: 0L
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept-Encoding", "identity")
            if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
        }

        try {
            connection.connect()
            check(connection.responseCode in 200..299) {
                "فشل تنزيل نموذج الترجمة (${connection.responseCode})."
            }

            val resumed = connection.responseCode == HttpURLConnection.HTTP_PARTIAL && existing > 0L
            val startAt = if (resumed) existing else 0L
            if (!resumed && existing > 0L) target.delete()

            val contentLength = connection.contentLengthLong.coerceAtLeast(1L)
            val total = if (resumed) startAt + contentLength else contentLength
            FileOutputStream(target, resumed).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var downloaded = startAt
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onProgress((downloaded.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 0.99f))
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
