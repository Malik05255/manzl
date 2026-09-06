package com.manzl.movietranslator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurkishArabicTranslatorTest {
    @Test
    fun qualityGate_acceptsNaturalArabicSubtitle() {
        assertFalse(
            TurkishArabicTranslator.translationNeedsRepairForTest(
                source = "Seni burada beklemiyordum.",
                arabic = "لم أكن أتوقع وجودك هنا.",
            )
        )
    }

    @Test
    fun qualityGate_rejectsUntranslatedTurkish() {
        assertTrue(
            TurkishArabicTranslator.translationNeedsRepairForTest(
                source = "Neden geldin?",
                arabic = "Neden geldin?",
            )
        )
    }

    @Test
    fun mobileTokenCapsStaySubtitleSized() {
        assertTrue(TurkishArabicTranslator.maxInputTokensForTest() <= 128)
        assertTrue(TurkishArabicTranslator.maxOutputTokensForTest() <= 96)
    }
}
