package com.manzl.movietranslator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationBatchingPerfTest {
    private fun cues(count: Int): List<SubtitleCue> = (0 until count).map { index ->
        SubtitleCue(
            startMs = index * 1_000L,
            endMs = index * 1_000L + 850L,
            sourceText = "Bu kısa bir Türkçe film cümlesidir $index.",
        )
    }

    @Test
    fun normalContextBatches_reduceModelCallsWithoutOversizedPrompts() {
        val input = cues(24)
        val batches = TurkishArabicTranslator.buildContextBatchesForTest(input)

        assertTrue(batches.size < input.size)
        assertTrue(batches.all { it.size <= 8 })
        assertTrue(batches.all { batch -> batch.sumOf { it.sourceText.length } <= 900 })
        assertEquals(input.size, batches.sumOf { it.size })
    }

    @Test
    fun compressedContextBatches_reduceCallsFurtherWhenDeadlineIsAtRisk() {
        val input = cues(24)
        val normal = TurkishArabicTranslator.buildContextBatchesForTest(input)
        val compressed = TurkishArabicTranslator.buildContextBatchesForTest(input, compressed = true)

        assertTrue(compressed.size <= normal.size)
        assertTrue(compressed.all { it.size <= 12 })
        assertTrue(compressed.all { batch -> batch.sumOf { it.sourceText.length } <= 1_300 })
        assertEquals(input.size, compressed.sumOf { it.size })
    }

    @Test
    fun qualityGate_flagsModelPrefacesAndNonArabicLeakage() {
        assertTrue(
            TurkishArabicTranslator.translationNeedsRepairForTest(
                source = "Seni burada görmeyi beklemiyordum.",
                arabic = "بالطبع، الترجمة العربية: لم أتوقع أن أراك هنا.",
            )
        )
        assertTrue(
            TurkishArabicTranslator.translationNeedsRepairForTest(
                source = "Bunu neden yaptın?",
                arabic = "Bunu neden yaptın",
            )
        )
        assertFalse(
            TurkishArabicTranslator.translationNeedsRepairForTest(
                source = "Seni burada görmeyi beklemiyordum.",
                arabic = "لم أتوقع أن أراك هنا.",
            )
        )
    }
}
