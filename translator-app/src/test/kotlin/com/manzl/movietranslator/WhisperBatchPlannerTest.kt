package com.manzl.movietranslator

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        val batches = planWhisperBatches(
            listOf(window(0, 5_000, "a"), window(8_000, 12_000, "b"))
        )
        assertEquals(2, batches.size)
    }

    @Test
    fun threadCount_isConservativeOnPhones() {
        assertEquals(2, whisperThreadCount(4))
        assertEquals(3, whisperThreadCount(6))
        assertEquals(4, whisperThreadCount(8))
    }

    @Test
    fun sparseBaseTranscript_isFlaggedForSelectiveSmallRepair() {
        val sparse = listOf(SubtitleCue(0, 1_000, "Evet."))
        val healthy = listOf(
            SubtitleCue(0, 2_500, "Seni burada görmeyi gerçekten beklemiyordum."),
            SubtitleCue(2_600, 5_000, "Şimdi ne yapacağımızı konuşmamız gerekiyor."),
        )

        assertTrue(whisperBatchSuspicion(12_000, sparse) >= 2.5f)
        assertTrue(whisperBatchSuspicion(5_000, healthy) < 2.5f)
    }

    @Test
    fun repairPass_isStrictlyCapped() {
        assertEquals(1, WhisperRepairEngine.maxRepairBatches(1))
        assertEquals(2, WhisperRepairEngine.maxRepairBatches(5))
        assertEquals(4, WhisperRepairEngine.maxRepairBatches(20))
    }

    private fun window(startMs: Long, endMs: Long, name: String) = SpeechWindow(
        startMs = startMs,
        endMs = endMs,
        wavFile = File(name),
    )
}
