package com.manzl.movietranslator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurkishArabicTranslatorTest {
    @Test
    fun batching_preservesFragmentedWhisperCuesOneForOne() {
        val cues = listOf(
            SubtitleCue(0, 900, "Seni"),
            SubtitleCue(950, 1_700, "burada beklemiyordum."),
            SubtitleCue(2_600, 3_300, "Neden geldin?"),
        )

        val flattened = TurkishArabicTranslator.buildContextBatchesForTest(cues).flatten()

        assertEquals(cues.size, flattened.size)
        assertEquals(cues.map { it.sourceText }, flattened.map { it.sourceText })
        assertEquals(cues.map { it.startMs }, flattened.map { it.startMs })
        assertEquals(cues.map { it.endMs }, flattened.map { it.endMs })
    }

    @Test
    fun batching_keepsLongDialogueInSeveralBoundedWindows() {
        val cues = (0 until 20).map { index ->
            SubtitleCue(index * 900L, index * 900L + 800L, "uzun diyalog parçası $index")
        }

        val batches = TurkishArabicTranslator.buildContextBatchesForTest(cues)

        assertTrue(batches.size > 1)
        assertTrue(batches.all { it.size <= 8 })
        assertEquals(cues.size, batches.sumOf { it.size })
    }
}
