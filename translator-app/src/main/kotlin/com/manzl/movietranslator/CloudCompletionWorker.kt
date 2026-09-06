package com.manzl.movietranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
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
        CloudMovieTranslationService.restoreBackgroundState(applicationContext, job)

        val advance = try {
            CloudTranslationClient(applicationContext).advance(job)
        } catch (transient: CloudTransientException) {
            val waiting = job.copy(stage = "بانتظار الشبكة… سيتم الاستكمال تلقائيًا")
            store.save(waiting)
            CloudMovieTranslationService.updateBackgroundProgress(applicationContext, waiting)
            schedule(applicationContext, 2_000L)
            return Result.success()
        } catch (error: Throwable) {
            CloudMovieTranslationService.failBackground(
                applicationContext,
                error.message ?: "تعذر إكمال الترجمة السحابية.",
            )
            return Result.failure(Data.Builder().putString("error", error.message).build())
        }

        store.save(advance.job)
        CloudMovieTranslationService.updateBackgroundProgress(applicationContext, advance.job)
        val cloud = advance.result
        if (cloud == null) {
            schedule(applicationContext, 2_500L)
            return Result.success()
        }

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
        val srtText = withContext(Dispatchers.IO) { output.readText(Charsets.UTF_8) }
        val elapsed = (System.currentTimeMillis() - advance.job.startedAtEpochMs).coerceAtLeast(0L)

        try {
            CloudLibraryClient(applicationContext).saveTranslation(
                movieKey = advance.job.movieKey,
                movieName = advance.job.movieName,
                videoUri = uri,
                durationMs = advance.job.durationMs,
                srtText = srtText,
                cueCount = cloud.cues.size,
                processingMs = elapsed,
            )
        } catch (error: Throwable) {
            val waiting = advance.job.copy(stage = "الترجمة جاهزة • بانتظار الشبكة لحفظها", progress = 0.98f)
            store.save(waiting)
            CloudMovieTranslationService.updateBackgroundProgress(applicationContext, waiting)
            schedule(applicationContext, 3_000L)
            return Result.success()
        }

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
        private const val COMPLETE_NOTIFICATION_ID = 4310

        fun schedule(context: Context, delayMs: Long = 0L) {
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
