package com.manzl.movietranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One-tap cloud path: copy the compressed audio track locally, upload audio only, then receive
 * Turkish ASR + professional Arabic subtitles. The video never leaves the device.
 */
class CloudMovieTranslationService : Service() {
    companion object {
        private const val ACTION_START = "com.manzl.movietranslator.cloud.START"
        private const val ACTION_CANCEL = "com.manzl.movietranslator.cloud.CANCEL"
        private const val CHANNEL_ID = "cloud_movie_translation"
        private const val NOTIFICATION_ID = 4201
        private const val COMPLETE_NOTIFICATION_ID = 4202

        private val _state = MutableStateFlow(TranslatorUiState(modelInstalled = true))
        val state: StateFlow<TranslatorUiState> = _state.asStateFlow()

        @Volatile private var activeService: CloudMovieTranslationService? = null

        fun selectVideo(context: Context, uri: Uri, displayName: String) {
            if (_state.value.isRunning) return
            _state.value = TranslatorUiState(
                videoUri = uri,
                videoName = displayName,
                stage = "جاهز للترجمة السحابية السريعة: $displayName",
                modelInstalled = true,
            )
        }

        fun refreshModelStatus(context: Context) {
            _state.update { it.copy(modelInstalled = true) }
        }

        fun start(context: Context) {
            val current = _state.value
            if (current.videoUri == null || current.isRunning) return
            _state.update {
                it.copy(
                    isRunning = true,
                    progress = 0f,
                    stage = "بدء الترجمة السحابية…",
                    error = null,
                    cues = emptyList(),
                    srtFile = null,
                )
            }
            context.applicationContext.startForegroundService(
                Intent(context.applicationContext, CloudMovieTranslationService::class.java)
                    .setAction(ACTION_START)
            )
        }

        fun cancel(context: Context) {
            activeService?.cancelWork() ?: context.applicationContext.startService(
                Intent(context.applicationContext, CloudMovieTranslationService::class.java)
                    .setAction(ACTION_CANCEL)
            )
        }

        fun clearError() = _state.update { it.copy(error = null) }
        fun stateMutableUpdateForUi(stage: String) = _state.update { it.copy(stage = stage) }
        fun stateMutableErrorForUi(message: String) = _state.update { it.copy(error = message) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var worker: Job? = null
    private var startedAt = 0L

    override fun onCreate() {
        super.onCreate()
        activeService = this
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ترجمة الأفلام السحابية", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> cancelWork()
            ACTION_START, null -> if (worker?.isActive != true) {
                startForeground(NOTIFICATION_ID, notification("تجهيز الصوت…", 2, true))
                beginTranslation()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun beginTranslation() {
        val snapshot = _state.value
        val uri = snapshot.videoUri ?: return
        startedAt = SystemClock.elapsedRealtime()

        worker = scope.launch {
            var audio: File? = null
            try {
                publish(0.05f, "استخراج الصوت فقط من الفيديو…")
                audio = CloudAudioExtractor(applicationContext).extract(uri)

                publish(0.18f, "رفع الصوت والاستماع التركي بـ Whisper Large V3…")
                val cloud = CloudTranslationClient().translate(audio)

                publish(0.92f, "تجهيز الترجمة العربية السينمائية…")
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
                _state.update {
                    it.copy(
                        isRunning = false,
                        progress = 1f,
                        stage = "تمت الترجمة خلال ${formatDuration(elapsed)} • السحابة ${formatDuration(cloud.totalMs)}",
                        cues = cloud.cues,
                        srtFile = output,
                        error = null,
                    )
                }
                getSystemService(NotificationManager::class.java).notify(
                    COMPLETE_NOTIFICATION_ID,
                    notification("اكتملت ترجمة ${snapshot.videoName} خلال ${formatDuration(elapsed)}", 100, false),
                )
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(isRunning = false, stage = "تم إيقاف الترجمة", error = null) }
                throw cancelled
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        isRunning = false,
                        stage = "تعذر إكمال الترجمة السحابية",
                        error = error.message ?: "حدث خطأ في الترجمة السحابية.",
                    )
                }
            } finally {
                runCatching { audio?.delete() }
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf()
            }
        }
    }

    private fun publish(progress: Float, stage: String) {
        _state.update { it.copy(isRunning = true, progress = progress, stage = "$stage\nمضى ${formatDuration(SystemClock.elapsedRealtime() - startedAt)}") }
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification(stage, (progress * 100).toInt(), false),
        )
    }

    private fun cancelWork() {
        worker?.cancel()
        worker = null
    }

    private fun notification(text: String, progress: Int, indeterminate: Boolean): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pending = PendingIntent.getActivity(
            this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("مترجم الأفلام")
            .setContentText(text)
            .setContentIntent(pending)
            .setOnlyAlertOnce(true)
            .setOngoing(progress < 100)
            .setProgress(100, progress.coerceIn(0, 100), indeterminate)
            .build()
    }

    private fun formatDuration(ms: Long): String {
        val seconds = (ms.coerceAtLeast(0L) + 500L) / 1000L
        return if (seconds < 60) "$seconds ث" else "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} د"
    }

    override fun onDestroy() {
        activeService = null
        scope.cancel()
        super.onDestroy()
    }
}
