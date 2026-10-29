package io.github.arkmind.seedy.player.audio

import kotlinx.serialization.Serializable
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.PlayMethod
import java.util.UUID

/* The audio player's state (web/src/audio/engine.ts, for Android): what plays, the queue around it, and an audiobook's
   chapters. Times are in seconds. */

enum class AudioMode { Music, Audiobook }

enum class Repeat { Off, All, One }

/** What a queue plays from, shown as "Playing from album …". */
enum class SourceKind { Album, Playlist, Artist, Mix, Library, Genre, Songs, Folder, Audiobook }

@Serializable
data class Source(val kind: SourceKind, val id: String? = null, val name: String)

/** One place in the queue: the same song can be in it twice, so entries have their own [key]. */
@Serializable
data class Entry(val key: String, val track: BaseItemDto)

@Serializable
data class BookChapter(val index: Int, val name: String, val start: Double, val end: Double)

/** One file of a book in several files: where it starts in the whole book. */
@Serializable
data class BookPart(val id: String, val start: Double, val duration: Double)

/** The audiobook playing: its chapters over the whole book, and its files when it has several. */
@Serializable
data class BookInfo(
    val id: String,
    val name: String,
    val author: String? = null,
    val narrator: String? = null,
    val duration: Double,
    val chapters: List<BookChapter>,
    val parts: List<BookPart>? = null,
)

/** The sleep timer: at a time (epoch millis, [minutes] as set), or at the end of a chapter. */
sealed interface Sleep {
    data class Time(val at: Long, val minutes: Int) : Sleep
    data class Chapter(val index: Int) : Sleep
}

/** How the current file reaches this phone, and its quality ("FLAC 24-bit 96 kHz"). */
data class StreamInfo(val method: PlayMethod, val quality: String)

data class AudioState(
    val mode: AudioMode? = null,
    val current: Entry? = null,
    /** The current song came from "Added by you"; context[pos] is then the song played before it. */
    val fromAdded: Boolean = false,
    /** What the queue plays from, in play order (shuffled when [shuffle] is on). */
    val context: List<Entry> = emptyList(),
    /** The unshuffled order, while shuffled. */
    val original: List<Entry>? = null,
    val pos: Int = 0,
    /** The songs the person queued themselves ("Added by you"), played before the rest of [context]. */
    val added: List<Entry> = emptyList(),
    val source: Source? = null,
    val shuffle: Boolean = false,
    val repeat: Repeat = Repeat.Off,
    /** Playing, or about to (loading); false when paused. */
    val playing: Boolean = false,
    val loading: Boolean = false,
    /** Audiobooks only. */
    val speed: Float = 1f,
    val sleep: Sleep? = null,
    val book: BookInfo? = null,
    val stream: StreamInfo? = null,
) {
    /** Up next: the queued songs, then the rest of the context. */
    val upNext: List<Entry> get() = added + context.drop(pos + 1)
    val isBook: Boolean get() = mode == AudioMode.Audiobook
}

/** Where the current song or book is, and its length; updated a few times a second while playing. */
data class AudioTime(val position: Double = 0.0, val duration: Double = 0.0) {
    val fraction: Float get() = if (duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f
}

data class PlayOptions(
    /** Where to start the first item, in ticks (an audiobook: in the whole book). */
    val startTicks: Long? = null,
    val shuffle: Boolean = false,
    val source: Source? = null,
    /** [AudioEngine.playContainer]: start at this song of the album or playlist. */
    val startId: UUID? = null,
)

/** A queue someone played lately ("Jump back in"). */
@Serializable
data class RecentSource(val kind: SourceKind, val id: String, val name: String, val at: Long)
