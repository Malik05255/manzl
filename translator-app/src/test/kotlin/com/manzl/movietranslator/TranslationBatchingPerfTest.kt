package com.manzl.movietranslator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationBatchingPerfTest {
    @Test
    fun nllbRuntime_capsThreadsAndSequenceLengthsForMobile() {
        assertEquals(2, TurkishArabicTranslator.stableThreadCount(4))
        assertEquals(3, TurkishArabicTranslator.stableThreadCount(6))
        assertEquals(4, TurkishArabicTranslator.stableThreadCount(8))
        assertEquals(4, TurkishArabicTranslator.stableThreadCount(12))

        assertTrue(TurkishArabicTranslator.maxInputTokensForTest() <= 128)
        assertTrue(TurkishArabicTranslator.maxOutputTokensForTest() <= 96)
    }

    @Test
    fun nllbLanguageIndices_matchCanonicalFairseqOrdering() {
        assertEquals(10L, NllbTokenizer.ARABIC_LANGUAGE_INDEX)
        assertEquals(183L, NllbTokenizer.TURKISH_LANGUAGE_INDEX)
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
