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
    fun twoHourMovie_usesOnePart() {
        val duration = 2L * 60L * 60_000L
        val parts = CloudAudioExtractor.planParts(duration)

        assertEquals(1, parts.size)
        assertEquals(0L to duration, parts[0])
    }

    @Test
    fun threeHourMovie_usesExactlyTwoParts() {
        val hour = 60L * 60_000L
        val parts = CloudAudioExtractor.planParts(3L * hour)

        assertEquals(2, parts.size)
        assertEquals(0L to 2L * hour, parts[0])
        assertEquals(2L * hour to hour, parts[1])
    }
}
