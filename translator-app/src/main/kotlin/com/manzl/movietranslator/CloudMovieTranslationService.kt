package com.manzl.movietranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
        private const val CHANNEL_ID = "cloud_movie_translation"
        private const val NOTIFICATION_ID = 4201
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
            CloudCompletionWorker.cancelAll(context)
            CloudJobStore(context.applicationContext).clear()
            _state.update {
                it.copy(
                    isRunning = true,
                    progress = 0f,
                    stage = "بدء المهمة",
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
                    it.copy(isRunning = false, stage = "تعذر بدء الترجمة", error = error.message ?: "تعذر تشغيل خدمة الترجمة.")
                }
            }
        }

        fun cancel(context: Context) {
            CloudCompletionWorker.cancelAll(context)
            CloudJobStore(context.applicationContext).clear()
            activeService?.cancelWork() ?: runCatching {
                context.applicationContext.startService(
                    Intent(context.applicationContext, CloudMovieTranslationService::class.java)
                        .setAction(ACTION_CANCEL)
                )
            }
            _state.update { it.copy(isRunning = false, stage = "تم إيقاف الترجمة", error = null) }
        }

        fun restorePending(context: Context) {
            val restored = CloudJobStore(context.applicationContext).restoreUiState() ?: return
            if (!_state.value.isRunning && _state.value.srtFile == null) _state.value = restored
        }

        internal fun restoreBackgroundState(context: Context, job: BackgroundCloudJob) {
            val current = _state.value
            if (current.movieKey == job.movieKey && current.isRunning) return
            _state.value = TranslatorUiState(
                videoUri = runCatching { Uri.parse(job.videoUri) }.getOrNull(),
                videoName = job.movieName,
                movieKey = job.movieKey,
                videoDurationMs = job.durationMs,
                isRunning = true,
                progress = job.progress,
                stage = job.stage,
                uploadedBytes = job.uploadedBytes,
                partCount = if (job.durationMs > TWO_HOURS_MS) 2 else 1,
            )
        }

        internal fun updateBackgroundProgress(context: Context, job: BackgroundCloudJob) {
            restoreBackgroundState(context, job)
            _state.update {
                it.copy(
                    isRunning = true,
                    progress = job.progress.coerceIn(0f, 1f),
                    stage = job.stage,
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
            _state.value = TranslatorUiState(
                videoUri = runCatching { Uri.parse(job.videoUri) }.getOrNull(),
                videoName = job.movieName,
                movieKey = job.movieKey,
                videoDurationMs = job.durationMs,
                isRunning = false,
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
            _state.update { it.copy(isRunning = false, stage = "تعذر إكمال الترجمة", error = message) }
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
    private var lastNotificationPercent = -1
    private var maxUploadProgress = 0f

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
                startForeground(NOTIFICATION_ID, notification("تجهيز الصوت", 1, true))
                beginUploadHandoff()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun beginUploadHandoff() {
        val snapshot = _state.value
        val uri = snapshot.videoUri ?: return
        val startedAt = System.currentTimeMillis()
        lastNotificationPercent = -1
        maxUploadProgress = 0f

        worker = scope.launch {
            var parts: List<CloudAudioPart> = emptyList()
            try {
                val durationMs = snapshot.videoDurationMs.takeIf { it > 0L }
                    ?: withContext(Dispatchers.IO) { readDuration(uri) }
                check(durationMs in 1..MAX_MOVIE_MS) { "يدعم التطبيق أفلامًا حتى 3 ساعات حاليًا." }

                publish(0.02f, "استخراج الصوت من الفيلم", true)
                parts = CloudAudioExtractor(applicationContext).prepare(uri, durationMs) { done, total ->
                    val ratio = done.toFloat() / total.coerceAtLeast(1).toFloat()
                    publish(0.02f + ratio * 0.13f, "تجهيز الصوت")
                }
                publish(0.15f, "تم تجهيز الصوت", true)

                val bytes = parts.sumOf { it.file.length() }
                _state.update { it.copy(uploadedBytes = bytes, partCount = parts.size, videoDurationMs = durationMs) }

                val submission = submitWithAutomaticNetworkResume(parts)
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
                    stage = if (submission.pendingAsr.isEmpty()) "الصوت وصل • بدء الترجمة على المنصة" else "الصوت وصل • تحليل الحوار على المنصة",
                    progress = if (submission.pendingAsr.isEmpty()) 0.62f else 0.45f,
                )
                CloudJobStore(applicationContext).save(job)
                updateBackgroundProgress(applicationContext, job)
                CloudCompletionWorker.schedule(applicationContext, 250L)

                // The phone's heavy foreground task ends here. Everything after the upload is
                // short network work scheduled by WorkManager and can survive leaving the app.
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(isRunning = false, stage = "تم إيقاف الترجمة", error = null) }
                throw cancelled
            } catch (error: Throwable) {
                _state.update {
                    it.copy(isRunning = false, stage = "تعذر إكمال رفع الصوت", error = error.message ?: "حدث خطأ أثناء رفع الصوت.")
                }
            } finally {
                parts.forEach { runCatching { it.file.delete() } }
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            }
        }
    }

    private suspend fun submitWithAutomaticNetworkResume(parts: List<CloudAudioPart>): CloudSubmission {
        while (scope.isActive) {
            waitUntilConnected()
            try {
                return CloudTranslationClient(applicationContext).submitForBackground(parts) { progress ->
                    maxUploadProgress = maxOf(maxUploadProgress, progress.coerceIn(0f, 1f))
                    publish(0.15f + maxUploadProgress * 0.30f, "رفع الصوت للمنصة")
                }
            } catch (transient: CloudTransientException) {
                publish((0.15f + maxUploadProgress * 0.30f).coerceAtMost(0.44f), "بانتظار الشبكة… سيتم الاستكمال تلقائيًا", true)
                waitUntilConnected()
                delay(750L)
            }
        }
        throw CancellationException()
    }

    private suspend fun waitUntilConnected() {
        while (scope.isActive && !hasNetwork()) {
            publish((0.15f + maxUploadProgress * 0.30f).coerceAtMost(0.44f), "لا توجد شبكة • التقدم متوقف مؤقتًا", true)
            delay(1_500L)
        }
    }

    private fun hasNetwork(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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
        val bounded = progress.coerceIn(0f, 1f)
        _state.update { it.copy(isRunning = true, progress = maxOf(it.progress, bounded), stage = stage) }
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
            .setContentTitle("مترجم الأفلام")
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
