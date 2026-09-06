package com.manzl.movietranslator

import android.content.Context

/**
 * App-scoped cloud identity used by the dedicated Supabase `models` backend.
 * It is intentionally independent from ANDROID_ID so reinstalling the APK or moving it to
 * another phone does not break the cloud link or expose provider API keys to the user.
 */
internal object CloudIdentity {
    private const val MODELS_CLOUD_ID = "cad58da7807e80a0442a01b1a02f1475eb2c8822edd18d26cb799360a56d3c2e"

    @Suppress("UNUSED_PARAMETER")
    fun deviceHash(context: Context): String = MODELS_CLOUD_ID
}
