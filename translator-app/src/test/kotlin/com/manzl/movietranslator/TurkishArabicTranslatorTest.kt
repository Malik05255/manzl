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
    fun sentencePlanner_preservesSemanticTimelineOrder() {
        val cues = listOf(
            SubtitleCue(0, 900, "Seni"),
            SubtitleCue(950, 1_700, "burada beklemiyordum."),
            SubtitleCue(2_000, 2_900, "Neden geldin?"),
        )

        val segments = TurkishArabicTranslator.buildSourceSegmentsForTest(cues)
        assertEquals(2, segments.size)
        assertEquals(0L, segments[0].startMs)
        assertEquals(1_700L, segments[0].endMs)
        assertEquals("Seni burada beklemiyordum.", segments[0].sourceText)
        assertEquals(2_000L, segments[1].startMs)
        assertEquals(2_900L, segments[1].endMs)
    }
}
