package com.manzl.movietranslator

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class TranslatorUiState(
    val videoUri: Uri? = null,
    val videoName: String = "",
    val isRunning: Boolean = false,
    val progress: Float = 0f,
    val stage: String = "اختر فيلمًا تركيًا للبدء",
    val error: String? = null,
    val cues: List<SubtitleCue> = emptyList(),
    val srtFile: File? = null,
    val modelInstalled: Boolean = false,
)

class MovieTranslatorViewModel(application: Application) : AndroidViewModel(application) {
    val uiState: StateFlow<TranslatorUiState> = MovieTranslationService.state

    init {
        MovieTranslationService.refreshModelStatus(application)
    }

    fun selectVideo(uri: Uri, displayName: String) {
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        MovieTranslationService.selectVideo(app, uri, displayName)
    }

    fun start() {
        MovieTranslationService.start(getApplication())
    }

    fun cancel() {
        MovieTranslationService.cancel(getApplication())
    }

    fun exportSrt(destination: Uri) {
        val source = uiState.value.srtFile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(destination)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: error("تعذر فتح مكان الحفظ.")
            }.onSuccess {
                MovieTranslationService.stateMutableUpdateForUi("تم حفظ ملف الترجمة العربية")
            }.onFailure { error ->
                MovieTranslationService.stateMutableErrorForUi(error.message ?: "تعذر حفظ ملف الترجمة.")
            }
        }
    }

    fun clearError() = MovieTranslationService.clearError()
}
