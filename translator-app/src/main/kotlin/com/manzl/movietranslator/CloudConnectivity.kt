package com.manzl.movietranslator

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.SocketTimeoutException

internal object CloudConnectivity {
    fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun retryMessage(context: Context, error: Throwable): String {
        if (!isOnline(context)) {
            return "الاتصال بالإنترنت غير متاح • سنستكمل تلقائيًا عند عودته"
        }

        val chain = generateSequence(error as Throwable?) { it.cause }.toList()
        val timedOut = chain.any { throwable ->
            throwable is SocketTimeoutException ||
                throwable.message.orEmpty().contains("timeout", ignoreCase = true) ||
                throwable.message.orEmpty().contains("مهلة", ignoreCase = true)
        }
        if (timedOut) {
            return "خدمة الترجمة تأخرت في الاستجابة • تتم إعادة المحاولة تلقائيًا"
        }

        val rateLimited = chain.any { throwable ->
            val message = throwable.message.orEmpty()
            message.contains("429") ||
                message.contains("500") ||
                message.contains("502") ||
                message.contains("503") ||
                message.contains("504") ||
                message.contains("quota", ignoreCase = true) ||
                message.contains("rate", ignoreCase = true) ||
                message.contains("high demand", ignoreCase = true) ||
                message.contains("overload", ignoreCase = true)
        }
        if (rateLimited) {
            return "خدمة الترجمة تحت ضغط مؤقت • تتم إعادة المحاولة تلقائيًا"
        }

        return "خدمة الترجمة غير متاحة مؤقتًا • تتم إعادة المحاولة تلقائيًا"
    }

    fun userFacingFailure(context: Context, error: Throwable): String {
        if (!isOnline(context)) {
            return "تعذر إكمال العملية لعدم توفر الإنترنت. أعد المحاولة عند عودة الاتصال."
        }
        val raw = generateSequence(error as Throwable?) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
        val technical = listOf(
            "gemini_", "groq_", "azure_", "api_error", "internal_error",
            "429", "500", "502", "503", "504", "high demand", "overload",
            "timeout", "interaction", "provider",
        ).any { raw.contains(it, ignoreCase = true) }
        if (technical) {
            return "إحدى خدمات الترجمة كانت تحت ضغط مؤقت. أعد المحاولة وسيختار التطبيق المسار المتاح تلقائيًا."
        }
        return error.message?.takeIf { message ->
            message.any { it in '\u0600'..'\u06FF' } && message.length <= 220
        } ?: "تعذر إكمال الترجمة. أعد المحاولة وسيستكمل التطبيق من المسار المتاح."
    }
}
