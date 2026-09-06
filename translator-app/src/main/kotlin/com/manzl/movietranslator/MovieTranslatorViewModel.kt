package com.manzl.movietranslator

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

data class TranslatorUiState(
    val videoUri: Uri? = null,
    val videoName: String = "",
    val videoDurationMs: Long = 0L,
    val isRunning: Boolean = false,
    val progress: Float = 0f,
    val stage: String = "اختر فيلمًا تركيًا للبدء",
    val error: String? = null,
    val cues: List<SubtitleCue> = emptyList(),
    val srtFile: File? = null,
    val uploadedBytes: Long = 0L,
    val processingMs: Long = 0L,
    val cloudMetrics: String = "",
    val partCount: Int = 0,
    // Kept while the previous local engine remains in-tree as a rollback fallback.
    val modelInstalled: Boolean = false,
)

class MovieTranslatorViewModel(application: Application) : AndroidViewModel(application) {
    val uiState: StateFlow<TranslatorUiState> = CloudMovieTranslationService.state

    fun selectVideo(uri: Uri, displayName: String) {
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        CloudMovieTranslationService.selectVideo(app, uri, displayName)
    }

    fun start() {
        CloudMovieTranslationService.start(getApplication())
    }

    fun cancel() {
        CloudMovieTranslationService.cancel(getApplication())
    }

    fun exportSrt(destination: Uri) {
        val source = uiState.value.srtFile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(destination)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: error("تعذر فتح مكان الحفظ.")
            }.onSuccess {
                CloudMovieTranslationService.stateMutableUpdateForUi("تم حفظ ملف الترجمة العربية")
            }.onFailure { error ->
                CloudMovieTranslationService.stateMutableErrorForUi(error.message ?: "تعذر حفظ ملف الترجمة.")
            }
        }
    }

    fun clearError() = CloudMovieTranslationService.clearError()
}
