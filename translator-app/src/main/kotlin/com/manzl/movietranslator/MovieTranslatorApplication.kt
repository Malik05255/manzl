package com.manzl.movietranslator

import android.app.Application
import android.content.Context
import kotlin.math.min

class MovieTranslatorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
    }

    companion object {
        @Volatile
        internal lateinit var appContext: Context
            private set
    }
}

/**
 * App-local UI metrics derived from physical pixels rather than Android's logical dp size.
 *
 * This is intentional: a user can set Display size / Screen zoom to 200%, which makes the
 * system-reported dp width much smaller even though the phone has the same physical pixel area.
 * Basing the Compose density on physical pixels keeps the layout proportional and prevents cards,
 * dialogs and bottom navigation from being clipped at large display/font settings.
 */
internal data class AdaptiveUiMetrics(
    val density: Float,
    val fontScale: Float,
)

internal fun calculateAdaptiveUiMetrics(
    widthPixels: Int,
    heightPixels: Int,
    systemDensity: Float,
    systemFontScale: Float,
): AdaptiveUiMetrics {
    val safeSystemDensity = systemDensity.takeIf { it.isFinite() && it > 0f } ?: 1f
    if (widthPixels <= 0 || heightPixels <= 0) {
        return AdaptiveUiMetrics(
            density = safeSystemDensity,
            fontScale = systemFontScale.coerceIn(MIN_APP_FONT_SCALE, MAX_APP_FONT_SCALE),
        )
    }

    val portrait = heightPixels >= widthPixels
    val referenceWidth = if (portrait) REFERENCE_PORTRAIT_WIDTH_DP else REFERENCE_PORTRAIT_HEIGHT_DP
    val referenceHeight = if (portrait) REFERENCE_PORTRAIT_HEIGHT_DP else REFERENCE_PORTRAIT_WIDTH_DP

    val widthFitDensity = widthPixels / referenceWidth
    val heightFitDensity = heightPixels / referenceHeight
    val fittedDensity = min(widthFitDensity, heightFitDensity)
        .takeIf { it.isFinite() && it > 0f }
        ?: safeSystemDensity

    return AdaptiveUiMetrics(
        density = fittedDensity.coerceAtLeast(MIN_APP_DENSITY),
        // Huge accessibility/display combinations (for example 200%) used to multiply the
        // already-large fixed typography. Keep a modest amount of user font scaling without
        // allowing it to destroy the one-screen layout.
        fontScale = systemFontScale.coerceIn(MIN_APP_FONT_SCALE, MAX_APP_FONT_SCALE),
    )
}

private const val REFERENCE_PORTRAIT_WIDTH_DP = 430f
private const val REFERENCE_PORTRAIT_HEIGHT_DP = 820f
private const val MIN_APP_DENSITY = 1f
private const val MIN_APP_FONT_SCALE = 0.85f
private const val MAX_APP_FONT_SCALE = 1.15f
