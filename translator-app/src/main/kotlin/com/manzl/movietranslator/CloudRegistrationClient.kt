package com.manzl.movietranslator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class CloudRegistrationStatus(
    val registered: Boolean,
    val authorized: Boolean,
)

/**
 * Registers the user's provider keys once with the private cloud gateway.
 * Subsequent reinstalls only verify this device identity; the keys never need to be re-entered.
 */
internal class CloudRegistrationClient {
    suspend fun status(deviceHash: String): CloudRegistrationStatus = withContext(Dispatchers.IO) {
        val root = post(
            JSONObject()
                .put("mode", "status")
                .put("device_hash", deviceHash),
            timeoutMs = 20_000,
        )
        CloudRegistrationStatus(
            registered = root.optBoolean("registered", false),
            authorized = root.optBoolean("authorized", false),
        )
    }

    suspend fun register(deviceHash: String, groqKey: String, geminiKey: String) = withContext(Dispatchers.IO) {
        val root = post(
            JSONObject()
                .put("mode", "register_keys")
                .put("device_hash", deviceHash)
                .put("groq_api_key", groqKey.trim())
                .put("gemini_api_key", geminiKey.trim()),
            timeoutMs = 30_000,
        )
        check(root.optBoolean("registered", false) || root.optBoolean("ok", false)) {
            "تعذر ربط مفاتيح السحابة."
        }
    }

    private fun post(payload: JSONObject, timeoutMs: Int): JSONObject {
        val connection = (URL(CloudTranslationClient.ENDPOINT).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = timeoutMs
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${CloudTranslationClient.ANON_JWT}")
            setRequestProperty("apikey", CloudTranslationClient.PUBLISHABLE_KEY)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val root = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
            if (status !in 200..299) {
                val message = root.optString("message").takeIf { it.isNotBlank() }
                    ?: when (root.optString("error")) {
                        "owner_exists" -> "هذه السحابة مرتبطة بالفعل بجهاز آخر."
                        "invalid_groq_key" -> "مفتاح Groq غير صالح."
                        "invalid_gemini_key" -> "مفتاح Gemini غير صالح."
                        else -> "تعذر ربط التطبيق بالسحابة ($status)."
                    }
                error(message)
            }
            root
        } finally {
            connection.disconnect()
        }
    }
}
