package io.github.arkmind.seedy.core.audio

import io.github.arkmind.seedy.core.format.TICKS_PER_SECOND
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PersonKind
import java.time.Instant
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val AudioTypes = setOf(BaseItemKind.AUDIO, BaseItemKind.AUDIO_BOOK, BaseItemKind.MUSIC_ALBUM, BaseItemKind.MUSIC_ARTIST, BaseItemKind.MUSIC_GENRE)

/** Whether "Play" on this item belongs to the audio player rather than the video one. */
fun isAudioItem(item: BaseItemDto): Boolean = when {
    item.type in AudioTypes -> true
    item.type == BaseItemKind.PLAYLIST || item.type == BaseItemKind.FOLDER -> item.mediaType == MediaType.AUDIO
    item.type == BaseItemKind.COLLECTION_FOLDER || item.type == BaseItemKind.USER_VIEW -> item.collectionType == CollectionType.MUSIC
    else -> item.mediaType == MediaType.AUDIO
}

/** The audio stream of a track (its first source's, or its own list's). */
fun audioStreamOf(item: BaseItemDto?): MediaStream? =
    (item?.mediaSources?.firstOrNull()?.mediaStreams ?: item?.mediaStreams).orEmpty().firstOrNull { it.type == MediaStreamType.AUDIO }

private val Codecs = mapOf(
    "flac" to "FLAC", "alac" to "ALAC", "mp3" to "MP3", "aac" to "AAC", "opus" to "Opus", "vorbis" to "Vorbis", "wav" to "WAV", "ac3" to "AC3",
    "eac3" to "E-AC3", "dsd_lsbf" to "DSD", "dsd_msbf" to "DSD", "ape" to "APE", "wavpack" to "WavPack", "wma" to "WMA", "pcm_s16le" to "PCM", "pcm_s24le" to "PCM",
)
private val Lossless = setOf("flac", "alac", "wav", "ape", "wavpack", "pcm_s16le", "pcm_s24le", "pcm_s32le", "dsd_lsbf", "dsd_msbf")

/** "FLAC · 24-bit 96 kHz", "MP3 · 320 kbps"; [sep] joins the codec and the rest (the Now playing badge uses a space). */
fun qualityLabel(s: MediaStream?, sep: String = " · "): String {
    val raw = s?.codec?.takeIf { it.isNotBlank() } ?: return ""
    val codec = Codecs[raw.lowercase()] ?: raw.uppercase()
    val khz = s.sampleRate?.let { r -> "${(r / 100.0).roundToInt() / 10.0} kHz".replace(".0 kHz", " kHz") }.orEmpty()
    if (raw.lowercase() in Lossless) {
        val rest = listOf(s.bitDepth?.let { "$it-bit" }.orEmpty(), khz).filter { it.isNotEmpty() }.joinToString(" ")
        return if (rest.isNotEmpty()) codec + sep + rest else codec
    }
    val kbps = s.bitRate?.let { "${(it / 1000.0).roundToInt()} kbps" }.orEmpty()
    return if (kbps.isNotEmpty()) codec + sep + kbps else codec
}

/**
 * Who wrote it: Jellyfin keeps book authors as people (Author, or Writer from ComicInfo), audiobook authors as album
 * artists or artists. Pairs of name and person or artist id (null when Jellyfin has none).
 */
fun authorsOf(item: BaseItemDto): List<Pair<String, String?>> {
    val people = item.people.orEmpty()
    val authors = people.filter { it.type == PersonKind.AUTHOR }.ifEmpty { people.filter { it.type == PersonKind.WRITER } }
    if (authors.isNotEmpty()) return authors.map { it.name.orEmpty() to it.id.toString() }
    item.albumArtists?.takeIf { it.isNotEmpty() }?.let { list -> return list.map { it.name.orEmpty() to it.id.toString() } }
    item.artistItems?.takeIf { it.isNotEmpty() }?.let { list -> return list.map { it.name.orEmpty() to it.id.toString() } }
    item.artists?.takeIf { it.isNotEmpty() }?.let { list -> return list.map { it to null } }
    return listOfNotNull(item.albumArtist?.takeIf { it.isNotBlank() }?.let { it to null })
}

/** "Hale & Ito" for two, "Hale, Ito & Moss" for more. */
fun authorLine(item: BaseItemDto): String {
    val names = authorsOf(item).map { it.first }
    if (names.size <= 1) return names.firstOrNull().orEmpty()
    return names.dropLast(1).joinToString(", ") + " & " + names.last()
}

/**
 * Who reads an audiobook: a Narrator (Jellyfin 12 makes one from the composer tag), else a composer, else an artist who
 * isn't the author, as many rips tag it (Jellyfin lists those as actors; the album artist is the author).
 */
fun narratorOf(item: BaseItemDto): String? {
    val people = item.people.orEmpty()
    val tagged = people.firstOrNull { it.type == PersonKind.NARRATOR || it.role == "Narrator" }?.name
        ?: people.firstOrNull { it.type == PersonKind.COMPOSER }?.name
    if (tagged != null || item.type != BaseItemKind.AUDIO_BOOK) return tagged
    fun fold(s: String?) = s.orEmpty().trim().lowercase()
    val authors = (people.filter { it.type == PersonKind.AUTHOR || it.type == PersonKind.WRITER }.map { it.name } + item.albumArtist).map(::fold).toSet()
    return people.firstOrNull { it.type == PersonKind.ACTOR && fold(it.name) !in authors }?.name
        ?: if (item.albumArtist != null) item.artists?.firstOrNull { fold(it) !in authors } else null
}

/**
 * Seedy's saved place in an audiobook over Jellyfin's resume point: it wins unless another app played the book more than
 * a minute after it was saved. Locator: seconds into the book.
 */
fun mergePlace(item: BaseItemDto, place: Reading?): BaseItemDto {
    val secs = place?.locator?.toDoubleOrNull()
    if (place == null || (!place.finished && (secs == null || secs < 0))) return item
    val ud = item.userData
    val jellyfinAt = ud?.lastPlayedDate?.let { runCatching { it.atZone(java.time.ZoneOffset.UTC).toInstant() }.getOrNull() }
    val seedyAt = place.updatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    if (jellyfinAt != null && jellyfinAt.toEpochMilli() > (seedyAt?.toEpochMilli() ?: 0L) + 60_000) return item
    val pos = if (place.finished) 0L else ((secs ?: 0.0) * TICKS_PER_SECOND).roundToLong()
    val total = item.runTimeTicks ?: 0L
    val data = ud ?: return item
    return item.copy(
        userData = data.copy(
            played = place.finished, playbackPositionTicks = pos,
            playedPercentage = if (!place.finished && total > 0) pos * 100.0 / total else null,
        ),
    )
}
