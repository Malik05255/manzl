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

/** Manages the local Turkish -> Arabic specialist translation model. */
class DirectTranslationModelManager(private val context: Context) {
    companion object {
        private const val MODEL_NAME = "Hy-MT2-1.8B-Q4_K_M.gguf"
        private const val MODEL_URL =
            "https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/resolve/main/Hy-MT2-1.8B-Q4_K_M.gguf?download=true"

        private val LEGACY_MODELS = listOf(
            "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            "qwen3.5-2b-q4_0.gguf",
            "qwen3.5-0.8b-q4_0.gguf",
        )

        // Official Q4_K_M is about 1.13 GB. Bounds only reject interrupted downloads.
        private const val MIN_VALID_BYTES = 1_040L * 1024L * 1024L
        private const val REQUIRED_FREE_BYTES = 1_450L * 1024L * 1024L
    }

    suspend fun ensureModel(onProgress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val modelDir = File(context.filesDir, "models").apply { mkdirs() }
        val model = File(modelDir, MODEL_NAME)
        if (isReady(model)) {
            deleteLegacyModels(modelDir)
            onProgress(1f)
            return@withContext model
        }

        val partial = File(modelDir, "$MODEL_NAME.part")
        val alreadyDownloaded = partial.takeIf { it.isFile }?.length() ?: 0L
        val extraNeeded = (MIN_VALID_BYTES - alreadyDownloaded).coerceAtLeast(0L)
        check(modelDir.usableSpace >= maxOf(extraNeeded, REQUIRED_FREE_BYTES - alreadyDownloaded)) {
            "المساحة الحرة غير كافية لتنزيل نموذج الترجمة التركي ← العربي."
        }

        download(partial, onProgress)
        check(partial.length() >= MIN_VALID_BYTES) {
            "تعذر تنزيل نموذج الترجمة التركي ← العربي كاملًا."
        }

        if (model.exists()) model.delete()
        check(partial.renameTo(model)) { "تعذر تثبيت نموذج الترجمة التركي ← العربي." }
        deleteLegacyModels(modelDir)
        onProgress(1f)
        model
    }

    fun isInstalled(): Boolean = isReady(File(File(context.filesDir, "models"), MODEL_NAME))

    private fun isReady(file: File): Boolean = file.isFile && file.length() >= MIN_VALID_BYTES

    private fun deleteLegacyModels(modelDir: File) {
        LEGACY_MODELS.forEach { name ->
            runCatching { File(modelDir, name).delete() }
            runCatching { File(modelDir, "$name.part").delete() }
        }
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
