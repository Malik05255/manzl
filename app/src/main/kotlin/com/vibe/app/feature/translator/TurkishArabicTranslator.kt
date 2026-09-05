package com.vibe.app.feature.translator

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Text translation stays on-device after ML Kit's language models are downloaded once. */
class TurkishArabicTranslator : AutoCloseable {
    private val translator: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.TURKISH)
            .setTargetLanguage(TranslateLanguage.ARABIC)
            .build()
    )

    suspend fun prepare() {
        suspendCancellableCoroutine<Unit> { continuation ->
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
                .addOnCanceledListener { continuation.cancel() }
        }
    }

    suspend fun translate(text: String): String {
        if (text.isBlank()) return ""
        return suspendCancellableCoroutine { continuation ->
            translator.translate(text.trim())
                .addOnSuccessListener { translated ->
                    if (continuation.isActive) continuation.resume(translated.trim())
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
                .addOnCanceledListener { continuation.cancel() }
        }
    }

    override fun close() {
        translator.close()
    }
}