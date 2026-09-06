package com.manzl.movietranslator

internal fun marker(index: Int): String = "§$index§"

/** Internal compatibility marker: counted by the service but never written to SRT. */
internal const val SKIP_SUBTITLE_TEXT = "\u2063"
