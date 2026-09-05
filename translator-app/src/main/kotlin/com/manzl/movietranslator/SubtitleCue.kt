package com.manzl.movietranslator

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val sourceText: String,
    val translatedText: String = "",
)

object SrtFormatter {
    fun format(cues: List<SubtitleCue>): String = buildString {
        cues.forEachIndexed { index, cue ->
            append(index + 1).append('\n')
            append(time(cue.startMs)).append(" --> ").append(time(cue.endMs)).append('\n')
            append(cue.translatedText.ifBlank { cue.sourceText }.trim()).append("\n\n")
        }
    }

    private fun time(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val hours = safe / 3_600_000
        val minutes = (safe % 3_600_000) / 60_000
        val seconds = (safe % 60_000) / 1_000
        val millis = safe % 1_000
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
    }
}
