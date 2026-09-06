package com.manzl.movietranslator

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Compatibility facade for the lightweight ML Kit Turkish -> Arabic translation models.
 *
 * Older builds downloaded ~1.3 GB of NLLB ONNX files through this class. The new path uses
 * ML Kit's small on-device language packs instead. The class name is kept so the service/UI do
 * not need a migration just to prepare the translation engine.
 */
class DirectTranslationModelManager(private val context: Context) {
    suspend fun ensureModel(onProgress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        removeLegacyHeavyModels()
        onProgress(0.05f)

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.TURKISH)
            .setTargetLanguage(TranslateLanguage.ARABIC)
            .build()
        val client = Translation.getClient(options)

        try {
            currentCoroutineContext().ensureActive()
            // Intentionally do not require Wi-Fi: the language packs are small and the app must be
            // able to finish first-time setup on mobile data as well.
            val conditions = DownloadConditions.Builder().build()
            Tasks.await(client.downloadModelIfNeeded(conditions))
            currentCoroutineContext().ensureActive()

            val marker = markerFile()
            marker.parentFile?.mkdirs()
            marker.writeText(MARKER_VERSION)
            onProgress(1f)
            marker
        } finally {
            client.close()
        }
    }

    /**
     * Fast UI hint only. The translator still asks ML Kit to verify/download its packs when setup
     * runs, so a stale marker cannot permanently break translation.
     */
    fun isInstalled(): Boolean = markerFile().let { file ->
        file.isFile && runCatching { file.readText().trim() == MARKER_VERSION }.getOrDefault(false)
    }

    private fun markerFile(): File = File(File(context.filesDir, "models"), MARKER_NAME)

    private fun removeLegacyHeavyModels() {
        val models = File(context.filesDir, "models")
        val obsolete = listOf(
            File(models, "nllb-200-distilled-600m"),
            File(models, "Hy-MT2-1.8B-Q4_K_M.gguf"),
            File(models, "Hy-MT2-1.8B-Q4_K_M.gguf.part"),
            File(context.filesDir, "Translation/HY-MT"),
        )
        obsolete.forEach { file ->
            runCatching {
                if (file.isDirectory) file.deleteRecursively() else file.delete()
            }
        }
    }

    companion object {
        private const val MARKER_NAME = "mlkit-tr-ar.ready"
        private const val MARKER_VERSION = "mlkit-translate-17.0.3-v1"
    }
}
