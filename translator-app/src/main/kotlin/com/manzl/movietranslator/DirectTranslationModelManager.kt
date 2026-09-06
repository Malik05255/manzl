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
 * Manages the quantized NLLB-200 distilled 600M files used by the fast local translator.
 *
 * The old Hy-MT2 GGUF remains untouched when the phone has enough free space, which gives us an
 * easy rollback path. If storage is tight, the legacy 1.1 GB model is reclaimed automatically.
 */
class DirectTranslationModelManager(private val context: Context) {
    suspend fun ensureModel(onProgress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val modelDir = File(context.filesDir, MODEL_DIR).apply { mkdirs() }
        if (isReady(modelDir)) {
            onProgress(1f)
            return@withContext modelDir
        }

        reclaimLegacyModelIfNeeded(modelDir)
        val missingBytes = MODEL_FILES.sumOf { spec ->
            val final = File(modelDir, spec.name)
            val partial = File(modelDir, "${spec.name}.part")
            if (final.length() >= spec.minBytes) 0L
            else (spec.expectedBytes - partial.length()).coerceAtLeast(0L)
        }
        check(modelDir.usableSpace >= missingBytes + DOWNLOAD_HEADROOM_BYTES) {
            "المساحة الحرة غير كافية لتنزيل مترجم NLLB السريع."
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
            check(partial.length() >= spec.minBytes) {
                "تعذر تنزيل ${spec.displayName} كاملًا."
            }
            if (final.exists()) final.delete()
            check(partial.renameTo(final)) { "تعذر تثبيت ${spec.displayName}." }
            completedExpected += spec.expectedBytes
        }

        check(isReady(modelDir)) { "ملفات مترجم NLLB غير مكتملة." }
        onProgress(1f)
        modelDir
    }

    fun isInstalled(): Boolean = isReady(File(context.filesDir, MODEL_DIR))

    private fun isReady(dir: File): Boolean = MODEL_FILES.all { spec ->
        File(dir, spec.name).length() >= spec.minBytes
    }

    private fun reclaimLegacyModelIfNeeded(modelDir: File) {
        if (modelDir.usableSpace >= MIN_COMFORTABLE_FREE_BYTES) return
        val legacyDir = modelDir.parentFile ?: return
        LEGACY_HY_MT_FILES.forEach { name ->
            runCatching { File(legacyDir, name).delete() }
            runCatching { File(legacyDir, "$name.part").delete() }
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
            connectTimeout = 25_000
            readTimeout = 90_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept-Encoding", "identity")
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
        internal const val MODEL_DIR = "models/nllb-200-distilled-600m"
        private const val BASE =
            "https://huggingface.co/Xenova/nllb-200-distilled-600M/resolve/main"

        private val MODEL_FILES = listOf(
            ModelFile(
                name = "encoder_model_quantized.onnx",
                displayName = "NLLB encoder",
                url = "$BASE/onnx/encoder_model_quantized.onnx?download=true",
                expectedBytes = 419_120_483L,
                minBytes = 390L * 1024L * 1024L,
            ),
            ModelFile(
                name = "decoder_model_quantized.onnx",
                displayName = "NLLB decoder",
                url = "$BASE/onnx/decoder_model_quantized.onnx?download=true",
                expectedBytes = 471_000_000L,
                minBytes = 430L * 1024L * 1024L,
            ),
            ModelFile(
                name = "decoder_with_past_model_quantized.onnx",
                displayName = "NLLB cached decoder",
                url = "$BASE/onnx/decoder_with_past_model_quantized.onnx?download=true",
                expectedBytes = 445_000_000L,
                minBytes = 405L * 1024L * 1024L,
            ),
            ModelFile(
                name = "sentencepiece.bpe.model",
                displayName = "NLLB tokenizer",
                url = "$BASE/sentencepiece.bpe.model?download=true",
                expectedBytes = 4_850_000L,
                minBytes = 4L * 1024L * 1024L,
            ),
        )

        private val LEGACY_HY_MT_FILES = listOf("Hy-MT2-1.8B-Q4_K_M.gguf")
        private const val DOWNLOAD_HEADROOM_BYTES = 160L * 1024L * 1024L
        private const val MIN_COMFORTABLE_FREE_BYTES = 1_650L * 1024L * 1024L
    }
}
