package io.github.arkmind.seedy.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.webvtt.WebvttParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * A text subtitle Seedy draws itself (the server sends every text format as WebVTT): all its lines with their times,
 * so it can be shown earlier or later than the file says.
 */
class TextSubtitles(private val lines: List<Line>) {
    data class Line(val startMs: Long, val endMs: Long, val text: CharSequence)

    /** The lines on screen at [positionMs], with the viewer's timing offset (positive: shown later). */
    fun at(positionMs: Long, offsetMs: Long): List<CharSequence> {
        val t = positionMs - offsetMs
        return lines.filter { t >= it.startMs && t < it.endMs }.map { it.text }
    }

    companion object {
        @OptIn(UnstableApi::class)
        suspend fun load(url: String): TextSubtitles = withContext(Dispatchers.IO) {
            val bytes = (URL(url).openConnection() as HttpURLConnection).run {
                connectTimeout = 15_000
                readTimeout = 30_000
                try {
                    inputStream.use { it.readBytes() }
                } finally {
                    disconnect()
                }
            }
            val lines = mutableListOf<Line>()
            WebvttParser().parse(bytes, SubtitleParser.OutputOptions.allCues()) { cues ->
                val start = cues.startTimeUs / 1000
                val end = if (cues.endTimeUs > 0) cues.endTimeUs / 1000 else start + cues.durationUs / 1000
                val text = cues.cues.mapNotNull { it.text }.joinToString("\n")
                if (text.isNotBlank()) lines += Line(start, end, text)
            }
            TextSubtitles(lines.sortedBy { it.startMs })
        }
    }
}
