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
                message.contains("quota", ignoreCase = true) ||
                message.contains("rate", ignoreCase = true)
        }
        if (rateLimited) {
            return "خدمة الترجمة مشغولة مؤقتًا • تتم إعادة المحاولة تلقائيًا"
        }

        return "خدمة الترجمة غير متاحة مؤقتًا • تتم إعادة المحاولة تلقائيًا"
    }
}
