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
import android.os.PowerManager
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

class MovieTranslationService : Service() {
    companion object {
        private const val ACTION_START = "com.manzl.movietranslator.action.START"
        private const val ACTION_CANCEL = "com.manzl.movietranslator.action.CANCEL"
        private const val CHANNEL_ID = "movie_translation"
        private const val NOTIFICATION_ID = 4101
        private const val COMPLETE_NOTIFICATION_ID = 4102
        private const val DEFAULT_MOVIE_DURATION_MS = 120L * 60_000L

        private val _state = MutableStateFlow(TranslatorUiState())
        val state: StateFlow<TranslatorUiState> = _state.asStateFlow()

        @Volatile
        private var activeService: MovieTranslationService? = null

        fun selectVideo(context: Context, uri: Uri, displayName: String) {
            if (_state.value.isRunning) return
            _state.value = TranslatorUiState(
                videoUri = uri,
                videoName = displayName,
                stage = "جاهز للترجمة: $displayName",
                modelInstalled = VoskModelManager(context.applicationContext).isInstalled(),
            )
        }

        fun refreshModelStatus(context: Context) {
            _state.update {
                it.copy(modelInstalled = VoskModelManager(context.applicationContext).isInstalled())
            }
        }

        fun start(context: Context) {
            val current = _state.value
            if (current.videoUri == null || current.isRunning) return
            _state.update {
                it.copy(
                    isRunning = true,
                    progress = 0f,
                    stage = "بدء الترجمة في الخلفية…",
                    error = null,
                    cues = emptyList(),
                    srtFile = null,
                )
            }
            runCatching {
                context.applicationContext.startForegroundService(
                    Intent(context.applicationContext, MovieTranslationService::class.java)
                        .setAction(ACTION_START)
                )
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isRunning = false,
                        stage = "تعذر بدء الترجمة",
                        error = error.message ?: "تعذر بدء المعالجة في الخلفية.",
                    )
                }
            }
        }

        fun cancel(context: Context) {
            activeService?.cancelWork() ?: runCatching {
                context.applicationContext.startService(
                    Intent(context.applicationContext, MovieTranslationService::class.java)
                        .setAction(ACTION_CANCEL)
                )
            }
        }

        fun clearError() {
            _state.update { it.copy(error = null) }
        }

        fun stateMutableUpdateForUi(stage: String) {
            _state.update { it.copy(stage = stage) }
        }

        fun stateMutableErrorForUi(message: String) {
            _state.update { it.copy(error = message) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var worker: Job? = null
    private var statusTicker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var operationStartedAtMs = 0L
    private var budgetStartedAtMs = 0L
    private var translationStartedAtMs = 0L
    private var translationDone = 0
    private var translationTotal = 0
    private var currentBaseStage = ""
    private var currentPlan: ProcessingPlan? = null
    private var lastUiProgress = -1f
    private var lastNotificationPercent = -1
    private var lastNotificationStage = ""

    override fun onCreate() {
        super.onCreate()
        activeService = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> cancelWork()
            ACTION_START, null -> {
                if (worker?.isActive != true) {
                    if (!promoteToForeground()) return START_NOT_STICKY
                    beginTranslation()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promoteToForeground(): Boolean = runCatching {
        startForeground(
            NOTIFICATION_ID,
            buildNotification("تجهيز الترجمة…", 0, indeterminate = true),
        )
        true
    }.getOrElse { error ->
        _state.update {
            it.copy(
                isRunning = false,
                stage = "تعذر بدء الترجمة",
                error = error.message ?: "تعذر تشغيل خدمة الترجمة.",
            )
        }
        stopSelf()
        false
    }

    private fun beginTranslation() {
        val snapshot = _state.value
        val videoUri = snapshot.videoUri ?: run {
            _state.update { it.copy(isRunning = false, error = "لم يتم اختيار فيلم.") }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        operationStartedAtMs = SystemClock.elapsedRealtime()
        budgetStartedAtMs = 0L
        translationStartedAtMs = 0L
        translationDone = 0
        translationTotal = 0
        currentPlan = null
        currentBaseStage = "بدء الترجمة في الخلفية…"
        lastUiProgress = -1f
        lastNotificationPercent = -1
        lastNotificationStage = ""
        startStatusTicker()
        acquireWakeLock()

        worker = scope.launch {
            var translator: TurkishArabicTranslator? = null
            var repairCandidates: List<RepairCandidate> = emptyList()
            try {
                // Model downloads are one-time setup and intentionally outside the movie timer.
                publish(0f, "تجهيز النماذج المحلية…", force = true)
                val modelManager = VoskModelManager(applicationContext)
                val modelDir = withContext(Dispatchers.IO) {
                    modelManager.ensureModel { p ->
                        publish(p * 0.03f, "تجهيز نموذج الاستماع التركي…")
                    }
                }
                _state.update { it.copy(modelInstalled = true) }

                val directReady = runCatching {
                    DirectTranslationModelManager(applicationContext).ensureModel { p ->
                        publish(0.03f + (p * 0.03f), "تجهيز نموذج الترجمة التركية ← العربية…")
                    }
                }.isSuccess

                val whisperEngine = WhisperRepairEngine(applicationContext)
                val whisperReady = whisperEngine.prepareModel()

                val movieDurationMs = withContext(Dispatchers.IO) { readMovieDurationMs(videoUri) }
                val plan = ProcessingBudget.forMovie(movieDurationMs)
                currentPlan = plan
                budgetStartedAtMs = SystemClock.elapsedRealtime()

                publish(0.08f, "التقاط كل الحوار التركي…", force = true)
                val transcription = MediaAudioTranscriber(applicationContext).transcribe(
                    uri = videoUri,
                    modelDir = modelDir,
                    onProgress = { p ->
                        publish(0.08f + p * 0.44f, "التقاط كل الحوار التركي…")
                    },
                )

                repairCandidates = transcription.repairCandidates
                check(transcription.cues.isNotEmpty() || repairCandidates.isNotEmpty()) {
                    "لم أجد حوارًا تركيًا قابلًا للترجمة في هذا الملف."
                }

                var sourceCues = transcription.cues
                val mandatoryCount = repairCandidates.count { it.mandatory }
                val elapsedAfterFastAsr = elapsedBudgetMs()
                val translationReserve = (plan.targetTotalMs * 0.62).toLong()
                val availableForOptionalWhisper = (
                    plan.targetTotalMs - elapsedAfterFastAsr - translationReserve - plan.finalReserveMs
                ).coerceAtLeast(0L)
                val optionalWhisperBudget = minOf(
                    plan.whisperWallBudgetMs,
                    availableForOptionalWhisper,
                )

                if (repairCandidates.isNotEmpty()) {
                    if (!whisperReady && mandatoryCount > 0) {
                        error("تعذر تشغيل نموذج الاستماع الدقيق، لذلك أوقفت العملية بدل حذف كلام من الترجمة.")
                    }

                    val shouldRunWhisper = whisperReady &&
                        (mandatoryCount > 0 || optionalWhisperBudget >= 8_000L)

                    if (shouldRunWhisper) {
                        val stage = if (mandatoryCount > 0) {
                            "استرجاع الكلام الذي لم يلتقطه الاستماع السريع…"
                        } else {
                            "تحسين المقاطع الأقل ثقة…"
                        }
                        publish(0.52f, stage, force = true)
                        sourceCues = whisperEngine.repair(
                            original = sourceCues,
                            candidates = repairCandidates,
                            maxWallTimeMs = optionalWhisperBudget,
                        ) { done, total ->
                            val ratio = done.toFloat() / total.coerceAtLeast(1).toFloat()
                            publish(0.52f + ratio * 0.12f, stage)
                        }
                        repairCandidates = emptyList()
                    } else {
                        repairCandidates.forEach { it.wavFile.delete() }
                        repairCandidates = emptyList()
                        publish(0.64f, "الحوار واضح — تجاوزت المراجعة الإضافية", force = true)
                    }
                } else {
                    publish(0.64f, "تم التقاط الحوار كاملًا", force = true)
                }

                check(sourceCues.isNotEmpty()) {
                    "لم ينتج نص تركي كافٍ بعد مراجعة الاستماع."
                }

                translator = TurkishArabicTranslator(applicationContext)
                publish(0.64f, "تحميل المترجم التركي ← العربي المباشر…", force = true)
                val directActive = translator.ensureModel(
                    onProgress = { p ->
                        publish(0.64f + p * 0.04f, "تحميل المترجم التركي ← العربي المباشر…")
                    },
                    allowDownload = false,
                )
                check(directReady && directActive) {
                    "تعذر تشغيل المترجم التركي ← العربي المباشر. أوقفت العملية بدل استخدام ترجمة أقل جودة."
                }

                val translationStage = "صياغة ترجمة عربية طبيعية من التركية…"
                translationStartedAtMs = SystemClock.elapsedRealtime()
                translationDone = 0
                translationTotal = 0
                publish(0.68f, translationStage, force = true)

                val translated = translator.translate(
                    cues = sourceCues,
                    deadlineAtElapsedRealtimeMs = plan.translationDeadlineMs(budgetStartedAtMs),
                ) { done, total ->
                    translationDone = done
                    translationTotal = total
                    val ratio = done.toFloat() / total.coerceAtLeast(1).toFloat()
                    publish(0.68f + ratio * 0.31f, translationStage)
                }

                check(translated.isNotEmpty()) { "لم ينتج المترجم أي سطور عربية." }

                publish(0.99f, "تنسيق التوقيت وتجهيز ملف الترجمة…", force = true)
                val output = withContext(Dispatchers.IO) {
                    val dir = File(filesDir, "subtitles").apply { mkdirs() }
                    val safeBase = snapshot.videoName
                        .substringBeforeLast('.', snapshot.videoName)
                        .replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
                        .take(80)
                        .ifBlank { "movie" }
                    File(dir, "${safeBase}_ar.srt").apply {
                        writeText(SrtFormatter.format(translated), Charsets.UTF_8)
                    }
                }

                val processingElapsed = elapsedBudgetMs()
                val targetNote = if (processingElapsed <= plan.targetTotalMs) {
                    "ضمن الهدف ${formatDuration(plan.targetTotalMs)}"
                } else {
                    "تم الحفاظ على اكتمال الحوار ولو تجاوز الوقت المستهدف"
                }
                _state.update {
                    it.copy(
                        isRunning = false,
                        progress = 1f,
                        stage = "تمت الترجمة خلال ${formatDuration(processingElapsed)} — $targetNote",
                        cues = translated,
                        srtFile = output,
                        error = null,
                    )
                }
                postCompletionNotification(snapshot.videoName)
            } catch (cancelled: CancellationException) {
                _state.update {
                    it.copy(
                        isRunning = false,
                        stage = "تم إيقاف المعالجة بعد ${formatDuration(elapsedOperationMs())}",
                        error = null,
                    )
                }
                throw cancelled
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        isRunning = false,
                        stage = "تعذر إكمال الترجمة بعد ${formatDuration(elapsedOperationMs())}",
                        error = error.message ?: "حدث خطأ أثناء الترجمة.",
                    )
                }
            } finally {
                repairCandidates.forEach { it.wavFile.delete() }
                statusTicker?.cancel()
                statusTicker = null
                translator?.close()
                releaseWakeLock()
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf()
            }
        }
    }

    private fun publish(progress: Float, stage: String, force: Boolean = false) {
        currentBaseStage = stage
        val bounded = progress.coerceIn(0f, 1f)
        val decoratedStage = decorateStage(stage)
        if (force || bounded - lastUiProgress >= 0.005f || bounded >= 1f) {
            lastUiProgress = bounded
            _state.update { it.copy(progress = bounded, stage = decoratedStage, isRunning = true) }
        }

        val percent = (bounded * 100f).toInt().coerceIn(0, 100)
        if (force || percent - lastNotificationPercent >= 3 || stage != lastNotificationStage) {
            lastNotificationPercent = percent
            lastNotificationStage = stage
            runCatching {
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    buildNotification(stage, percent, indeterminate = false),
                )
            }
        }
    }

    private fun startStatusTicker() {
        statusTicker?.cancel()
        statusTicker = scope.launch {
            while (true) {
                delay(2_000L)
                if (_state.value.isRunning && currentBaseStage.isNotBlank()) {
                    _state.update { it.copy(stage = decorateStage(currentBaseStage)) }
                }
            }
        }
    }

    private fun decorateStage(stage: String): String {
        if (operationStartedAtMs <= 0L) return stage
        val elapsed = if (budgetStartedAtMs > 0L) elapsedBudgetMs() else elapsedOperationMs()
        val target = currentPlan?.let { " • الهدف ${formatDuration(it.targetTotalMs)}" }.orEmpty()

        if (translationStartedAtMs > 0L && stage.contains("ترجم")) {
            val translatedInfo = if (translationTotal > 0) {
                "${translationDone.coerceAtMost(translationTotal)} من $translationTotal"
            } else {
                "بدء المرحلة"
            }
            val eta = estimateTranslationRemainingMs()
            val etaText = if (eta != null) {
                "متبقي تقريبًا ${formatDuration(eta)}"
            } else {
                "جارٍ حساب الوقت المتبقي"
            }
            return "$stage\n$translatedInfo • مضى ${formatDuration(elapsed)}$target • $etaText"
        }

        return "$stage\nمضى ${formatDuration(elapsed)}$target"
    }

    private fun estimateTranslationRemainingMs(): Long? {
        if (translationStartedAtMs <= 0L || translationDone <= 0 || translationTotal <= translationDone) {
            return if (translationTotal > 0 && translationDone >= translationTotal) 0L else null
        }
        val stageElapsed = (SystemClock.elapsedRealtime() - translationStartedAtMs).coerceAtLeast(1L)
        val remainingUnits = translationTotal - translationDone
        return (stageElapsed.toDouble() * remainingUnits.toDouble() / translationDone.toDouble())
            .toLong()
            .coerceAtLeast(0L)
    }

    private fun readMovieDurationMs(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(1L)
                ?: DEFAULT_MOVIE_DURATION_MS
        } catch (_: Throwable) {
            DEFAULT_MOVIE_DURATION_MS
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun elapsedOperationMs(): Long =
        if (operationStartedAtMs > 0L) {
            (SystemClock.elapsedRealtime() - operationStartedAtMs).coerceAtLeast(0L)
        } else {
            0L
        }

    private fun elapsedBudgetMs(): Long =
        if (budgetStartedAtMs > 0L) {
            (SystemClock.elapsedRealtime() - budgetStartedAtMs).coerceAtLeast(0L)
        } else {
            0L
        }

    private fun formatDuration(milliseconds: Long): String {
        val totalSeconds = (milliseconds / 1_000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    private fun cancelWork() {
        worker?.cancel()
        if (worker?.isActive != true) {
            _state.update {
                it.copy(
                    isRunning = false,
                    stage = "تم إيقاف المعالجة بعد ${formatDuration(elapsedOperationMs())}",
                )
            }
            statusTicker?.cancel()
            statusTicker = null
            releaseWakeLock()
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
    }

    private fun acquireWakeLock() {
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:movie-translation").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1_000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) runCatching { lock.release() }
        }
        wakeLock = null
    }

    private fun buildNotification(stage: String, percent: Int, indeterminate: Boolean): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_status_translate)
            .setContentTitle("مترجم الأفلام")
            .setContentText(stage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .build()
    }

    private fun postCompletionNotification(videoName: String) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_status_translate)
            .setContentTitle("اكتملت الترجمة")
            .setContentText(videoName.ifBlank { "الفيلم جاهز للمشاهدة" })
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java).notify(COMPLETE_NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ترجمة الأفلام",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "تقدم معالجة وترجمة الفيلم في الخلفية"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        activeService = null
        statusTicker?.cancel()
        statusTicker = null
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }
}
