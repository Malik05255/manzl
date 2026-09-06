package com.manzl.movietranslator

import android.app.Application
import android.content.Context

class MovieTranslatorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
    }

    companion object {
        @Volatile
        internal lateinit var appContext: Context
            private set
    }
}
