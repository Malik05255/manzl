package com.manzl.movietranslator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationBatchingPerfTest {
    private fun cues(count: Int): List<SubtitleCue> = (0 until count).map { index ->
        SubtitleCue(
            startMs = index * 1_000L,
            endMs = index * 1_000L + 800L,
            sourceText = "Bu kısa bir Türkçe film cümlesidir $index.",
        )
    }

    @Test
    fun mlKitContextGroups_areSmallAndPreserveEveryCue() {
        val input = cues(24)
        val groups = TurkishArabicTranslator.buildContextGroupsForTest(input)

        assertTrue(groups.all { it.size <= TurkishArabicTranslator.maxContextGroupSizeForTest() })
        assertTrue(groups.all { group -> group.sumOf { it.sourceText.length } <= TurkishArabicTranslator.maxContextCharsForTest() })
        assertEquals(input.size, groups.sumOf { it.size })
        assertEquals(input.map { it.sourceText }, groups.flatten().map { it.sourceText })
    }

    @Test
    fun longSilence_startsNewTranslationContext() {
        val input = listOf(
            SubtitleCue(0, 800, "Buraya gel."),
            SubtitleCue(900, 1_700, "Tamam."),
            SubtitleCue(4_000, 4_800, "Şimdi konuşabiliriz."),
        )

        val groups = TurkishArabicTranslator.buildContextGroupsForTest(input)
        assertEquals(2, groups.size)
        assertEquals(2, groups.first().size)
        assertEquals(1, groups.last().size)
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
