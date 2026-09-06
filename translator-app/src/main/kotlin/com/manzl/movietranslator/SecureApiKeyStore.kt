package com.manzl.movietranslator

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the user's personal Groq/Gemini keys encrypted at rest with Android Keystore.
 * Nothing is committed to the repository and no Supabase account is required at runtime.
 */
internal class SecureApiKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasGroqKey(): Boolean = read(GROQ_KEY).isNotBlank()
    fun hasGeminiKey(): Boolean = read(GEMINI_KEY).isNotBlank()
    fun isConfigured(): Boolean = hasGroqKey() && hasGeminiKey()

    fun save(groqApiKey: String, geminiApiKey: String) {
        val groq = groqApiKey.trim()
        val gemini = geminiApiKey.trim()
        require(groq.isNotBlank()) { "أدخل مفتاح Groq." }
        require(gemini.isNotBlank()) { "أدخل مفتاح Gemini." }
        write(GROQ_KEY, groq)
        write(GEMINI_KEY, gemini)
    }

    fun clear() {
        prefs.edit().remove(GROQ_KEY).remove(GEMINI_KEY).apply()
    }

    fun requireGroqKey(): String = read(GROQ_KEY).takeIf { it.isNotBlank() }
        ?: error("أضف مفتاح Groq من إعدادات التطبيق أولًا.")

    fun requireGeminiKey(): String = read(GEMINI_KEY).takeIf { it.isNotBlank() }
        ?: error("أضف مفتاح Gemini من إعدادات التطبيق أولًا.")

    private fun write(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString(name, packed).apply()
    }

    private fun read(name: String): String {
        val packed = prefs.getString(name, null) ?: return ""
        return runCatching {
            val separator = packed.indexOf('.')
            require(separator > 0)
            val iv = Base64.decode(packed.substring(0, separator), Base64.NO_WRAP)
            val encrypted = Base64.decode(packed.substring(separator + 1), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFS_NAME = "secure_cloud_keys"
        private const val KEY_ALIAS = "manzl_movie_translator_api_keys_v1"
        private const val GROQ_KEY = "groq_api_key"
        private const val GEMINI_KEY = "gemini_api_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
