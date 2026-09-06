package com.manzl.movietranslator

import android.content.Context
import java.io.IOException

internal data class CloudTranslationResult(
    val cues: List<SubtitleCue>,
    val asrMs: Long,
    val translationMs: Long,
    val totalMs: Long,
    val providers: String,
)

internal data class CloudSubmission(
    val segments: List<StoredSegment>,
    val pendingAsr: List<PendingAsr>,
    val providers: List<String>,
)

internal data class CloudAdvance(
    val job: BackgroundCloudJob,
    val result: CloudTranslationResult? = null,
    val nextDelayMs: Long = 2_500L,
)

internal class CloudTransientException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Stable facade used by the foreground service and WorkManager.
 * The implementation now delegates to SmartCloudTranslationClient so old call sites keep working
 * while balanced ASR, Azure-first translation, selective Groq review and checkpoint recovery are enabled.
 */
internal class CloudTranslationClient(context: Context) {
    private val smart = SmartCloudTranslationClient(context.applicationContext)

    suspend fun submitForBackground(
        parts: List<CloudAudioPart>,
        onUploadProgress: (Float) -> Unit = {},
    ): CloudSubmission = smart.submitForBackground(parts, onUploadProgress)

    suspend fun advance(job: BackgroundCloudJob): CloudAdvance {
        val next = smart.advance(job)
        return CloudAdvance(
            job = next.job,
            result = next.result,
            nextDelayMs = next.nextDelayMs,
        )
    }

    companion object {
        internal const val ENDPOINT = "https://abavsspydbpkudhswmzp.supabase.co/functions/v1/movie-translate"
        internal const val PUBLISHABLE_KEY = "sb_publishable_iuZnOH7ye1WITm-xc44TiQ_CNb2d2qB"
        internal const val ANON_JWT = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImFiYXZzc3B5ZGJwa3VkaHN3bXpwIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODgzNjAzODIsImV4cCI6MjEwMzkzNjM4Mn0.uBG_5xHNo760PUq2bZLeUqURo9cqIICTeiHpMh-kYxE"
    }
}
