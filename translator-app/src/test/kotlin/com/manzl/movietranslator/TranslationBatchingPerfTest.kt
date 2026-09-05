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
    fun normalContextBatches_preserveEveryCueAndKeepScenesSmall() {
        val input = cues(24)
        val batches = TurkishArabicTranslator.buildContextBatchesForTest(input)

        assertTrue(batches.all { it.size <= 8 })
        assertTrue(batches.all { batch -> batch.sumOf { it.sourceText.length } <= 800 })
        assertEquals(input.size, batches.sumOf { it.size })
        assertEquals(input.map { it.sourceText }, batches.flatten().map { it.sourceText })
    }

    @Test
    fun compactBatches_remainBoundedWhenDeadlineIsTight() {
        val input = cues(24)
        val normal = TurkishArabicTranslator.buildContextBatchesForTest(input)
        val compact = TurkishArabicTranslator.buildContextBatchesForTest(input, compressed = true)

        assertTrue(compact.size <= normal.size)
        assertTrue(compact.all { it.size <= 10 })
        assertTrue(compact.all { batch -> batch.sumOf { it.sourceText.length } <= 1_000 })
        assertEquals(input.size, compact.sumOf { it.size })
    }

    @Test
    fun qualityGate_flagsPrefacesAndNonArabicLeakage() {
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
