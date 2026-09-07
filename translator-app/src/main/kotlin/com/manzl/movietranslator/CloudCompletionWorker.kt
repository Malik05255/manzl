package com.manzl.movietranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

internal class CloudCompletionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = CloudJobStore(applicationContext)
        val job = store.load() ?: return Result.success()

        // Keep the process alive while the cloud phase continues after the Activity is backgrounded.
        setForeground(createForegroundInfo(job))

        if (CloudMovieTranslationService.isPauseRequested(applicationContext)) {
            CloudMovieTranslationService.markBackgroundPaused(applicationContext, job)
            return Result.success()
        }

        CloudMovieTranslationService.restoreBackgroundState(applicationContext, job)

        val advance = try {
            advanceWithResumableFallback(job)
        } catch (transient: CloudTransientException) {
            // A hard stop clears the stored job. Do not resurrect it on retry.
            if (!sameJobStillActive(store, job.movieKey)) return Result.success()
            if (CloudMovieTranslationService.isPauseRequested(applicationContext)) {
                CloudMovieTranslationService.markBackgroundPaused(applicationContext, job)
                return Result.success()
            }
            val waiting = job.copy(stage = CloudConnectivity.retryMessage(applicationContext, transient))
            store.save(waiting)
            CloudMovieTranslationService.updateBackgroundProgress(applicationContext, waiting)
            schedule(applicationContext, 3_000L)
            return Result.success()
        } catch (error: Throwable) {
            if (!sameJobStillActive(store, job.movieKey)) return Result.success()
            val friendly = CloudConnectivity.userFacingFailure(applicationContext, error)
            CloudMovieTranslationService.failBackground(applicationContext, friendly)
            return Result.failure(Data.Builder().putString("error", friendly).build())
        }

        // If the red stop button was pressed while a network request was in flight, discard its result.
        if (!sameJobStillActive(store, job.movieKey)) return Result.success()

        store.save(advance.job)
        val cloud = advance.result

        if (cloud == null && CloudMovieTranslationService.isPauseRequested(applicationContext)) {
            CloudMovieTranslationService.markBackgroundPaused(applicationContext, advance.job)
            return Result.success()
        }

        CloudMovieTranslationService.updateBackgroundProgress(applicationContext, advance.job)
        if (cloud == null) {
            schedule(applicationContext, advance.nextDelayMs)
            return Result.success()
        }

        if (!sameJobStillActive(store, job.movieKey)) return Result.success()

        val uri = Uri.parse(advance.job.videoUri)
        val output = withContext(Dispatchers.IO) {
            val dir = File(applicationContext.filesDir, "subtitles").apply { mkdirs() }
            val safeBase = advance.job.movieName
                .substringBeforeLast('.', advance.job.movieName)
                .replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
                .take(80)
                .ifBlank { "movie" }
            File(dir, "${safeBase}_ar.srt").apply {
                writeText(SrtFormatter.format(cloud.cues), Charsets.UTF_8)
            }
        }

        if (!sameJobStillActive(store, job.movieKey)) {
            runCatching { output.delete() }
            return Result.success()
        }

        val srtText = withContext(Dispatchers.IO) { output.readText(Charsets.UTF_8) }
        val elapsed = (System.currentTimeMillis() - advance.job.startedAtEpochMs).coerceAtLeast(0L)
        val library = CloudLibraryClient(applicationContext)

        try {
            library.saveTranslation(
                movieKey = advance.job.movieKey,
                movieName = advance.job.movieName,
                videoUri = uri,
                durationMs = advance.job.durationMs,
                srtText = srtText,
                cueCount = cloud.cues.size,
                processingMs = elapsed,
            )
            runCatching { library.recordUsage(advance.job.durationMs, cloud.providers) }
        } catch (error: Throwable) {
            if (!sameJobStillActive(store, job.movieKey)) return Result.success()
            val stage = if (CloudConnectivity.isOnline(applicationContext)) {
                "الترجمة جاهزة • تعذر حفظها في المكتبة، تتم إعادة المحاولة تلقائيًا"
            } else {
                "الترجمة جاهزة • سنحفظها تلقائيًا عند عودة الإنترنت"
            }
            val waiting = advance.job.copy(stage = stage, progress = 0.98f)
            store.save(waiting)
            CloudMovieTranslationService.updateBackgroundProgress(applicationContext, waiting)
            schedule(applicationContext, 4_000L)
            return Result.success()
        }

        if (!sameJobStillActive(store, job.movieKey)) return Result.success()

        CloudMovieTranslationService.completeBackground(
            context = applicationContext,
            job = advance.job,
            cloud = cloud,
            output = output,
            elapsed = elapsed,
        )
        store.clear()
        notifyComplete(advance.job.movieName)
        return Result.success()
    }

    private fun sameJobStillActive(store: CloudJobStore, movieKey: String): Boolean =
        store.load()?.movieKey == movieKey

    private suspend fun advanceWithResumableFallback(job: BackgroundCloudJob): CloudAdvance {
        val fallback = ResumableTranslationFallbackClient(applicationContext)
        if (ResumableTranslationFallbackClient.isActive(job)) return fallback.advance(job)

        return try {
            CloudTranslationClient(applicationContext).advance(job)
        } catch (error: Throwable) {
            if (!ResumableTranslationFallbackClient.canRecover(job, error)) throw error
            fallback.advance(
                job.copy(
                    translationJobId = null,
                    stage = "التحويل لمسار ترجمة قابل للاستكمال",
                    progress = maxOf(job.progress, 0.70f),
                )
            )
        }
    }

    private fun createForegroundInfo(job: BackgroundCloudJob): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(BACKGROUND_CHANNEL_ID, "ترجمة في الخلفية", NotificationManager.IMPORTANCE_LOW)
        )
        val launch = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        val pending = PendingIntent.getActivity(
            applicationContext,
            78,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(applicationContext, BACKGROUND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_status_translate)
            .setContentTitle("مترجم H AI الرقمي")
            .setContentText("تستمر ترجمة ${job.movieName} في الخلفية")
            .setContentIntent(pending)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, (job.progress.coerceIn(0f, 1f) * 100f).toInt(), false)
            .build()
        return ForegroundInfo(
            BACKGROUND_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun notifyComplete(movieName: String) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "نتائج الترجمة", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val launch = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        val pending = PendingIntent.getActivity(
            applicationContext,
            77,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            COMPLETE_NOTIFICATION_ID,
            Notification.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_status_translate)
                .setContentTitle("الترجمة جاهزة")
                .setContentText("$movieName أصبح جاهزًا في مكتبة الأفلام")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        private const val CHANNEL_ID = "cloud_translation_results"
        private const val BACKGROUND_CHANNEL_ID = "cloud_translation_background"
        private const val COMPLETE_NOTIFICATION_ID = 4310
        private const val BACKGROUND_NOTIFICATION_ID = 4311

        fun schedule(context: Context, delayMs: Long = 0L) {
            if (CloudMovieTranslationService.isPauseRequested(context)) return
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<CloudCompletionWorker>()
                .setConstraints(constraints)
                .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .addTag("movie-cloud-completion")
                .build()
            WorkManager.getInstance(context.applicationContext).enqueue(request)
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag("movie-cloud-completion")
        }
    }
}
