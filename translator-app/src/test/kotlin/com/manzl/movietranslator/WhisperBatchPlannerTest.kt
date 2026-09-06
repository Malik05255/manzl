package com.manzl.movietranslator

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperBatchPlannerTest {
    @Test
    fun nearbySpeechWindows_areCoalescedUntilWhisperSpanLimit() {
        val windows = listOf(
            window(0, 4_000, "a"),
            window(4_600, 8_000, "b"),
            window(9_000, 14_000, "c"),
            window(15_000, 22_000, "d"),
            window(23_000, 27_500, "e"),
            window(28_000, 32_000, "f"),
        )

        val batches = planWhisperBatches(windows)

        assertEquals(2, batches.size)
        assertEquals(listOf("a", "b", "c", "d", "e"), batches[0].map { it.wavFile.name })
        assertEquals(listOf("f"), batches[1].map { it.wavFile.name })
    }

    @Test
    fun longPause_startsANewWhisperBatch() {
        val windows = listOf(
            window(0, 5_000, "a"),
            window(8_000, 12_000, "b"),
        )

        val batches = planWhisperBatches(windows)

        assertEquals(2, batches.size)
    }

    @Test
    fun threadCount_isConservativeOnSmallPhones() {
        assertEquals(2, whisperThreadCount(4))
        assertEquals(3, whisperThreadCount(6))
        assertEquals(4, whisperThreadCount(8))
    }

    private fun window(startMs: Long, endMs: Long, name: String) = SpeechWindow(
        startMs = startMs,
        endMs = endMs,
        wavFile = File(name),
    )
}
