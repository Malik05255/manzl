package com.manzl.movietranslator

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class MovieTranslatorApplication : Application() {
    private var lastSystemFontScale: Float = 1f

    override fun onCreate() {
        super.onCreate()
        lastSystemFontScale = resources.configuration.fontScale
        applyAdaptiveConfiguration(this, lastSystemFontScale)

        // Apply the same app-only normalization to each Activity before Compose is created.
        // This makes the layout independent from Android Display size / Screen zoom settings.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                applyAdaptiveConfiguration(activity, lastSystemFontScale)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        appContext = applicationContext
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // Capture the user's real font setting before applying our app-local capped value.
        lastSystemFontScale = newConfig.fontScale
        super.onConfigurationChanged(newConfig)
        applyAdaptiveConfiguration(this, lastSystemFontScale)
    }

    @Suppress("DEPRECATION")
    private fun applyAdaptiveConfiguration(context: Context, systemFontScale: Float) {
        val resources = context.resources
        val metrics = resources.displayMetrics
        val adaptive = calculateAdaptiveUiMetrics(
            widthPixels = metrics.widthPixels,
            heightPixels = metrics.heightPixels,
            systemDensity = metrics.density,
            systemFontScale = systemFontScale,
        )
        val targetDpi = (adaptive.density * 160f).roundToInt().coerceAtLeast(120)
        val current = resources.configuration
        if (current.densityDpi == targetDpi && abs(current.fontScale - adaptive.fontScale) < 0.001f) return

        val configuration = Configuration(current).apply {
            densityDpi = targetDpi
            fontScale = adaptive.fontScale
        }
        resources.updateConfiguration(configuration, metrics)
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
 * A 200% Display size setting can halve the logical width Android reports while the phone still
 * has the same physical pixel area. Using the physical pixel bounds as the source of truth keeps
 * the reference layout proportional instead of multiplying already-large cards and typography.
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
    val safeSystemFontScale = systemFontScale.takeIf { it.isFinite() && it > 0f } ?: 1f
    if (widthPixels <= 0 || heightPixels <= 0) {
        return AdaptiveUiMetrics(
            density = safeSystemDensity,
            fontScale = safeSystemFontScale.coerceIn(MIN_APP_FONT_SCALE, MAX_APP_FONT_SCALE),
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
        // Retain modest accessibility scaling, but prevent 150–200% font/display combinations
        // from clipping dialogs, action buttons and the bottom navigation.
        fontScale = safeSystemFontScale.coerceIn(MIN_APP_FONT_SCALE, MAX_APP_FONT_SCALE),
    )
}

private const val REFERENCE_PORTRAIT_WIDTH_DP = 430f
private const val REFERENCE_PORTRAIT_HEIGHT_DP = 820f
private const val MIN_APP_DENSITY = 0.75f
private const val MIN_APP_FONT_SCALE = 0.85f
private const val MAX_APP_FONT_SCALE = 1.15f
