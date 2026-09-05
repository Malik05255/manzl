package com.manzl.movietranslator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationBatchingPerfTest {
    @Test
    fun contextBatches_reduceModelCallsWithoutOversizedPrompts() {
        val cues = (0 until 12).map { index ->
            SubtitleCue(
                startMs = index * 1_000L,
                endMs = index * 1_000L + 850L,
                sourceText = "Bu kısa bir Türkçe film cümlesidir $index.",
            )
        }

        val batches = TurkishArabicTranslator.buildContextBatchesForTest(cues)

        assertTrue(batches.size < 12)
        assertTrue(batches.all { it.size <= 6 })
        assertTrue(batches.all { batch -> batch.sumOf { it.sourceText.length } <= 720 })
        assertEquals(12, batches.sumOf { it.size })
    }
}
