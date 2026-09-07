package com.manzl.movietranslator

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveUiMetricsTest {
    @Test
    fun screenZoom200Percent_keepsSamePhysicalLayoutDensity() {
        val normal = calculateAdaptiveUiMetrics(
            widthPixels = 1080,
            heightPixels = 2400,
            systemDensity = 3f,
            systemFontScale = 1f,
        )
        val zoomed = calculateAdaptiveUiMetrics(
            widthPixels = 1080,
            heightPixels = 2400,
            systemDensity = 6f,
            systemFontScale = 2f,
        )

        assertEquals(normal.density, zoomed.density, 0.001f)
        assertEquals(1.15f, zoomed.fontScale, 0.001f)
    }

    @Test
    fun portraitDensity_fitsReferenceWidthAndHeight() {
        val metrics = calculateAdaptiveUiMetrics(
            widthPixels = 1080,
            heightPixels = 2400,
            systemDensity = 3f,
            systemFontScale = 1f,
        )

        assertEquals(1080f / 430f, metrics.density, 0.001f)
        assertEquals(1f, metrics.fontScale, 0.001f)
    }

    @Test
    fun landscape_swapsReferenceBounds() {
        val metrics = calculateAdaptiveUiMetrics(
            widthPixels = 2400,
            heightPixels = 1080,
            systemDensity = 3f,
            systemFontScale = 1f,
        )

        assertEquals(1080f / 430f, metrics.density, 0.001f)
    }
}
