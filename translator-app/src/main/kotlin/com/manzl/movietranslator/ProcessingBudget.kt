package com.manzl.movietranslator

import kotlin.math.roundToLong

/**
 * Time/quality governor for local movie translation.
 *
 * The target is proportional for shorter movies and capped at 25 minutes for a two-hour movie.
 * Model downloads are a one-time setup cost and are intentionally not used to shrink quality.
 */
data class ProcessingPlan(
    val movieDurationMs: Long,
    val targetTotalMs: Long,
    val whisperWallBudgetMs: Long,
    val finalReserveMs: Long,
) {
    fun remainingTotalMs(elapsedOperationMs: Long): Long =
        (targetTotalMs - elapsedOperationMs).coerceAtLeast(0L)

    fun translationDeadlineMs(operationStartedAtMs: Long): Long =
        operationStartedAtMs + (targetTotalMs - finalReserveMs).coerceAtLeast(60_000L)
}

object ProcessingBudget {
    private const val MINUTE = 60_000L
    private const val TWO_HOURS = 120L * MINUTE
    private const val MAX_TARGET = 25L * MINUTE
    private const val MIN_TARGET = 7L * MINUTE
    private const val MAX_WHISPER_WALL = 2L * MINUTE + 30_000L
    private const val MIN_WHISPER_WALL = 45_000L
    private const val FINAL_RESERVE = 60_000L

    fun forMovie(movieDurationMs: Long): ProcessingPlan {
        val safeDuration = movieDurationMs.coerceAtLeast(1L)
        val scaledTarget = (safeDuration.toDouble() / TWO_HOURS.toDouble() * MAX_TARGET)
            .roundToLong()
            .coerceAtLeast(MIN_TARGET)
            .coerceAtMost(MAX_TARGET)

        // Whisper is a repair tool, not the main ASR engine. It gets roughly 9% of the wall budget,
        // bounded tightly so it can never consume the whole 25-minute goal by itself.
        val whisperBudget = (scaledTarget * 0.09)
            .roundToLong()
            .coerceIn(MIN_WHISPER_WALL, MAX_WHISPER_WALL)

        return ProcessingPlan(
            movieDurationMs = safeDuration,
            targetTotalMs = scaledTarget,
            whisperWallBudgetMs = whisperBudget,
            finalReserveMs = FINAL_RESERVE,
        )
    }
}
