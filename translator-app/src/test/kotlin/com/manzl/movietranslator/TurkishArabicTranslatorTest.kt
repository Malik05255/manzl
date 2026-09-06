package com.manzl.movietranslator

import org.junit.Assert.assertEquals
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
    fun contextPlanner_preservesTimelineOrder() {
        val cues = listOf(
            SubtitleCue(0, 900, "Seni"),
            SubtitleCue(950, 1_700, "burada beklemiyordum."),
            SubtitleCue(2_000, 2_900, "Neden geldin?"),
        )

        val flattened = TurkishArabicTranslator.buildContextGroupsForTest(cues).flatten()
        assertEquals(cues.map { it.startMs }, flattened.map { it.startMs })
        assertEquals(cues.map { it.endMs }, flattened.map { it.endMs })
        assertEquals(cues.map { it.sourceText }, flattened.map { it.sourceText })
    }
}
