package com.manzl.movietranslator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurkishArabicTranslatorTest {
    @Test
    fun semanticMerge_joinsFragmentedDialogueUntilSentenceBoundary() {
        val cues = listOf(
            SubtitleCue(0, 900, "Seni"),
            SubtitleCue(950, 1_700, "burada beklemiyordum."),
            SubtitleCue(2_600, 3_300, "Neden geldin?"),
        )

        val merged = TurkishArabicTranslator.mergeSemanticCuesForTest(cues)

        assertEquals(2, merged.size)
        assertEquals("Seni burada beklemiyordum.", merged[0].sourceText)
        assertEquals(0, merged[0].startMs)
        assertEquals(1_700, merged[0].endMs)
        assertEquals("Neden geldin?", merged[1].sourceText)
    }

    @Test
    fun semanticMerge_doesNotCreateVeryLongSubtitle() {
        val cues = (0 until 8).map { index ->
            SubtitleCue(index * 900L, index * 900L + 800L, "uzun diyalog parçası $index")
        }

        val merged = TurkishArabicTranslator.mergeSemanticCuesForTest(cues)

        assertTrue(merged.size > 1)
        assertTrue(merged.all { it.endMs - it.startMs <= 6_500L })
    }
}
