package com.manzl.movietranslator

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class VoskModelManager(private val context: Context) {
    companion object {
        private const val MODEL_NAME = "vosk-model-small-tr-0.3"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip"
    }

    suspend fun ensureModel(onProgress: (Float) -> Unit): File {
        val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        val modelDir = File(modelsDir, MODEL_NAME)
        if (isReady(modelDir)) {
            onProgress(1f)
            return modelDir
        }

        val zipFile = File(context.cacheDir, "$MODEL_NAME.zip")
        download(zipFile, onProgress)
        if (modelDir.exists()) modelDir.deleteRecursively()
        unzip(zipFile, modelsDir)
        zipFile.delete()

        check(isReady(modelDir)) { "تعذر تجهيز نموذج التعرف على اللغة التركية." }
        onProgress(1f)
        return modelDir
    }

    fun isInstalled(): Boolean = isReady(File(File(context.filesDir, "models"), MODEL_NAME))

    private fun isReady(dir: File): Boolean =
        dir.isDirectory && File(dir, "conf/model.conf").isFile && File(dir, "am/final.mdl").isFile

    private suspend fun download(target: File, onProgress: (Float) -> Unit) {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()

        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            connection.connect()
            check(connection.responseCode in 200..299) {
                "فشل تنزيل نموذج اللغة التركية (${connection.responseCode})."
            }
            val total = connection.contentLengthLong.coerceAtLeast(1L)
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onProgress((downloaded.toDouble() / total).toFloat().coerceIn(0f, 0.98f))
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun unzip(zip: File, destination: File) {
        val rootCanonical = destination.canonicalFile
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                currentCoroutineContext().ensureActive()
                val output = File(destination, entry.name).canonicalFile
                check(output.path.startsWith(rootCanonical.path + File.separator)) {
                    "ملف نموذج غير صالح."
                }
                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    FileOutputStream(output).use { fileOut -> input.copyTo(fileOut, 64 * 1024) }
                }
                input.closeEntry()
                entry = input.nextEntry
            }
        }
    }
}
