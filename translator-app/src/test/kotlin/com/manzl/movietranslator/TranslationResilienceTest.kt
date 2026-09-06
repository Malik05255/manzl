package com.manzl.movietranslator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationResilienceTest {
    @Test
    fun qualityGate_acceptsNaturalArabic() {
        assertFalse(
            TurkishArabicTranslator.translationNeedsRepairForTest(
                source = "Seni burada beklemiyordum.",
                arabic = "لم أتوقع أن أراك هنا.",
            )
        )
    }

    @Test
    fun qualityGate_rejectsUntranslatedTurkish() {
        assertTrue(
            TurkishArabicTranslator.translationNeedsRepairForTest(
                source = "Benden bunu isteme.",
                arabic = "Benden bunu isteme.",
            )
        )
    }

    @Test
    fun qualityGate_rejectsStructurallyEmptyArabicForLongSource() {
        assertTrue(
            TurkishArabicTranslator.translationNeedsRepairForTest(
                source = "Bunu neden yaptığını gerçekten anlamıyorum.",
                arabic = "لا",
            )
        )
    }
}
