package com.manzl.movietranslator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumResponsiveSpecTest {
    @Test
    fun honor200Profile_usesReferenceCalibratedProportions() {
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
        assertTrue(heightDp >= 950f)
        assertFalse(spec.compactWidth)
        assertEquals(1f, spec.scale, 0.02f)
        assertEquals(232f, spec.heroHeightDp, 1.5f)
        assertEquals(205f, spec.workflowHeightDp, 1.5f)
        assertEquals(128f, spec.metricHeightDp, 1.5f)
        assertEquals(94f, spec.statusHeightDp, 1.5f)
        assertEquals(76f, spec.stopHeightDp, 1.5f)
    }

    @Test
    fun commonAndroidPhones_keepPremiumLayoutWithinSafeRange() {
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
            assertTrue("hero for ${w}x$h", spec.heroHeightDp in 130f..270f)
            assertTrue("workflow for ${w}x$h", spec.workflowHeightDp in 105f..235f)
        }
    }

    @Test
    fun shortWindow_compressesInsteadOfGrowingCards() {
        val honor = calculatePremiumResponsiveSpec(430f, 955f, 1f)
        val short = calculatePremiumResponsiveSpec(430f, 760f, 1f)

        assertTrue(short.scale < honor.scale)
        assertTrue(short.heroHeightDp < honor.heroHeightDp)
        assertTrue(short.workflowHeightDp < honor.workflowHeightDp)
        assertTrue(short.stopHeightDp < honor.stopHeightDp)
    }

    @Test
    fun nearbyTallPhones_blendSmoothlyTowardHonorReference() {
        val normal = calculatePremiumResponsiveSpec(430f, 915f, 1f)
        val mid = calculatePremiumResponsiveSpec(430f, 935f, 1f)
        val honor = calculatePremiumResponsiveSpec(430f, 955f, 1f)

        assertTrue(normal.heroHeightDp < mid.heroHeightDp)
        assertTrue(mid.heroHeightDp < honor.heroHeightDp)
        assertTrue(normal.metricHeightDp < mid.metricHeightDp)
        assertTrue(mid.metricHeightDp < honor.metricHeightDp)
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
