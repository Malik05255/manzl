package com.manzl.movietranslator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingBudgetTest {
    @Test
    fun tenMinuteClipTargetsTwoMinutesFortyFiveSeconds() {
        val plan = ProcessingBudget.forMovie(10L * 60_000L)
        assertEquals(165_000L, plan.targetTotalMs)
        assertTrue(plan.whisperWallBudgetMs <= 20_000L)
    }

    @Test
    fun twoHourMovieTargetsUnderTwentyFiveMinutes() {
        val plan = ProcessingBudget.forMovie(120L * 60_000L)
        assertEquals(1_485_000L, plan.targetTotalMs)
        assertTrue(plan.targetTotalMs < 25L * 60_000L)
        assertTrue(plan.whisperWallBudgetMs <= 150_000L)
    }

    @Test
    fun veryLongMediaIsCappedAtTwentyFiveMinutes() {
        val plan = ProcessingBudget.forMovie(4L * 60L * 60_000L)
        assertEquals(25L * 60_000L, plan.targetTotalMs)
    }
}
