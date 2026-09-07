package com.manzl.movietranslator

import kotlin.math.min

/**
 * Device-independent sizing rules for the premium UI.
 *
 * The UI is intentionally derived from the available logical window, not from a device model.
 * This keeps the same hierarchy on compact phones, tall phones, large-screen phones and tablets,
 * while allowing short windows to compress vertically instead of overlapping.
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
        safeHeight < 940f -> 0.96f
        else -> 1.00f
    }

    // Font scale is already capped app-wide, but reserve a little extra space when accessibility
    // text is larger so cards compress before labels wrap into each other.
    val fontGuard = when {
        safeFont > 1.10f -> 0.94f
        safeFont > 1.04f -> 0.97f
        else -> 1.00f
    }
    val scale = min(widthFactor, heightFactor) * fontGuard

    return PremiumResponsiveSpec(
        scale = scale,
        compactWidth = safeWidth < 390f,
        shortHeight = safeHeight < 820f,
        outerPadding = 12f * scale,
        verticalGap = 9f * scale,
        headerTitleSp = 27f * scale,
        headerSubtitleSp = 13f * scale,
        greetingTitleSp = 15f * scale,
        greetingSubtitleSp = 11f * scale,
        avatarDp = 46f * scale,
        heroHeightDp = 218f * scale,
        posterWidthDp = 112f * scale,
        posterHeightDp = 174f * scale,
        workflowHeightDp = 180f * scale,
        metricHeightDp = 112f * scale,
        statusHeightDp = 80f * scale,
        stopHeightDp = 64f * scale,
        bottomBarHeightDp = 74f * scale,
    )
}
