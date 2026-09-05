package com.manzl.movietranslator

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val sourceText: String,
    val translatedText: String = "",
    val confidence: Float = 1f,
)

object SrtFormatter {
    fun format(cues: List<SubtitleCue>): String {
        val normalized = normalizeTimeline(cues)
        return buildString {
            normalized.forEachIndexed { index, cue ->
                append(index + 1).append('\n')
                append(time(cue.startMs)).append(" --> ").append(time(cue.endMs)).append('\n')
                append(wrap(cue.translatedText.ifBlank { cue.sourceText }.trim())).append("\n\n")
            }
        }
    }

    private fun normalizeTimeline(cues: List<SubtitleCue>): List<SubtitleCue> {
        val sorted = cues.sortedBy { it.startMs }
        return sorted.mapIndexed { index, cue ->
            val nextStart = sorted.getOrNull(index + 1)?.startMs
            val naturalEnd = cue.endMs.coerceAtLeast(cue.startMs + 350L)
            val safeEnd = if (nextStart != null && naturalEnd >= nextStart) {
                (nextStart - 60L).coerceAtLeast(cue.startMs + 350L)
            } else {
                naturalEnd
            }
            cue.copy(endMs = safeEnd)
        }
    }

    private fun wrap(text: String, maxChars: Int = 42): String {
        if (text.length <= maxChars) return text
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return text
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val extra = if (current.isEmpty()) word.length else word.length + 1
            if (current.isNotEmpty() && current.length + extra > maxChars) {
                lines += current.toString()
                current = StringBuilder(word)
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(word)
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines.joinToString("\n")
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
