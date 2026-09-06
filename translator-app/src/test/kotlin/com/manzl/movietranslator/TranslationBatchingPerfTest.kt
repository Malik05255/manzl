package com.manzl.movietranslator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationBatchingPerfTest {
    @Test
    fun m2mRuntime_capsThreadsAndSequenceLengthsForMobile() {
        assertEquals(2, TurkishArabicTranslator.stableThreadCount(4))
        assertEquals(3, TurkishArabicTranslator.stableThreadCount(6))
        assertEquals(4, TurkishArabicTranslator.stableThreadCount(8))
        assertEquals(4, TurkishArabicTranslator.stableThreadCount(12))

        assertTrue(TurkishArabicTranslator.maxInputTokensForTest() <= 192)
        assertTrue(TurkishArabicTranslator.maxOutputTokensForTest() <= 144)
    }

    @Test
    fun m2mLanguageIndices_matchCanonicalHuggingFaceOrdering() {
        assertEquals(2L, M2M100Tokenizer.ARABIC_LANGUAGE_INDEX)
        assertEquals(89L, M2M100Tokenizer.TURKISH_LANGUAGE_INDEX)
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
