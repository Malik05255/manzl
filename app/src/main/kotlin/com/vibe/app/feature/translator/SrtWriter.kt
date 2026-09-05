package com.vibe.app.feature.translator

import java.io.File
import kotlin.math.max

/** One translated subtitle cue using movie-relative timestamps. */
data class ArabicSubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

object SrtWriter {
    fun write(file: File, cues: List<ArabicSubtitleCue>): File {
        file.parentFile?.mkdirs()
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            cues.sortedBy { it.startMs }.forEachIndexed { index, cue ->
                writer.appendLine((index + 1).toString())
                writer.append(formatTime(cue.startMs))
                writer.append(" --> ")
                writer.appendLine(formatTime(max(cue.endMs, cue.startMs + 400L)))
                writer.appendLine(wrapArabic(cue.text))
                writer.appendLine()
            }
        }
        return file
    }

    /** Keep subtitles readable on phones without changing their wording. */
    private fun wrapArabic(text: String, maxLineLength: Int = 42): String {
        val words = text.replace(Regex("\\s+"), " ").trim().split(' ')
        if (words.size <= 1) return text.trim()

        val lines = mutableListOf<String>()
        val current = StringBuilder()
        for (word in words) {
            if (current.isNotEmpty() && current.length + 1 + word.length > maxLineLength) {
                lines += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
        }
        if (current.isNotEmpty()) lines += current.toString()

        // SRT supports more lines, but two-line cues are substantially easier to read on a phone.
        return if (lines.size <= 2) {
            lines.joinToString("\n")
        } else {
            val midpoint = words.size / 2
            listOf(words.take(midpoint).joinToString(" "), words.drop(midpoint).joinToString(" "))
                .joinToString("\n")
        }
    }

    private fun formatTime(timeMs: Long): String {
        val safe = timeMs.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe % 3_600_000L) / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val millis = safe % 1_000L
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
    }
}