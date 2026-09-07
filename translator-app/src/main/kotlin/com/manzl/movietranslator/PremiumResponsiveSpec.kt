package com.manzl.movietranslator

import kotlin.math.min

/**
 * Device-independent sizing rules for the premium UI.
 *
 * The approved visual reference is calibrated against the Honor 200 class window (roughly
 * 430dp wide and 950dp tall after the app-local density normalization). Other Android phones use
 * the same system with smooth compression/expansion rather than model-name checks, so the layout
 * remains stable when the APK is installed on another device.
 */
internal data class PremiumResponsiveSpec(
    val scale: Float,
    val compactWidth: Boolean,
    val shortHeight: Boolean,
    val outerPadding: Float,
    val verticalGap: Float,
    val headerTitleSp: Float,
    val headerSubtitleSp: Float,
    val greetingTitleSp: Float,
    val greetingSubtitleSp: Float,
    val avatarDp: Float,
    val heroHeightDp: Float,
    val posterWidthDp: Float,
    val posterHeightDp: Float,
    val workflowHeightDp: Float,
    val metricHeightDp: Float,
    val statusHeightDp: Float,
    val stopHeightDp: Float,
    val bottomBarHeightDp: Float,
)

internal fun calculatePremiumResponsiveSpec(
    widthDp: Float,
    heightDp: Float,
    fontScale: Float,
): PremiumResponsiveSpec {
    val safeWidth = widthDp.takeIf { it.isFinite() && it > 0f } ?: 430f
    val safeHeight = heightDp.takeIf { it.isFinite() && it > 0f } ?: 820f
    val safeFont = fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f

    val widthFactor = (safeWidth / 430f).coerceIn(0.82f, 1.12f)
    val heightFactor = when {
        safeHeight < 740f -> 0.76f
        safeHeight < 820f -> 0.82f
        safeHeight < 900f -> 0.90f
        safeHeight < 930f -> 0.96f
        else -> 1.00f
    }

    // Font scale is already capped app-wide, but reserve a little extra space when accessibility
    // text is larger so labels never collide with icons or neighboring cards.
    val fontGuard = when {
        safeFont > 1.10f -> 0.94f
        safeFont > 1.04f -> 0.97f
        else -> 1.00f
    }
    val scale = min(widthFactor, heightFactor) * fontGuard

    // Honor 200 calibration is geometry-based, not device-name-based. A phone with the same tall
    // logical window receives the same premium proportions. The blend is continuous so nearby
    // Samsung/Pixel/Honor sizes stay visually consistent instead of jumping between layouts.
    val tallPhoneBlend = if (safeWidth in 405f..455f) {
        ((safeHeight - 915f) / 40f).coerceIn(0f, 1f)
    } else {
        0f
    }

    fun tuned(base: Float, honorTarget: Float): Float =
        (base + (honorTarget - base) * tallPhoneBlend) * scale

    return PremiumResponsiveSpec(
        scale = scale,
        compactWidth = safeWidth < 390f,
        shortHeight = safeHeight < 820f,
        outerPadding = tuned(12f, 12f),
        verticalGap = tuned(9f, 10.5f),
        headerTitleSp = tuned(27f, 29f),
        headerSubtitleSp = tuned(13f, 13.5f),
        greetingTitleSp = tuned(15f, 16f),
        greetingSubtitleSp = tuned(11f, 11.5f),
        avatarDp = tuned(46f, 49f),
        heroHeightDp = tuned(218f, 232f),
        posterWidthDp = tuned(112f, 116f),
        posterHeightDp = tuned(174f, 184f),
        workflowHeightDp = tuned(180f, 205f),
        metricHeightDp = tuned(112f, 128f),
        statusHeightDp = tuned(80f, 94f),
        stopHeightDp = tuned(64f, 76f),
        bottomBarHeightDp = tuned(74f, 80f),
    )
}
