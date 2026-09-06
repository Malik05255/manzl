package com.manzl.movietranslator

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import kotlin.math.min
import kotlin.math.roundToInt

class MovieTranslatorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        applyAdaptivePhoneDensity()
        appContext = applicationContext
    }

    /**
     * The reference UI was designed around a roomy ~430dp portrait phone.
     * On narrower or shorter phones we reduce the app density proportionally,
     * so fixed Compose dp/sp values reflow without clipping or oversized cards.
     * Large phones keep the original 1:1 design instead of being enlarged.
     */
    @Suppress("DEPRECATION")
    private fun applyAdaptivePhoneDensity() {
        val metrics = resources.displayMetrics
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0 || metrics.density <= 0f) return

        val widthDp = metrics.widthPixels / metrics.density
        val heightDp = metrics.heightPixels / metrics.density
        val widthScale = (widthDp / REFERENCE_WIDTH_DP).coerceIn(MIN_UI_SCALE, 1f)
        val heightScale = (heightDp / REFERENCE_HEIGHT_DP).coerceIn(MIN_UI_SCALE, 1f)
        val scale = min(widthScale, heightScale)
        if (scale >= 0.995f) return

        val configuration = Configuration(resources.configuration)
        configuration.densityDpi = (configuration.densityDpi * scale)
            .roundToInt()
            .coerceAtLeast(120)
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
