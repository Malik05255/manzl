package com.manzl.movietranslator

import kotlin.math.roundToLong

/**
 * Time/quality governor for local movie translation.
 *
 * The target includes a small fixed startup allowance plus roughly 20% of media duration, capped
 * at 25 minutes for long movies. One-time model downloads remain outside the processing budget.
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
        operationStartedAtMs + (targetTotalMs - finalReserveMs).coerceAtLeast(45_000L)
}

object ProcessingBudget {
    private const val MINUTE = 60_000L
    private const val MAX_TARGET = 25L * MINUTE
    private const val MIN_TARGET = 90_000L
    private const val FIXED_STARTUP_ALLOWANCE = 45_000L
    private const val MAX_WHISPER_WALL = 2L * MINUTE + 30_000L
    private const val MIN_WHISPER_WALL = 20_000L
    private const val MIN_FINAL_RESERVE = 15_000L
    private const val MAX_FINAL_RESERVE = 60_000L

    fun forMovie(movieDurationMs: Long): ProcessingPlan {
        val safeDuration = movieDurationMs.coerceAtLeast(1L)
        val scaledTarget = (FIXED_STARTUP_ALLOWANCE + safeDuration * 0.20)
            .roundToLong()
            .coerceIn(MIN_TARGET, MAX_TARGET)

        // Whisper repairs only the lowest-confidence speech. Short clips therefore get a small
        // repair slice instead of inheriting the multi-minute budget intended for feature films.
        val whisperBudget = (scaledTarget * 0.09)
            .roundToLong()
            .coerceIn(MIN_WHISPER_WALL, MAX_WHISPER_WALL)

        val finalReserve = (scaledTarget * 0.04)
            .roundToLong()
            .coerceIn(MIN_FINAL_RESERVE, MAX_FINAL_RESERVE)

        return ProcessingPlan(
            movieDurationMs = safeDuration,
            targetTotalMs = scaledTarget,
            whisperWallBudgetMs = whisperBudget,
            finalReserveMs = finalReserve,
        )
    }
}
