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
 * Manages the local Turkish -> Arabic language model.
 *
 * The previous 0.8B checkpoint was too small for reliable film dialogue. The 2B Q4_0 checkpoint is
 * still practical on modern ARM64 phones, but gives the translator materially more capacity for
 * context, idioms and natural Arabic. It is downloaded once and reused for every movie.
 */
class DirectTranslationModelManager(private val context: Context) {
    companion object {
        private const val MODEL_NAME = "qwen3.5-2b-q4_0.gguf"
        private const val MODEL_URL =
            "https://huggingface.co/bartowski/Qwen_Qwen3.5-2B-GGUF/resolve/main/Qwen_Qwen3.5-2B-Q4_0.gguf?download=true"
        private const val LEGACY_MODEL_NAME = "qwen3.5-0.8b-q4_0.gguf"

        // The selected Q4_0 file is about 1.2 GB. These bounds catch interrupted downloads without
        // hashing the full checkpoint on every launch.
        private const val MIN_VALID_BYTES = 1_100L * 1024L * 1024L
        private const val REQUIRED_FREE_BYTES = 1_500L * 1024L * 1024L
    }

    suspend fun ensureModel(onProgress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val modelDir = File(context.filesDir, "models").apply { mkdirs() }
        val model = File(modelDir, MODEL_NAME)
        if (isReady(model)) {
            deleteLegacyModel(modelDir)
            onProgress(1f)
            return@withContext model
        }

        val partial = File(modelDir, "$MODEL_NAME.part")
        val alreadyDownloaded = partial.takeIf { it.isFile }?.length() ?: 0L
        val extraNeeded = (MIN_VALID_BYTES - alreadyDownloaded).coerceAtLeast(0L)
        check(modelDir.usableSpace >= maxOf(extraNeeded, REQUIRED_FREE_BYTES - alreadyDownloaded)) {
            "المساحة الحرة غير كافية لتنزيل نموذج الترجمة العربية عالي الجودة."
        }

        download(partial, onProgress)
        check(partial.length() >= MIN_VALID_BYTES) {
            "تعذر تنزيل نموذج الترجمة العربية عالي الجودة كاملًا."
        }

        if (model.exists()) model.delete()
        check(partial.renameTo(model)) {
            "تعذر تثبيت نموذج الترجمة العربية عالي الجودة."
        }
        deleteLegacyModel(modelDir)
        onProgress(1f)
        model
    }

    fun isInstalled(): Boolean = isReady(File(File(context.filesDir, "models"), MODEL_NAME))

    private fun isReady(file: File): Boolean = file.isFile && file.length() >= MIN_VALID_BYTES

    private fun deleteLegacyModel(modelDir: File) {
        runCatching { File(modelDir, LEGACY_MODEL_NAME).delete() }
        runCatching { File(modelDir, "$LEGACY_MODEL_NAME.part").delete() }
    }

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
