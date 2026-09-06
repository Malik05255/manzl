package com.manzl.movietranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class CloudMovieTranslationService : Service() {
    companion object {
        private const val ACTION_START = "com.manzl.movietranslator.cloud.START"
        private const val ACTION_CANCEL = "com.manzl.movietranslator.cloud.CANCEL"
        private const val CHANNEL_ID = "cloud_movie_translation"
        private const val NOTIFICATION_ID = 4201
        private const val COMPLETE_NOTIFICATION_ID = 4202
        private const val TWO_HOURS_MS = 2L * 60L * 60_000L
        private const val MAX_MOVIE_MS = 3L * 60L * 60_000L

        private val _state = MutableStateFlow(TranslatorUiState())
        val state: StateFlow<TranslatorUiState> = _state.asStateFlow()

        @Volatile private var activeService: CloudMovieTranslationService? = null

        fun selectVideo(context: Context, uri: Uri, displayName: String, movieKeyOverride: String? = null) {
            if (_state.value.isRunning) return
            val durationMs = readMovieDurationMs(context, uri)
            val partCount = when {
                durationMs <= 0L -> 0
                durationMs <= TWO_HOURS_MS -> 1
                durationMs <= MAX_MOVIE_MS -> 2
                else -> 0
            }
            val movieKey = movieKeyOverride ?: CloudLibraryClient.movieKey(displayName, durationMs)
            _state.value = TranslatorUiState(
                videoUri = uri,
                videoName = displayName,
                movieKey = movieKey,
                videoDurationMs = durationMs,
                stage = if (durationMs > MAX_MOVIE_MS) "الفيلم أطول من الحد الحالي" else "جاهز للتنفيذ",
                partCount = partCount,
            )
        }

        fun start(context: Context) {
            val current = _state.value
            if (current.videoUri == null || current.isRunning) return
            if (current.videoDurationMs > MAX_MOVIE_MS) {
                _state.update { it.copy(error = "الحد الحالي للفيلم 3 ساعات.") }
                return
            }
            _state.update {
                it.copy(
                    isRunning = true,
                    progress = 0f,
                    stage = "بدء المهمة…",
                    error = null,
                    cues = emptyList(),
                    srtFile = null,
                    uploadedBytes = 0L,
                    processingMs = 0L,
                    cloudMetrics = "",
                )
            }
            runCatching {
                context.applicationContext.startForegroundService(
                    Intent(context.applicationContext, CloudMovieTranslationService::class.java)
                        .setAction(ACTION_START)
                )
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isRunning = false,
                        stage = "تعذر بدء الترجمة",
                        error = error.message ?: "تعذر تشغيل خدمة الترجمة.",
                    )
                }
            }
        }

        fun cancel(context: Context) {
            activeService?.cancelWork() ?: runCatching {
                context.applicationContext.startService(
                    Intent(context.applicationContext, CloudMovieTranslationService::class.java)
                        .setAction(ACTION_CANCEL)
                )
            }
        }

        fun clearError() = _state.update { it.copy(error = null) }
        fun stateMutableUpdateForUi(stage: String) = _state.update { it.copy(stage = stage) }
        fun stateMutableErrorForUi(message: String) = _state.update { it.copy(error = message) }

        private fun readMovieDurationMs(context: Context, uri: Uri): Long = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                retriever.release()
            }
        }.getOrDefault(0L)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var worker: Job? = null
    private var startedAt = 0L
    private var lastNotificationPercent = -1

    override fun onCreate() {
        super.onCreate()
        activeService = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ترجمة الأفلام", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> cancelWork()
            ACTION_START, null -> if (worker?.isActive != true) {
                runCatching {
                    startForeground(NOTIFICATION_ID, notification("تجهيز الصوت…", 2, true))
                    beginTranslation()
                }.onFailure { error ->
                    _state.update { it.copy(isRunning = false, error = error.message ?: "تعذر بدء الترجمة.") }
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun beginTranslation() {
        val snapshot = _state.value
        val uri = snapshot.videoUri ?: return
        startedAt = SystemClock.elapsedRealtime()
        lastNotificationPercent = -1

        worker = scope.launch {
            var parts: List<CloudAudioPart> = emptyList()
            try {
                val durationMs = snapshot.videoDurationMs.takeIf { it > 0L }
                    ?: withContext(Dispatchers.IO) {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(applicationContext, uri)
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        } finally {
                            retriever.release()
                        }
                    }
                check(durationMs in 1..MAX_MOVIE_MS) { "يدعم التطبيق أفلامًا حتى 3 ساعات حاليًا." }

                publish(0.02f, "استخراج الصوت من الفيلم", force = true)
                parts = CloudAudioExtractor(applicationContext).prepare(
                    uri = uri,
                    durationMs = durationMs,
                ) { done, total ->
                    if (total > 1) {
                        val ratio = done.toFloat() / total.coerceAtLeast(1).toFloat()
                        publish(0.02f + ratio * 0.10f, "تجهيز جزء الصوت $done من $total")
                    }
                }
                publish(0.15f, "تم تجهيز الصوت", force = true)

                val bytes = parts.sumOf { it.file.length() }
                _state.update {
                    it.copy(uploadedBytes = bytes, partCount = parts.size, videoDurationMs = durationMs)
                }

                val cloud = CloudTranslationClient(applicationContext).translate(
                    parts = parts,
                    onUploadProgress = { uploadProgress ->
                        publish(0.15f + uploadProgress.coerceIn(0f, 1f) * 0.30f, "رفع الصوت للمنصة")
                    },
                    onStage = { stage, progress -> publish(progress, stage, force = true) },
                )

                publish(0.97f, "حفظ الترجمة", force = true)
                val output = withContext(Dispatchers.IO) {
                    val dir = File(filesDir, "subtitles").apply { mkdirs() }
                    val safeBase = snapshot.videoName
                        .substringBeforeLast('.', snapshot.videoName)
                        .replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
                        .take(80)
                        .ifBlank { "movie" }
                    File(dir, "${safeBase}_ar.srt").apply {
                        writeText(SrtFormatter.format(cloud.cues), Charsets.UTF_8)
                    }
                }

                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val movieKey = snapshot.movieKey.ifBlank {
                    CloudLibraryClient.movieKey(snapshot.videoName, durationMs)
                }
                val srtText = withContext(Dispatchers.IO) { output.readText(Charsets.UTF_8) }

                // Keep the cloud library in sync. Retry briefly because the translation itself is
                // already complete and should not be lost to a transient network failure.
                var synced = false
                repeat(2) { attempt ->
                    if (!synced) {
                        synced = runCatching {
                            CloudLibraryClient(applicationContext).saveTranslation(
                                movieKey = movieKey,
                                movieName = snapshot.videoName,
                                videoUri = uri,
                                durationMs = durationMs,
                                srtText = srtText,
                                cueCount = cloud.cues.size,
                                processingMs = elapsed,
                            )
                        }.isSuccess
                        if (!synced && attempt == 0) delay(700L)
                    }
                }

                val providerText = cloud.providers.ifBlank { "Whisper Large V3 + Gemini" }
                val details = buildString {
                    append(providerText)
                    if (cloud.asrMs > 0L) append(" • استماع ${formatDuration(cloud.asrMs)}")
                    if (cloud.translationMs > 0L) append(" • ترجمة ومراجعة ${formatDuration(cloud.translationMs)}")
                    if (!synced) append(" • الترجمة محفوظة محليًا")
                }
                _state.update {
                    it.copy(
                        isRunning = false,
                        progress = 1f,
                        stage = "اكتملت الترجمة",
                        movieKey = movieKey,
                        cues = cloud.cues,
                        srtFile = output,
                        uploadedBytes = bytes,
                        processingMs = elapsed,
                        cloudMetrics = details,
                        error = null,
                    )
                }
                postCompletionNotification(snapshot.videoName, elapsed)
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(isRunning = false, stage = "تم إيقاف الترجمة", error = null) }
                throw cancelled
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        isRunning = false,
                        stage = "تعذر إكمال الترجمة",
                        error = error.message ?: "حدث خطأ في الترجمة السحابية.",
                    )
                }
            } finally {
                parts.forEach { runCatching { it.file.delete() } }
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf()
            }
        }
    }

    private fun publish(progress: Float, stage: String, force: Boolean = false) {
        val bounded = progress.coerceIn(0f, 1f)
        _state.update { it.copy(isRunning = true, progress = bounded, stage = stage) }
        val percent = (bounded * 100f).toInt().coerceIn(0, 100)
        if (force || percent - lastNotificationPercent >= 4) {
            lastNotificationPercent = percent
            runCatching {
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    notification(stage, percent, false),
                )
            }
        }
    }

    private fun cancelWork() {
        worker?.cancel()
        worker = null
    }

    private fun postCompletionNotification(name: String, elapsed: Long) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(
                COMPLETE_NOTIFICATION_ID,
                notification("جاهز: $name • ${formatDuration(elapsed)}", 100, false),
            )
        }
    }

    private fun notification(text: String, progress: Int, indeterminate: Boolean): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pending = PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_status_translate)
            .setContentTitle("مترجم الأفلام")
            .setContentText(text)
            .setContentIntent(pending)
            .setOnlyAlertOnce(progress < 100)
            .setOngoing(progress < 100)
            .setAutoCancel(progress >= 100)
            .setProgress(100, progress.coerceIn(0, 100), indeterminate)
            .build()
    }

    private fun formatDuration(ms: Long): String {
        val seconds = (ms.coerceAtLeast(0L) + 500L) / 1000L
        return if (seconds < 60L) "$seconds ث" else String.format(
            Locale.US,
            "%d:%02d د",
            seconds / 60L,
            seconds % 60L,
        )
    }

    override fun onDestroy() {
        if (activeService === this) activeService = null
        scope.cancel()
        super.onDestroy()
    }
}
