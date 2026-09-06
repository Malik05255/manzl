package com.manzl.movietranslator

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudAudioPlanTest {
    @Test
    fun twoMinuteClip_usesOnePart() {
        val duration = 2L * 60_000L
        val parts = CloudAudioExtractor.planParts(duration)

        assertEquals(1, parts.size)
        assertEquals(0L to duration, parts[0])
    }

    @Test
    fun oneHourFortyFive_balancesTwoParts() {
        val duration = 105L * 60_000L
        val parts = CloudAudioExtractor.planParts(duration)

        assertEquals(2, parts.size)
        assertEquals(duration, parts.sumOf { it.second })
        assertEquals(parts[0].second, parts[1].second)
    }

    @Test
    fun twoHourMovie_usesTwoOneHourParts() {
        val hour = 60L * 60_000L
        val parts = CloudAudioExtractor.planParts(2L * hour)

        assertEquals(2, parts.size)
        assertEquals(0L to hour, parts[0])
        assertEquals(hour to hour, parts[1])
    }

    @Test
    fun threeHourMovie_usesThreeOneHourParts() {
        val hour = 60L * 60_000L
        val parts = CloudAudioExtractor.planParts(3L * hour)

        assertEquals(3, parts.size)
        assertEquals(0L to hour, parts[0])
        assertEquals(hour to hour, parts[1])
        assertEquals(2L * hour to hour, parts[2])
    }
}
