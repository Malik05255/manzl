package com.manzl.movietranslator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumResponsiveSpecTest {
    @Test
    fun honor200Profile_keepsReferenceScaleWithoutCrowding() {
        val adaptive = calculateAdaptiveUiMetrics(
            widthPixels = 922,
            heightPixels = 2048,
            systemDensity = 3f,
            systemFontScale = 1f,
        )
        val widthDp = 922f / adaptive.density
        val heightDp = 2048f / adaptive.density
        val spec = calculatePremiumResponsiveSpec(widthDp, heightDp, adaptive.fontScale)

        assertTrue(widthDp >= 429f)
        assertTrue(heightDp > 900f)
        assertFalse(spec.compactWidth)
        assertTrue(spec.scale >= 0.95f)
    }

    @Test
    fun commonAndroidPhones_neverProduceTinyLogicalCanvas() {
        val devices = listOf(
            720 to 1600,
            1080 to 2400,
            1080 to 2340,
            1220 to 2712,
            1440 to 3088,
            1440 to 3120,
        )

        devices.forEach { (w, h) ->
            val adaptive = calculateAdaptiveUiMetrics(w, h, 3f, 1f)
            val logicalW = w / adaptive.density
            val logicalH = h / adaptive.density
            val spec = calculatePremiumResponsiveSpec(logicalW, logicalH, adaptive.fontScale)

            assertTrue("logical width for ${w}x$h", logicalW >= 390f)
            assertTrue("logical height for ${w}x$h", logicalH >= 740f)
            assertTrue("scale for ${w}x$h", spec.scale in 0.74f..1.12f)
        }
    }

    @Test
    fun shortWindow_compressesInsteadOfGrowingCards() {
        val normal = calculatePremiumResponsiveSpec(430f, 955f, 1f)
        val short = calculatePremiumResponsiveSpec(430f, 760f, 1f)

        assertTrue(short.scale < normal.scale)
        assertTrue(short.heroHeightDp < normal.heroHeightDp)
        assertTrue(short.workflowHeightDp < normal.workflowHeightDp)
        assertTrue(short.stopHeightDp < normal.stopHeightDp)
    }

    @Test
    fun largerFontScale_reservesMoreLayoutRoom() {
        val normal = calculatePremiumResponsiveSpec(430f, 900f, 1f)
        val enlarged = calculatePremiumResponsiveSpec(430f, 900f, 1.15f)

        assertTrue(enlarged.scale < normal.scale)
    }

    @Test
    fun tabletsStayControlledInsteadOfStretchingIndefinitely() {
        val spec = calculatePremiumResponsiveSpec(650f, 900f, 1f)
        assertTrue(spec.scale <= 1.12f)
        assertFalse(spec.compactWidth)
    }
}
