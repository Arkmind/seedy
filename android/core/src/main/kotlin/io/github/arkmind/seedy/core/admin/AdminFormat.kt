package io.github.arkmind.seedy.core.admin

import io.github.arkmind.seedy.core.format.dayMonth
import io.github.arkmind.seedy.core.format.fullDate
import io.github.arkmind.seedy.core.i18n.I18n
import io.github.arkmind.seedy.core.i18n.Lang
import io.github.arkmind.seedy.core.i18n.formatNumber
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.SessionInfoDto
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
 * How the server boards write things (web/src/screens/server/format.ts): sizes, dates and times ("2.4 MB",
 * "today at 03:00", "22 min", "Last seen yesterday"), versions, and what a session is playing. Every date function
 * takes a [Clock] so screenshots and tests can fix "now".
 */

val WeekdaysEn = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
private val WeekdaysFr = listOf("dimanche", "lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi")

/** "Sunday" in English, "dimanche" in French, from Jellyfin's English name or 0 (Sunday) to 6. */
fun weekdayName(day: String): String {
    val i = WeekdaysEn.indexOf(day)
    return if (i < 0) day else weekdayName(i)
}

fun weekdayName(day: Int): String = (if (I18n.lang == Lang.Fr) WeekdaysFr else WeekdaysEn)[((day % 7) + 7) % 7]

/** The same on its own ("Sunday", "Dimanche"). */
fun weekdayLabel(day: String): String = weekdayName(day).replaceFirstChar { it.uppercaseChar() }

/** Jellyfin's dates; .NET's DateTime.MinValue ("0001-01-01T00:00:00") means never. */
fun realDate(s: String?): Instant? {
    if (s.isNullOrBlank() || s.startsWith("0001-")) return null
    val d = try {
        Instant.parse(if (s.endsWith("Z") || s.contains('+') || s.lastIndexOf('-') > 9) s else s + "Z")
    } catch (_: DateTimeParseException) {
        runCatching { java.time.OffsetDateTime.parse(s).toInstant() }.getOrNull()
    } ?: return null
    return if (d.atZone(java.time.ZoneOffset.UTC).year < 1971) null else d
}

private fun local(d: Instant, clock: Clock): ZonedDateTime = d.atZone(clock.zone)
private fun today(clock: Clock): LocalDate = LocalDate.now(clock)

/** Whole days from that day to today (0 today, 1 yesterday, -1 tomorrow). */
fun dayDiff(d: Instant, clock: Clock): Long = ChronoUnit.DAYS.between(local(d, clock).toLocalDate(), today(clock))

/** "20:02". */
fun hhmm(d: Instant, clock: Clock): String = local(d, clock).let { "%02d:%02d".format(it.hour, it.minute) }

/** "17 Oct", or "17 Oct 2025" outside this year. */
fun shortDate(d: Instant, clock: Clock): String {
    val day = local(d, clock).toLocalDate()
    return if (day.year == today(clock).year) dayMonth(day) else fullDate(day)
}

fun fmtSize(bytes: Long): String {
    if (bytes < 0) return ""
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    val n = if (v >= 100 || i == 0) v.roundToLong().toDouble() else (v * 10).roundToLong() / 10.0
    return "${formatNumber(if (n % 1.0 == 0.0) n.toLong() else n)} ${units[i]}"
}

/** "today at 03:00", "yesterday at 03:00", "Sunday at 04:00" (this week), "12 Oct at 04:00", "12 Oct 2025". */
fun dayAt(d: Instant, clock: Clock): String {
    val days = -dayDiff(d, clock)
    val time = hhmm(d, clock)
    val z = local(d, clock)
    return when {
        days == 0L -> t("today at {time}", "time" to time)
        days == -1L -> t("yesterday at {time}", "time" to time)
        days == 1L -> t("tomorrow at {time}", "time" to time)
        days > -7 && days < 7 -> t("{day} at {time}", "day" to weekdayName(z.dayOfWeek.value % 7), "time" to time)
        z.year == today(clock).year -> t("{date} at {time}", "date" to dayMonth(z.toLocalDate()), "time" to time)
        I18n.lang == Lang.En -> fullDate(z.toLocalDate())
        else -> t("on {date}", "date" to fullDate(z.toLocalDate()))
    }
}

/** "today", "yesterday", "on Sunday", "on 12 Oct". */
fun onDay(d: Instant, clock: Clock): String {
    val days = -dayDiff(d, clock)
    val z = local(d, clock)
    return when {
        days == 0L -> t("today")
        days == -1L -> t("yesterday")
        days > -7 && days < 0 -> t("on {day}", "day" to weekdayName(z.dayOfWeek.value % 7))
        else -> t("on {date}", "date" to if (z.year == today(clock).year) dayMonth(z.toLocalDate()) else fullDate(z.toLocalDate()))
    }
}

/** How long a run took: "1 s", "12 s", "4 min", "1 h 5 min". */
fun runLength(ms: Long): String {
    val s = max(0L, (ms / 1000.0).roundToLong())
    if (s < 60) return "${max(1L, s)} s"
    val m = (s / 60.0).roundToLong()
    if (m < 60) return "$m min"
    val h = m / 60
    val rest = m % 60
    return if (rest > 0) "$h h $rest min" else "$h h"
}

/** "just now", "3 min ago", "1 hour ago", "yesterday", "4 days ago", "3 weeks ago", "6 months ago", "2 years ago". */
fun ago(d: Instant, clock: Clock): String {
    val s = ((clock.millis() - d.toEpochMilli()) / 1000.0).roundToLong()
    if (s < 45) return t("just now")
    val m = (s / 60.0).roundToLong()
    if (m < 60) return t("{n} min ago", "n" to m)
    val h = (m / 60.0).roundToLong()
    if (h < 24) return if (h == 1L) t("1 hour ago") else t("{n} hours ago", "n" to h)
    val days = (h / 24.0).roundToLong()
    if (days == 1L) return t("yesterday")
    if (days < 14) return t("{n} days ago", "n" to days)
    if (days < 60) return t("{n} weeks ago", "n" to (days / 7.0).roundToLong())
    val months = (days / 30.4).roundToLong()
    if (months < 12) return t("{n} months ago", "n" to months)
    val years = (days / 365.0).roundToLong()
    return if (years <= 1) t("1 year ago") else t("{n} years ago", "n" to years)
}

/** "today at 18:30", "yesterday at 03:00", "on 12 Oct at 03:00": the {when} of a sentence. */
fun atLabel(d: Instant, clock: Clock): String {
    val days = dayDiff(d, clock)
    return when (days) {
        0L -> t("today at {time}", "time" to hhmm(d, clock))
        1L -> t("yesterday at {time}", "time" to hhmm(d, clock))
        else -> t("on {date} at {time}", "date" to dayMonth(local(d, clock).toLocalDate()), "time" to hhmm(d, clock))
    }
}

/** Activity times: "20:02" today, "Yesterday", then "17 Oct". */
fun activityWhen(d: Instant, clock: Clock): String = when (dayDiff(d, clock)) {
    0L -> hhmm(d, clock)
    1L -> t("Yesterday")
    else -> shortDate(d, clock)
}

/** "Last seen yesterday", "Last seen 3 months ago", "Last seen 3 days ago on iPhone"; "Never signed in". */
fun lastSeen(d: Instant?, clock: Clock, device: String? = null): String {
    if (d == null || local(d, clock).year < 2000) return t("Never signed in")
    val seen = lastSeenText(d, clock)
    return if (!device.isNullOrEmpty()) t("{seen} on {device}", "seen" to seen, "device" to device) else seen
}

private fun lastSeenText(d: Instant, clock: Clock): String {
    val mins = ((clock.millis() - d.toEpochMilli()) / 60_000.0).roundToLong()
    val days = dayDiff(d, clock)
    return when {
        mins < 60 -> t("Last seen {n} min ago", "n" to max(1L, mins))
        days == 0L -> t("Last seen today at {time}", "time" to hhmm(d, clock))
        days == 1L -> t("Last seen yesterday")
        days < 7 -> t("Last seen {n} days ago", "n" to days)
        days < 30 -> tn((days / 7.0).roundToInt(), "Last seen {n} week ago", "Last seen {n} weeks ago")
        days < 365 -> tn(max(1, (days / 30.4).roundToInt()), "Last seen {n} month ago", "Last seen {n} months ago")
        else -> tn((days / 365.0).roundToInt(), "Last seen a year ago", "Last seen {n} years ago")
    }
}

/** "up 6 days", "up 3 hours", "up 12 min". */
fun uptime(since: Instant, clock: Clock): String {
    val mins = max(0L, ((clock.millis() - since.toEpochMilli()) / 60_000.0).roundToLong())
    if (mins < 60) return t("up {n} min", "n" to max(1L, mins))
    val hours = (mins / 60.0).roundToInt()
    if (hours < 24) return tn(hours, "up {n} hour", "up {n} hours")
    return tn(hours / 24, "up {n} day", "up {n} days")
}

/** "Linux x64": Jellyfin 12 no longer reports the OS, so Seedy's status says it, or the server's paths do. */
fun platformLabel(info: SystemInfo, knownOs: String? = null): String {
    val arch = info.systemArchitecture.orEmpty().lowercase()
    val p = info.programDataPath ?: info.cachePath ?: ""
    var os = knownOs?.takeIf { it.isNotEmpty() } ?: info.operatingSystemDisplayName?.takeIf { it.isNotEmpty() } ?: info.operatingSystem.orEmpty()
    if (os.isEmpty()) {
        os = when {
            Regex("^[a-zA-Z]:[\\\\/]").containsMatchIn(p) || p.startsWith("\\\\") -> "Windows"
            p.startsWith("/Users/") || p.contains("Library/Application Support") -> "macOS"
            p.startsWith("/") -> "Linux"
            else -> ""
        }
    }
    return listOf(os, arch).filter { it.isNotEmpty() }.joinToString(" ")
}

/** "12.2.0" → "12.2". */
fun majorMinor(v: String?): String = v?.split('.')?.take(2)?.joinToString(".").orEmpty()

/** "12.2.0" → "12.2.0"; "0.1.0.0" → "0.1.0". */
fun shortVersion(v: String?, parts: Int = 3): String {
    if (v.isNullOrEmpty()) return ""
    val bits = v.split('.').toMutableList()
    while (bits.size > parts && bits.last() == "0") bits.removeAt(bits.lastIndex)
    return bits.joinToString(".")
}

/** Dotted versions compared numerically. */
fun cmpVersion(a: String?, b: String?): Int {
    val pa = a.orEmpty().split('.').map { it.toIntOrNull() ?: 0 }
    val pb = b.orEmpty().split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until max(pa.size, pb.size)) {
        val d = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
        if (d != 0) return if (d < 0) -1 else 1
    }
    return 0
}

/** "21 Mbps", "8 Mbps", "2.5 Mbps". */
fun mbps(bps: Long?): String {
    if (bps == null || bps <= 0) return ""
    val m = bps / 1_000_000.0
    val n: Number = if (m >= 10) m.roundToLong() else (m * 10).roundToLong() / 10.0
    return "${formatNumber(n)} Mbps"
}

/** Initials for a name ("Arky" → "A"). */
fun initial(name: String): String = name.trim().firstOrNull()?.uppercase() ?: "?"

/* ── Playback descriptions for "Playing now" ── */

private val Codecs = mapOf(
    "hevc" to "HEVC", "h265" to "HEVC", "h264" to "H.264", "avc" to "H.264", "av1" to "AV1", "vp9" to "VP9", "vp8" to "VP8",
    "mpeg2video" to "MPEG-2", "mpeg4" to "MPEG-4", "vc1" to "VC-1", "aac" to "AAC", "ac3" to "AC3", "eac3" to "EAC3", "dts" to "DTS",
    "truehd" to "TrueHD", "flac" to "FLAC", "opus" to "Opus", "mp3" to "MP3", "vorbis" to "Vorbis", "pcm_s16le" to "PCM", "alac" to "ALAC",
)

fun codecName(c: String?): String = if (c.isNullOrEmpty()) "" else Codecs[c.lowercase()] ?: c.uppercase()

private val Accelerators = mapOf(
    "qsv" to "Intel QSV", "nvenc" to "NVIDIA NVENC", "amf" to "AMD AMF", "vaapi" to "VA-API", "videotoolbox" to "VideoToolbox",
    "rkmpp" to "Rockchip MPP", "v4l2m2m" to "V4L2",
)

private fun channels(n: Int?): String = when (n) {
    null, 0 -> ""
    1 -> t("mono")
    2 -> t("stereo")
    6 -> "5.1"
    8 -> "7.1"
    else -> t("{n} ch", "n" to n)
}

private fun resolution(w: Int?, h: Int?): String {
    val ww = w ?: 0
    val hh = h ?: 0
    return when {
        ww == 0 && hh == 0 -> ""
        ww >= 3200 || hh >= 2000 -> "4K"
        ww >= 1800 || hh >= 1000 -> "1080p"
        ww >= 1200 || hh >= 700 -> "720p"
        else -> "SD"
    }
}

private fun rangeLabel(v: MediaStream?): String {
    val type = v?.videoRangeType?.serialName.orEmpty()
    return when {
        type.isEmpty() || type == "SDR" || type == "Unknown" -> ""
        type.startsWith("DOVI") -> "Dolby Vision"
        type == "HDR10Plus" -> "HDR10+"
        else -> type
    }
}

private fun reason(key: String, video: String, audio: String): String? = when (key) {
    "VideoCodecNotSupported" -> if (video.isNotEmpty()) t("the device can’t play {codec}", "codec" to video) else t("the device can’t play this video")
    "AudioCodecNotSupported" -> if (audio.isNotEmpty()) t("the device can’t play {codec}", "codec" to audio) else t("the device can’t play this audio")
    "ContainerNotSupported" -> t("the device can’t open this file type")
    "ContainerBitrateExceedsLimit" -> t("the file is above the streaming limit")
    "VideoBitrateNotSupported" -> t("the video is above the streaming limit")
    "AudioBitrateNotSupported" -> t("the audio is above the streaming limit")
    "SubtitleCodecNotSupported" -> t("subtitles are burned in")
    "VideoResolutionNotSupported" -> t("the resolution is too high for the device")
    "VideoRangeTypeNotSupported" -> t("the device can’t show HDR")
    "VideoLevelNotSupported" -> t("the video profile level is too high")
    "VideoProfileNotSupported" -> t("the device can’t play this video profile")
    "VideoBitDepthNotSupported" -> t("the device can’t play this bit depth")
    "AudioChannelsNotSupported" -> t("the device can’t play this many audio channels")
    "InterlacedVideoNotSupported" -> t("the video is interlaced")
    "DirectPlayError" -> t("direct play failed")
    else -> null
}

enum class PlayKind { Direct, Stream, Transcode }

fun playKind(s: SessionInfoDto): PlayKind = when (s.playState?.playMethod) {
    PlayMethod.TRANSCODE -> if (s.transcodingInfo?.isVideoDirect == true) PlayKind.Stream else PlayKind.Transcode
    PlayMethod.DIRECT_STREAM -> PlayKind.Stream
    else -> PlayKind.Direct
}

/** "Direct play", "Direct stream", "Transcoding" (English: show with t()). */
fun PlayKind.label(): String = when (this) {
    PlayKind.Direct -> "Direct play"
    PlayKind.Stream -> "Direct stream"
    PlayKind.Transcode -> "Transcoding"
}

/**
 * What a session is playing and how, as the boards write it after the method pill:
 * "4K HEVC HDR10 · EAC3 5.1 · 21 Mbps", "HEVC to H.264 on Intel QSV, 8 Mbps. The device can’t play HEVC.",
 * "Audio TrueHD to AAC stereo · video copied".
 */
fun playDetail(s: SessionInfoDto): String {
    val item = s.nowPlayingItem
    val streams = item?.mediaStreams ?: item?.mediaSources?.firstOrNull()?.mediaStreams ?: emptyList()
    val video = streams.firstOrNull { it.type == MediaStreamType.VIDEO }
    val audioIndex = s.playState?.audioStreamIndex
    val audio = (if (audioIndex != null) streams.firstOrNull { it.type == MediaStreamType.AUDIO && it.index == audioIndex } else null)
        ?: streams.firstOrNull { it.type == MediaStreamType.AUDIO && it.isDefault }
        ?: streams.firstOrNull { it.type == MediaStreamType.AUDIO }
    val ti = s.transcodingInfo
    val srcVideo = codecName(video?.codec)
    val srcAudio = codecName(audio?.codec)
    when (playKind(s)) {
        PlayKind.Direct -> {
            val v = listOf(resolution(video?.width, video?.height), srcVideo, rangeLabel(video)).filter { it.isNotEmpty() }.joinToString(" ")
            val layout = audio?.channelLayout?.replace(Regex("\\(.*\\)"), "")?.takeIf { it.isNotEmpty() } ?: channels(audio?.channels)
            val a = listOf(srcAudio, layout).filter { it.isNotEmpty() }.joinToString(" ")
            val rate = mbps((item?.mediaSources?.firstOrNull()?.bitrate ?: video?.bitRate)?.toLong())
            return listOf(v, a, rate).filter { it.isNotEmpty() }.joinToString(" · ")
        }
        PlayKind.Stream -> {
            val parts = mutableListOf<String>()
            if (ti != null && !ti.isAudioDirect && !ti.audioCodec.isNullOrEmpty()) {
                val to = codecName(ti.audioCodec) + (ti.audioChannels?.let { " " + channels(it) } ?: "")
                parts += if (srcAudio.isNotEmpty()) t("Audio {from} to {to}", "from" to srcAudio, "to" to to) else t("Audio track to {to}", "to" to to)
            } else if (!ti?.container.isNullOrEmpty()) {
                parts += t("Into {container}", "container" to ti!!.container!!.uppercase())
            }
            parts += if (ti != null && !ti.isAudioDirect) t("video copied") else t("video and audio copied")
            return parts.joinToString(" · ")
        }
        PlayKind.Transcode -> {
            val hwType = ti?.hardwareAccelerationType?.serialName
            val hw = if (hwType != null && hwType != "none") Accelerators[hwType.lowercase()] ?: hwType else ""
            val to = codecName(ti?.videoCodec)
            var line = when {
                srcVideo.isNotEmpty() && to.isNotEmpty() -> if (hw.isNotEmpty()) t("{from} to {to} on {hw}", "from" to srcVideo, "to" to to, "hw" to hw) else t("{from} to {to}", "from" to srcVideo, "to" to to)
                to.isNotEmpty() -> if (hw.isNotEmpty()) t("To {to} on {hw}", "to" to to, "hw" to hw) else t("To {to}", "to" to to)
                hw.isNotEmpty() -> t("Video on {hw}", "hw" to hw)
                else -> t("Video converted")
            }
            mbps(ti?.bitrate?.toLong()).takeIf { it.isNotEmpty() }?.let { line += ", $it" }
            val first = ti?.transcodeReasons.orEmpty().firstNotNullOfOrNull { reason(it.serialName, srcVideo, srcAudio) }
            return if (first != null) t("{line}. {reason}.", "line" to line, "reason" to first.replaceFirstChar { it.uppercaseChar() }) else "$line."
        }
    }
}

/** A session's position as a fraction of the title (0–1). */
fun playProgress(s: SessionInfoDto): Float {
    val total = s.nowPlayingItem?.runTimeTicks ?: 0L
    if (total <= 0) return 0f
    return ((s.playState?.positionTicks ?: 0L).toDouble() / total).coerceIn(0.0, 1.0).toFloat()
}

/** Percent as the boards write it ("42%"). */
fun percent(p: Double?): String = "${floor(p ?: 0.0).toInt()}%"
