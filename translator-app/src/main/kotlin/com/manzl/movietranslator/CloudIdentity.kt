package com.manzl.movietranslator

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

/**
 * Device-scoped cloud identity. ANDROID_ID is stable for the same device/user/app-signing key.
 * The CI workflow now keeps a stable signing key, so reinstalling the same app can recover the
 * cloud registration without asking for provider keys again.
 */
internal object CloudIdentity {
    fun deviceHash(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        ).orEmpty().ifBlank { "unknown-device" }
        val material = "manzl-movie-translator-v1|${context.packageName}|$androidId"
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
