package com.manzl.movietranslator

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import kotlin.math.min
import kotlin.math.roundToInt

class MovieTranslatorApplication : Application() {
    private var baseDensityDpi: Int = 0

    override fun onCreate() {
        super.onCreate()
        baseDensityDpi = resources.configuration.densityDpi.coerceAtLeast(120)
        applyAdaptivePhoneDensity()
        appContext = applicationContext
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyAdaptivePhoneDensity()
    }

    /**
     * The reference UI is composed around a roomy ~430dp portrait phone.
     * Narrow/short phones get a proportional app-only density reduction so
     * cards, typography, progress rings and bottom navigation all fit without
     * clipping. Larger phones keep the reference 1:1 sizing.
     */
    @Suppress("DEPRECATION")
    private fun applyAdaptivePhoneDensity() {
        val metrics = resources.displayMetrics
        val originalDpi = baseDensityDpi.takeIf { it > 0 } ?: resources.configuration.densityDpi
        val originalDensity = originalDpi / 160f
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0 || originalDensity <= 0f) return

        val widthDp = metrics.widthPixels / originalDensity
        val heightDp = metrics.heightPixels / originalDensity
        val widthScale = (widthDp / REFERENCE_WIDTH_DP).coerceIn(MIN_UI_SCALE, 1f)
        val heightScale = (heightDp / REFERENCE_HEIGHT_DP).coerceIn(MIN_UI_SCALE, 1f)
        val scale = min(widthScale, heightScale)

        val targetDpi = (originalDpi * scale).roundToInt().coerceAtLeast(120)
        if (resources.configuration.densityDpi == targetDpi) return

        val configuration = Configuration(resources.configuration)
        configuration.densityDpi = targetDpi
        resources.updateConfiguration(configuration, metrics)
    }

    companion object {
        private const val REFERENCE_WIDTH_DP = 430f
        private const val REFERENCE_HEIGHT_DP = 820f
        private const val MIN_UI_SCALE = 0.78f

        @Volatile
        internal lateinit var appContext: Context
            private set
    }
}
