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
    fun normalContextBatches_preserveEveryCueAndKeepNativePromptsSmall() {
        val input = cues(24)
        val batches = TurkishArabicTranslator.buildContextBatchesForTest(input)

        assertTrue(batches.all { it.size <= 4 })
        assertTrue(batches.all { batch -> batch.sumOf { it.sourceText.length } <= 360 })
        assertEquals(input.size, batches.sumOf { it.size })
        assertEquals(input.map { it.sourceText }, batches.flatten().map { it.sourceText })
    }

    @Test
    fun compactBatches_remainBoundedWhenDeadlineIsTight() {
        val input = cues(24)
        val normal = TurkishArabicTranslator.buildContextBatchesForTest(input)
        val compact = TurkishArabicTranslator.buildContextBatchesForTest(input, compressed = true)

        assertTrue(compact.size <= normal.size)
        assertTrue(compact.all { it.size <= 5 })
        assertTrue(compact.all { batch -> batch.sumOf { it.sourceText.length } <= 420 })
        assertEquals(input.size, compact.sumOf { it.size })
    }

    @Test
    fun mobileInferenceProfile_capsThreadsAndGeneratedTokens() {
        assertEquals(2, TurkishArabicTranslator.stableThreadCount(4))
        assertEquals(2, TurkishArabicTranslator.stableThreadCount(6))
        assertEquals(3, TurkishArabicTranslator.stableThreadCount(8))
        assertEquals(3, TurkishArabicTranslator.stableThreadCount(12))

        assertTrue(TurkishArabicTranslator.maxBatchTokensForTest(120, 2) <= 200)
        assertTrue(TurkishArabicTranslator.maxBatchTokensForTest(420, 5) <= 200)
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
