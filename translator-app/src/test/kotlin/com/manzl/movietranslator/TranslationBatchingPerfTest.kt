package com.manzl.movietranslator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationBatchingPerfTest {
    @Test
    fun small100Runtime_capsThreadsAndSequenceLengthsForMobile() {
        assertEquals(2, TurkishArabicTranslator.stableThreadCount(4))
        assertEquals(3, TurkishArabicTranslator.stableThreadCount(6))
        assertEquals(4, TurkishArabicTranslator.stableThreadCount(8))
        assertEquals(4, TurkishArabicTranslator.stableThreadCount(12))

        assertTrue(TurkishArabicTranslator.maxInputTokensForTest() <= 160)
        assertTrue(TurkishArabicTranslator.maxOutputTokensForTest() <= 112)
        assertEquals(128006L, Small100Tokenizer.EXPECTED_ARABIC_TOKEN_ID)
    }

    @Test
    fun sentencePlanner_rebuildsFragmentedWhisperSentenceWithoutArabicResplitting() {
        val cues = listOf(
            SubtitleCue(0, 700, "Seni burada"),
            SubtitleCue(760, 1_500, "görmeyi hiç"),
            SubtitleCue(1_560, 2_500, "beklemiyordum."),
            SubtitleCue(3_100, 4_100, "Neden geldin?"),
        )

        val segments = TurkishArabicTranslator.buildSourceSegmentsForTest(cues)

        assertEquals(2, segments.size)
        assertEquals("Seni burada görmeyi hiç beklemiyordum.", segments[0].sourceText)
        assertEquals(0L, segments[0].startMs)
        assertEquals(2_500L, segments[0].endMs)
        assertEquals(3f, segments[0].confidence)
        assertEquals("Neden geldin?", segments[1].sourceText)
    }
}
