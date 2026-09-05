package com.manzl.movietranslator

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val modelManager = VoskModelManager(application)
    private val transcriber = MediaAudioTranscriber(application)
    private val translator = TurkishArabicTranslator()
    private var processingJob: Job? = null

    private val _uiState = MutableStateFlow(
        TranslatorUiState(modelInstalled = modelManager.isInstalled())
    )
    val uiState: StateFlow<TranslatorUiState> = _uiState.asStateFlow()

    fun selectVideo(uri: Uri, displayName: String) {
        val resolver = getApplication<Application>().contentResolver
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        _uiState.update {
            TranslatorUiState(
                videoUri = uri,
                videoName = displayName,
                stage = "جاهز للترجمة: $displayName",
                modelInstalled = modelManager.isInstalled(),
            )
        }
    }

    fun start() {
        val uri = _uiState.value.videoUri ?: return
        if (_uiState.value.isRunning) return
        processingJob = viewModelScope.launch {
            _uiState.update { it.copy(isRunning = true, progress = 0f, error = null, cues = emptyList(), srtFile = null) }
            try {
                _uiState.update { it.copy(stage = "تجهيز نموذج الاستماع التركي…") }
                val modelDir = withContext(Dispatchers.IO) {
                    modelManager.ensureModel { p ->
                        _uiState.update { state -> state.copy(progress = p * 0.12f) }
                    }
                }
                _uiState.update { it.copy(modelInstalled = true, progress = 0.12f, stage = "تجهيز نموذج الترجمة العربية…") }

                translator.ensureModel()
                _uiState.update { it.copy(progress = 0.18f, stage = "أستمع إلى الفيلم وأحوّل الكلام التركي إلى نص…") }

                val sourceCues = transcriber.transcribe(uri, modelDir) { p ->
                    _uiState.update { state ->
                        state.copy(progress = 0.18f + (p * 0.62f))
                    }
                }
                check(sourceCues.isNotEmpty()) {
                    "لم أستطع استخراج حوار تركي واضح من المسار الصوتي."
                }

                _uiState.update { it.copy(progress = 0.80f, stage = "أترجم الحوار إلى العربية محليًا…") }
                val translated = translator.translate(sourceCues) { done, total ->
                    val ratio = done.toFloat() / total.coerceAtLeast(1).toFloat()
                    _uiState.update { state -> state.copy(progress = 0.80f + ratio * 0.18f) }
                }

                val output = withContext(Dispatchers.IO) {
                    val dir = File(getApplication<Application>().filesDir, "subtitles").apply { mkdirs() }
                    val file = File(dir, "arabic_${System.currentTimeMillis()}.srt")
                    file.writeText(SrtFormatter.format(translated), Charsets.UTF_8)
                    file
                }
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        progress = 1f,
                        stage = "تمت الترجمة — الفيلم جاهز للمشاهدة بالترجمة العربية",
                        cues = translated,
                        srtFile = output,
                    )
                }
            } catch (cancelled: CancellationException) {
                _uiState.update { it.copy(isRunning = false, stage = "تم إيقاف المعالجة") }
                throw cancelled
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        error = error.message ?: "حدث خطأ أثناء الترجمة.",
                        stage = "تعذر إكمال الترجمة",
                    )
                }
            }
        }
    }

    fun cancel() {
        processingJob?.cancel()
    }

    fun exportSrt(destination: Uri) {
        val source = _uiState.value.srtFile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(destination)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: error("تعذر فتح مكان الحفظ.")
            }.onSuccess {
                _uiState.update { it.copy(stage = "تم حفظ ملف الترجمة العربية") }
            }.onFailure { error ->
                _uiState.update { it.copy(error = error.message ?: "تعذر حفظ ملف الترجمة.") }
            }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    override fun onCleared() {
        processingJob?.cancel()
        translator.close()
        super.onCleared()
    }
}
