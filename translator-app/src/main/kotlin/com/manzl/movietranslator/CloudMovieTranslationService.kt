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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CloudMovieTranslationService : Service() {
    companion object {
        private const val ACTION_START = "com.manzl.movietranslator.cloud.START"
        private const val ACTION_CANCEL = "com.manzl.movietranslator.cloud.CANCEL"
        private const val ACTION_PAUSE = "com.manzl.movietranslator.cloud.PAUSE"
        private const val ACTION_RESUME = "com.manzl.movietranslator.cloud.RESUME"
        private const val CHANNEL_ID = "cloud_movie_translation"
        private const val NOTIFICATION_ID = 4201
        private const val TWO_HOURS_MS = 2L * 60L * 60_000L
        private const val MAX_MOVIE_MS = 3L * 60L * 60_000L
        private const val CONTROL_PREFS = "cloud_translation_control"
        private const val KEY_PAUSED = "paused"

        private val _state = MutableStateFlow(TranslatorUiState())
        val state: StateFlow<TranslatorUiState> = _state.asStateFlow()

        @Volatile private var activeService: CloudMovieTranslationService? = null

        fun selectVideo(context: Context, uri: Uri, displayName: String, movieKeyOverride: String? = null) {
            if (_state.value.isRunning) return
            setPauseRequested(context, false)
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
                stage = if (durationMs > MAX_MOVIE_MS) "الفيلم أطول من الحد الحالي" else "جاهز للترجمة",
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
            setPauseRequested(context, false)
            CloudCompletionWorker.cancelAll(context)
            CloudJobStore(context.applicationContext).clear()
            _state.update {
                it.copy(
                    isRunning = true,
                    isPaused = false,
                    progress = 0f,
                    stage = "بدء الترجمة",
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
                    it.copy(isRunning = false, isPaused = false, stage = "تعذر بدء الترجمة", error = error.message ?: "تعذر تشغيل خدمة الترجمة.")
                }
            }
        }

        fun pause(context: Context) {
            val current = _state.value
            if (!current.isRunning || current.isPaused) return
            setPauseRequested(context, true)
            activeService?.requestPause() ?: runCatching {
                context.applicationContext.startService(
                    Intent(context.applicationContext, CloudMovieTranslationService::class.java)
                        .setAction(ACTION_PAUSE)
                )
            }
            _state.update { it.copy(isRunning = true, isPaused = true, stage = "متوقف مؤقتًا", error = null) }
        }

        fun resume(context: Context) {
            val current = _state.value
            if (!current.isPaused && !isPauseRequested(context)) return
            setPauseRequested(context, false)
            activeService?.requestResume() ?: run {
                val pending = CloudJobStore(context.applicationContext).load()
                _state.update {
                    it.copy(
                        isRunning = true,
                        isPaused = false,
                        stage = if (pending != null) "استمرار الترجمة" else "استمرار رفع الصوت",
                        error = null,
                    )
                }
                if (pending != null) {
                    CloudCompletionWorker.schedule(context, 150L)
                } else if (_state.value.videoUri != null) {
                    runCatching {
                        context.applicationContext.startForegroundService(
                            Intent(context.applicationContext, CloudMovieTranslationService::class.java)
                                .setAction(ACTION_RESUME)
                        )
                    }
                }
            }
        }

        fun cancel(context: Context) {
            setPauseRequested(context, false)
            CloudCompletionWorker.cancelAll(context)
            CloudJobStore(context.applicationContext).clear()
            activeService?.cancelWork() ?: runCatching {
                context.applicationContext.startService(
                    Intent(context.applicationContext, CloudMovieTranslationService::class.java)
                        .setAction(ACTION_CANCEL)
                )
            }
            _state.update { it.copy(isRunning = false, isPaused = false, stage = "تم إيقاف الترجمة", error = null) }
        }

        fun restorePending(context: Context) {
            val restored = CloudJobStore(context.applicationContext).restoreUiState() ?: return
            if (!_state.value.isRunning && _state.value.srtFile == null) {
                _state.value = restored.copy(
                    isPaused = isPauseRequested(context),
                    stage = if (isPauseRequested(context)) "متوقف مؤقتًا" else restored.stage,
                )
            }
        }

        internal fun isPauseRequested(context: Context): Boolean =
            context.applicationContext
                .getSharedPreferences(CONTROL_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_PAUSED, false)

        private fun setPauseRequested(context: Context, paused: Boolean) {
            context.applicationContext
                .getSharedPreferences(CONTROL_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PAUSED, paused)
                .apply()
        }

        internal fun restoreBackgroundState(context: Context, job: BackgroundCloudJob) {
            val paused = isPauseRequested(context)
            val current = _state.value
            if (current.movieKey == job.movieKey && current.isRunning && current.isPaused == paused) return
            _state.value = TranslatorUiState(
                videoUri = runCatching { Uri.parse(job.videoUri) }.getOrNull(),
                videoName = job.movieName,
                movieKey = job.movieKey,
                videoDurationMs = job.durationMs,
                isRunning = true,
                isPaused = paused,
                progress = job.progress,
                stage = if (paused) "متوقف مؤقتًا" else job.stage,
                uploadedBytes = job.uploadedBytes,
                partCount = if (job.durationMs > TWO_HOURS_MS) 2 else 1,
            )
        }

        internal fun markBackgroundPaused(context: Context, job: BackgroundCloudJob) {
            restoreBackgroundState(context, job)
            _state.update { it.copy(isRunning = true, isPaused = true, stage = "متوقف مؤقتًا", error = null) }
        }

        internal fun updateBackgroundProgress(context: Context, job: BackgroundCloudJob) {
            restoreBackgroundState(context, job)
            val paused = isPauseRequested(context)
            _state.update {
                it.copy(
                    isRunning = true,
                    isPaused = paused,
                    progress = job.progress.coerceIn(0f, 1f),
                    stage = if (paused) "متوقف مؤقتًا" else job.stage,
                    error = null,
                    uploadedBytes = job.uploadedBytes,
                )
            }
        }

        internal fun completeBackground(
            context: Context,
            job: BackgroundCloudJob,
            cloud: CloudTranslationResult,
            output: File,
            elapsed: Long,
        ) {
            setPauseRequested(context, false)
            _state.value = TranslatorUiState(
                videoUri = runCatching { Uri.parse(job.videoUri) }.getOrNull(),
                videoName = job.movieName,
                movieKey = job.movieKey,
                videoDurationMs = job.durationMs,
                isRunning = false,
                isPaused = false,
                progress = 1f,
                stage = "اكتملت الترجمة",
                cues = cloud.cues,
                srtFile = output,
                uploadedBytes = job.uploadedBytes,
                processingMs = elapsed,
                cloudMetrics = cloud.providers,
                partCount = if (job.durationMs > TWO_HOURS_MS) 2 else 1,
            )
        }

        internal fun failBackground(context: Context, message: String) {
            restorePending(context)
            _state.update { it.copy(isRunning = false, isPaused = false, stage = "تعذر إكمال الترجمة", error = message) }
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

    private class PauseRequestedException : RuntimeException()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var worker: Job? = null
    private var lastNotificationPercent = -1
    private var maxUploadProgress = 0f
    @Volatile private var pauseRequested = false

    override fun onCreate() {
        super.onCreate()
        activeService = this
        pauseRequested = isPauseRequested(applicationContext)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ترجمة الأفلام", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> cancelWork()
            ACTION_PAUSE -> requestPause()
            ACTION_RESUME -> {
                requestResume()
                if (worker?.isActive != true && CloudJobStore(applicationContext).load() == null && _state.value.videoUri != null) {
                    startForeground(NOTIFICATION_ID, notification("استمرار الترجمة", (_state.value.progress * 100f).toInt(), false))
                    beginUploadHandoff()
                }
            }
            ACTION_START, null -> if (worker?.isActive != true) {
                startForeground(NOTIFICATION_ID, notification("تجهيز الصوت", 1, true))
                beginUploadHandoff()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun requestPause() {
        pauseRequested = true
        _state.update { it.copy(isRunning = true, isPaused = true, stage = "متوقف مؤقتًا", error = null) }
        runCatching {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notification("متوقف مؤقتًا", (_state.value.progress * 100f).toInt(), false),
            )
        }
    }

    private fun requestResume() {
        pauseRequested = false
        _state.update { it.copy(isRunning = true, isPaused = false, stage = "استمرار الترجمة", error = null) }
    }

    private suspend fun awaitIfPaused(resumeStage: String) {
        while (scope.isActive && (pauseRequested || isPauseRequested(applicationContext))) {
            pauseRequested = true
            _state.update { it.copy(isRunning = true, isPaused = true, stage = "متوقف مؤقتًا", error = null) }
            delay(250L)
        }
        if (scope.isActive) {
            pauseRequested = false
            _state.update { it.copy(isRunning = true, isPaused = false, stage = resumeStage, error = null) }
        }
    }

    private fun beginUploadHandoff() {
        val snapshot = _state.value
        val uri = snapshot.videoUri ?: return
        val startedAt = System.currentTimeMillis()
        lastNotificationPercent = -1
        maxUploadProgress = snapshot.progress.takeIf { it in 0.15f..0.45f }?.let { (it - 0.15f) / 0.30f } ?: 0f

        worker = scope.launch {
            var parts: List<CloudAudioPart> = emptyList()
            try {
                awaitIfPaused("تجهيز الصوت")
                val durationMs = snapshot.videoDurationMs.takeIf { it > 0L }
                    ?: withContext(Dispatchers.IO) { readDuration(uri) }
                check(durationMs in 1..MAX_MOVIE_MS) { "يدعم التطبيق أفلامًا حتى 3 ساعات حاليًا." }

                publish(0.02f, "استخراج الصوت من الفيلم", true)
                parts = CloudAudioExtractor(applicationContext).prepare(uri, durationMs) { done, total ->
                    if (!pauseRequested && !isPauseRequested(applicationContext)) {
                        val ratio = done.toFloat() / total.coerceAtLeast(1).toFloat()
                        publish(0.02f + ratio * 0.13f, "تجهيز الصوت")
                    }
                }
                awaitIfPaused("رفع الصوت")
                publish(0.15f, "تم تجهيز الصوت", true)

                val bytes = parts.sumOf { it.file.length() }
                _state.update { it.copy(uploadedBytes = bytes, partCount = parts.size, videoDurationMs = durationMs) }

                val submission = submitWithAutomaticResume(parts)
                awaitIfPaused("استكمال الترجمة")
                val movieKey = snapshot.movieKey.ifBlank { CloudLibraryClient.movieKey(snapshot.videoName, durationMs) }
                val job = BackgroundCloudJob(
                    movieKey = movieKey,
                    movieName = snapshot.videoName,
                    videoUri = uri.toString(),
                    durationMs = durationMs,
                    uploadedBytes = bytes,
                    startedAtEpochMs = startedAt,
                    segments = submission.segments,
                    pendingAsr = submission.pendingAsr,
                    providers = submission.providers,
                    stage = if (submission.pendingAsr.isEmpty()) "الصوت وصل • بدء الترجمة" else "الصوت وصل • تحليل الحوار",
                    progress = if (submission.pendingAsr.isEmpty()) 0.62f else 0.45f,
                )
                CloudJobStore(applicationContext).save(job)
                updateBackgroundProgress(applicationContext, job)
                CloudCompletionWorker.schedule(applicationContext, 250L)

                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (cancelled: CancellationException) {
                if (!isPauseRequested(applicationContext)) {
                    _state.update { it.copy(isRunning = false, isPaused = false, stage = "تم إيقاف الترجمة", error = null) }
                }
                throw cancelled
            } catch (error: Throwable) {
                _state.update {
                    it.copy(isRunning = false, isPaused = false, stage = "تعذر إكمال رفع الصوت", error = error.message ?: "حدث خطأ أثناء رفع الصوت.")
                }
            } finally {
                parts.forEach { runCatching { it.file.delete() } }
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            }
        }
    }

    private suspend fun submitWithAutomaticResume(parts: List<CloudAudioPart>): CloudSubmission {
        var retryAttempt = 0
        while (scope.isActive) {
            awaitIfPaused("رفع الصوت")
            waitUntilConnected()
            try {
                return CloudAudioUploadClient(applicationContext).submitForBackground(parts) { progress ->
                    if (pauseRequested || isPauseRequested(applicationContext)) throw PauseRequestedException()
                    maxUploadProgress = maxOf(maxUploadProgress, progress.coerceIn(0f, 1f))
                    publish(0.15f + maxUploadProgress * 0.30f, "رفع الصوت بأمان")
                }
            } catch (_: PauseRequestedException) {
                awaitIfPaused("رفع الصوت")
            } catch (transient: CloudTransientException) {
                retryAttempt += 1
                val online = CloudConnectivity.isOnline(applicationContext)
                publish(
                    (0.15f + maxUploadProgress * 0.30f).coerceAtMost(0.44f),
                    CloudConnectivity.retryMessage(applicationContext, transient),
                    true,
                )
                if (!online) waitUntilConnected()
                delay(retryDelayMs(retryAttempt))
            }
        }
        throw CancellationException()
    }

    private suspend fun waitUntilConnected() {
        while (scope.isActive && !CloudConnectivity.isOnline(applicationContext)) {
            awaitIfPaused("انتظار الاتصال")
            publish(
                (0.15f + maxUploadProgress * 0.30f).coerceAtMost(0.44f),
                "الاتصال بالإنترنت غير متاح • سنستكمل تلقائيًا عند عودته",
                true,
            )
            delay(1_500L)
        }
    }

    private fun retryDelayMs(attempt: Int): Long = when {
        attempt <= 1 -> 1_500L
        attempt == 2 -> 3_000L
        attempt == 3 -> 5_000L
        else -> 10_000L
    }

    private fun readDuration(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(applicationContext, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }

    private fun publish(progress: Float, stage: String, force: Boolean = false) {
        if (pauseRequested || isPauseRequested(applicationContext)) return
        val bounded = progress.coerceIn(0f, 1f)
        _state.update { it.copy(isRunning = true, isPaused = false, progress = maxOf(it.progress, bounded), stage = stage) }
        val percent = (maxOf(_state.value.progress, bounded) * 100f).toInt().coerceIn(0, 100)
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
        pauseRequested = false
        setPauseRequested(applicationContext, false)
        worker?.cancel()
        worker = null
        CloudCompletionWorker.cancelAll(applicationContext)
        CloudJobStore(applicationContext).clear()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
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
            .setContentTitle("مترجم H AI الرقمي")
            .setContentText(text)
            .setContentIntent(pending)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress.coerceIn(0, 100), indeterminate)
            .build()
    }

    override fun onDestroy() {
        if (activeService === this) activeService = null
        scope.cancel()
        super.onDestroy()
    }
}
