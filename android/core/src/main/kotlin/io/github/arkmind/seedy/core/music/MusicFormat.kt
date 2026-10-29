package io.github.arkmind.seedy.core.music

import io.github.arkmind.seedy.core.audio.audioStreamOf
import io.github.arkmind.seedy.core.audio.qualityLabel
import io.github.arkmind.seedy.core.format.TICKS_PER_SECOND
import io.github.arkmind.seedy.core.i18n.I18n
import io.github.arkmind.seedy.core.i18n.formatNumber
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.max
import kotlin.math.roundToInt

/* Words the music screens share (web/src/screens/music/parts.tsx, web/src/audio/parts.tsx). */

/** Joins the parts that aren't empty with " · ". */
fun dot(vararg parts: String?): String = parts.filter { !it.isNullOrEmpty() }.joinToString(" · ")

fun songsLabel(n: Int): String = tn(n, "{n} song", "{n} songs")
fun albumsLabel(n: Int): String = tn(n, "{n} album", "{n} albums")
fun playsLabel(n: Int?): String = if (n != null && n > 0) tn(n, "{n} play", "{n} plays") else ""

/** Jellyfin doesn't say whether a release is an album, an EP or a single: a short one is a single, a mid-length one an EP. */
enum class Release { Album, Ep, Single }

fun releaseOf(album: BaseItemDto, count: Int? = null, ticks: Long? = null): Release {
    val tags = album.tags.orEmpty().map { it.lowercase() }
    if ("single" in tags) return Release.Single
    if ("ep" in tags) return Release.Ep
    val n = count ?: album.childCount ?: 0
    val min = (ticks ?: album.runTimeTicks ?: 0L) / 600_000_000.0
    if (n in 1..3 && (min == 0.0 || min < 20)) return Release.Single
    if (n in 1..6 && (min == 0.0 || min < 32)) return Release.Ep
    return Release.Album
}

fun releaseLabel(r: Release): String = when (r) {
    Release.Single -> t("Single")
    Release.Ep -> t("EP")
    Release.Album -> t("Album")
}

/** The album's artist: its album artist, else its first artist. */
fun albumArtistOf(a: BaseItemDto): String =
    a.albumArtist?.takeIf { it.isNotBlank() } ?: a.albumArtists?.firstOrNull()?.name ?: a.artists?.firstOrNull().orEmpty()

fun yearOf(a: BaseItemDto): String = (a.productionYear ?: a.premiereDate?.year)?.toString().orEmpty()

/** A song's artists ("Ada Vale, Oren Moss"), else its album artist. */
fun artistsOf(track: BaseItemDto?): String =
    track?.artists?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: track?.albumArtist.orEmpty()

/** "1:48", "4:12", "1:02:09". */
fun clockOf(seconds: Double): String {
    val s = max(0, seconds.toInt())
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = (s % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec"
}

/** A song's length as a clock ("3:42"); empty when the server doesn't know it. */
fun trackTime(track: BaseItemDto): String = track.runTimeTicks?.takeIf { it > 0 }?.let { clockOf(it / TICKS_PER_SECOND.toDouble()) }.orEmpty()

/** "3 h 12 min", "14 h", "50 min". */
fun lengthLabel(seconds: Double): String {
    val min = max(1, (seconds / 60).roundToInt())
    val h = min / 60
    val m = min % 60
    return when {
        h == 0 -> t("{m} min", "m" to m)
        m == 0 -> t("{h} h", "h" to h)
        else -> t("{h} h {m} min", "h" to h, "m" to m)
    }
}

/** "48 songs · 3 h 12 min". */
fun playlistSub(count: Int, seconds: Double): String = dot(songsLabel(count), if (seconds > 0) lengthLabel(seconds) else null)

/** Four covers from a playlist's songs, one per album. */
fun playlistCovers(tracks: List<BaseItemDto>): List<BaseItemDto> = tracks.distinctBy { it.albumId ?: it.id }.take(4)

fun sizeLabel(bytes: Long): String {
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    if (gb >= 1) return "${formatNumber((gb * 10).roundToInt() / 10.0)} GB"
    return "${formatNumber(max(1, (bytes / 1024.0 / 1024.0).roundToInt()))} MB"
}

/** The codec alone ("FLAC"), for the album's meta line. */
fun codecOf(tracks: List<BaseItemDto>): String =
    qualityLabel(tracks.firstNotNullOfOrNull { audioStreamOf(it) }).substringBefore(" · ")

/** The full quality ("FLAC · 24-bit 96 kHz"). */
fun qualityOf(track: BaseItemDto?): String = qualityLabel(audioStreamOf(track))

/** "12 March 2024", "12 mars 2024". */
fun longDate(d: LocalDate): String =
    runCatching { d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(I18n.locale)) }.getOrElse { d.toString() }

/** When this profile last played it, epoch millis (Jellyfin's dates are UTC). */
fun lastPlayedAt(item: BaseItemDto): Long =
    item.userData?.lastPlayedDate?.let { runCatching { it.toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() } ?: 0L
