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
 * Downloads the compact int8 SMaLL-100 translation runtime.
 *
 * SMaLL-100 is distilled from M2M-100 but has only three decoder layers. The Android runtime needs
 * two ONNX graphs plus the original SentencePiece/vocab files used by the local Kotlin tokenizer.
 */
class DirectTranslationModelManager(private val context: Context) {
    suspend fun ensureModel(onProgress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val modelDir = File(context.filesDir, MODEL_DIR).apply { mkdirs() }
        if (isReady(modelDir)) {
            onProgress(1f)
            return@withContext modelDir
        }

        reclaimObsoleteModels()
        val missingBytes = MODEL_FILES.sumOf { spec ->
            val final = File(modelDir, spec.name)
            val partial = File(modelDir, "${spec.name}.part")
            if (final.length() >= spec.minBytes) 0L
            else (spec.expectedBytes - partial.length()).coerceAtLeast(0L)
        }
        check(modelDir.usableSpace >= missingBytes + DOWNLOAD_HEADROOM_BYTES) {
            "المساحة الحرة غير كافية لتنزيل مترجم SMaLL-100 السريع."
        }

        val totalExpected = MODEL_FILES.sumOf { it.expectedBytes }.toDouble()
        var completedExpected = 0L
        MODEL_FILES.forEach { spec ->
            currentCoroutineContext().ensureActive()
            val final = File(modelDir, spec.name)
            if (final.length() >= spec.minBytes) {
                completedExpected += spec.expectedBytes
                onProgress((completedExpected / totalExpected).toFloat().coerceIn(0f, 0.99f))
                return@forEach
            }

            val partial = File(modelDir, "${spec.name}.part")
            download(spec, partial) { currentBytes ->
                val weighted = completedExpected + currentBytes.coerceAtMost(spec.expectedBytes)
                onProgress((weighted / totalExpected).toFloat().coerceIn(0f, 0.99f))
            }
            check(partial.length() >= spec.minBytes) { "تعذر تنزيل ${spec.displayName} كاملًا." }
            if (final.exists()) final.delete()
            check(partial.renameTo(final)) { "تعذر تثبيت ${spec.displayName}." }
            completedExpected += spec.expectedBytes
        }

        check(isReady(modelDir)) { "ملفات مترجم SMaLL-100 غير مكتملة." }
        onProgress(1f)
        modelDir
    }

    fun isInstalled(): Boolean = isReady(File(context.filesDir, MODEL_DIR))

    private fun isReady(dir: File): Boolean = MODEL_FILES.all { spec ->
        File(dir, spec.name).length() >= spec.minBytes
    }

    private fun reclaimObsoleteModels() {
        val modelsDir = File(context.filesDir, "models")
        val obsolete = listOf(
            File(modelsDir, "m2m100-418m-tr-ar"),
            File(modelsDir, "nllb-200-distilled-600m"),
            File(modelsDir, "Hy-MT2-1.8B-Q4_K_M.gguf"),
            File(modelsDir, "Hy-MT2-1.8B-Q4_K_M.gguf.part"),
            File(modelsDir, "mlkit-tr-ar.ready"),
            File(context.filesDir, "Translation/HY-MT"),
        )
        obsolete.forEach { file ->
            runCatching {
                if (file.isDirectory) file.deleteRecursively() else file.delete()
            }
        }
    }

    private suspend fun download(
        spec: ModelFile,
        target: File,
        onBytes: (Long) -> Unit,
    ) {
        target.parentFile?.mkdirs()
        val existing = target.takeIf { it.isFile }?.length() ?: 0L
        val connection = (URL(spec.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "Manzl-MovieTranslator/1.0")
            if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
        }

        try {
            connection.connect()
            check(connection.responseCode in 200..299) {
                "فشل تنزيل ${spec.displayName} (${connection.responseCode})."
            }
            val resumed = connection.responseCode == HttpURLConnection.HTTP_PARTIAL && existing > 0L
            val startAt = if (resumed) existing else 0L
            if (!resumed && existing > 0L) target.delete()
            onBytes(startAt)

            FileOutputStream(target, resumed).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(512 * 1024)
                    var downloaded = startAt
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onBytes(downloaded)
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private data class ModelFile(
        val name: String,
        val displayName: String,
        val url: String,
        val expectedBytes: Long,
        val minBytes: Long,
    )

    companion object {
        internal const val MODEL_DIR = "models/small100-tr-ar"
        private const val BASE = "https://huggingface.co/casawolice/small100-onnx/resolve/main"

        private val MODEL_FILES = listOf(
            ModelFile(
                name = "encoder_model.onnx",
                displayName = "SMaLL-100 encoder",
                url = "$BASE/onnx/encoder_model.onnx?download=true",
                expectedBytes = 287_000_000L,
                minBytes = 250L * 1024L * 1024L,
            ),
            ModelFile(
                name = "decoder_model_merged.onnx",
                displayName = "SMaLL-100 cached decoder",
                url = "$BASE/onnx/decoder_model_merged.onnx?download=true",
                expectedBytes = 322_000_000L,
                minBytes = 285L * 1024L * 1024L,
            ),
            ModelFile(
                name = "sentencepiece.bpe.model",
                displayName = "SMaLL-100 SentencePiece",
                url = "$BASE/sentencepiece.bpe.model?download=true",
                expectedBytes = 2_420_000L,
                minBytes = 2L * 1024L * 1024L,
            ),
            ModelFile(
                name = "vocab.json",
                displayName = "SMaLL-100 vocabulary",
                url = "$BASE/vocab.json?download=true",
                expectedBytes = 3_710_000L,
                minBytes = 3L * 1024L * 1024L,
            ),
        )

        private const val DOWNLOAD_HEADROOM_BYTES = 128L * 1024L * 1024L
    }
}
