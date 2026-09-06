package com.manzl.movietranslator

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

data class TranslatorUiState(
    val videoUri: Uri? = null,
    val videoName: String = "",
    val movieKey: String = "",
    val videoDurationMs: Long = 0L,
    val isRunning: Boolean = false,
    val progress: Float = 0f,
    val stage: String = "جاهز",
    val error: String? = null,
    val cues: List<SubtitleCue> = emptyList(),
    val srtFile: File? = null,
    val uploadedBytes: Long = 0L,
    val processingMs: Long = 0L,
    val cloudMetrics: String = "",
    val partCount: Int = 0,
    val modelInstalled: Boolean = false,
)

class MovieTranslatorViewModel(application: Application) : AndroidViewModel(application) {
    val uiState: StateFlow<TranslatorUiState> = CloudMovieTranslationService.state

    private val libraryClient = CloudLibraryClient(application.applicationContext)
    private val _library = MutableStateFlow<List<CloudMovieItem>>(emptyList())
    val library: StateFlow<List<CloudMovieItem>> = _library.asStateFlow()

    private val _platforms = MutableStateFlow<List<PlatformQuota>>(emptyList())
    val platforms: StateFlow<List<PlatformQuota>> = _platforms.asStateFlow()

    private val _cloudUiError = MutableStateFlow<String?>(null)
    val cloudUiError: StateFlow<String?> = _cloudUiError.asStateFlow()

    init {
        CloudMovieTranslationService.restorePending(application.applicationContext)
        refreshLibrary()
        refreshPlatforms()
        viewModelScope.launch {
            var lastCompletedMovie = ""
            uiState.collectLatest { state ->
                if (!state.isRunning && state.progress >= 1f && state.movieKey.isNotBlank() && state.movieKey != lastCompletedMovie) {
                    lastCompletedMovie = state.movieKey
                    refreshLibrary()
                    refreshPlatforms()
                }
            }
        }
    }

    fun selectVideo(uri: Uri, displayName: String) {
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        CloudMovieTranslationService.selectVideo(app, uri, displayName)
        val snapshot = uiState.value
        if (snapshot.movieKey.isNotBlank() && snapshot.videoDurationMs > 0L) {
            viewModelScope.launch {
                runCatching {
                    libraryClient.upsertPath(
                        movieKey = snapshot.movieKey,
                        movieName = snapshot.videoName,
                        videoUri = uri,
                        durationMs = snapshot.videoDurationMs,
                    )
                }.onFailure { _cloudUiError.value = it.message }
                refreshLibrary()
            }
        }
    }

    fun relinkMovie(movie: CloudMovieItem, uri: Uri) {
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModelScope.launch {
            runCatching {
                libraryClient.upsertPath(
                    movieKey = movie.movieKey,
                    movieName = movie.movieName,
                    videoUri = uri,
                    durationMs = movie.durationMs,
                )
            }.onSuccess { refreshLibrary() }
                .onFailure { _cloudUiError.value = it.message ?: "تعذر تعديل مسار الفيلم." }
        }
    }

    fun start() = CloudMovieTranslationService.start(getApplication())
    fun cancel() = CloudMovieTranslationService.cancel(getApplication())

    fun refreshLibrary() {
        viewModelScope.launch {
            runCatching { libraryClient.listMovies() }
                .onSuccess { _library.value = it }
                .onFailure { _cloudUiError.value = it.message }
        }
    }

    fun refreshPlatforms() {
        viewModelScope.launch {
            runCatching { libraryClient.platformStatus() }
                .onSuccess { _platforms.value = it }
                .onFailure { _cloudUiError.value = it.message }
        }
    }

    fun deleteTranslation(movie: CloudMovieItem) {
        viewModelScope.launch {
            runCatching { libraryClient.deleteTranslation(movie.movieKey) }
                .onSuccess { refreshLibrary() }
                .onFailure { _cloudUiError.value = it.message ?: "تعذر حذف الترجمة." }
        }
    }

    fun prepareLibrarySubtitle(movie: CloudMovieItem): File? = libraryClient.prepareSubtitleFile(movie)

    fun exportCloudSrt(movie: CloudMovieItem, destination: Uri) {
        val text = movie.srtText ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(destination)?.use { output ->
                    output.write(text.toByteArray(Charsets.UTF_8))
                } ?: error("تعذر فتح مكان الحفظ.")
            }.onFailure { _cloudUiError.value = it.message ?: "تعذر تنزيل الترجمة." }
        }
    }

    fun exportSrt(destination: Uri) {
        val source = uiState.value.srtFile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(destination)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: error("تعذر فتح مكان الحفظ.")
            }.onFailure { error ->
                CloudMovieTranslationService.stateMutableErrorForUi(error.message ?: "تعذر حفظ ملف الترجمة.")
            }
        }
    }

    fun clearError() = CloudMovieTranslationService.clearError()
    fun clearCloudUiError() { _cloudUiError.value = null }
}
