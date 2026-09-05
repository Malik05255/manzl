package com.manzl.movietranslator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationResilienceTest {
    @Test
    fun singleArabicCandidate_acceptsBareArabicWithoutMarker() {
        assertEquals("مني.", TurkishArabicTranslator.singleArabicCandidateForTest("مني."))
    }

    @Test
    fun singleArabicCandidate_stripsModelPrefaceAndTurkishParenthetical() {
        assertEquals(
            "مني",
            TurkishArabicTranslator.singleArabicCandidateForTest("الترجمة العربية: مني (benden)")
        )
    }

    @Test
    fun singleArabicCandidate_rejectsUntranslatedTurkish() {
        assertTrue(TurkishArabicTranslator.singleArabicCandidateForTest("benden").isBlank())
    }

    @Test
    fun contextualFragmentFallback_coversObservedAblativePronoun() {
        assertEquals("مني.", TurkishArabicTranslator.contextualFragmentFallbackForTest("benden"))
        assertTrue(TurkishArabicTranslator.contextualFragmentFallbackForTest("bilinmeyenkelime").isBlank())
    }
}
