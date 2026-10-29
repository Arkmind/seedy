package io.github.arkmind.seedy.mobile.nav

import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.arkmind.seedy.core.audio.isAudioItem
import io.github.arkmind.seedy.player.audio.AudioEngine
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.MediaType
import java.util.UUID

/** The pages the phone app opens over its tabs. */
sealed interface Route {
    data class Details(val id: UUID) : Route
    data class Library(val id: UUID) : Route
    data object Search : Route
    data object LiveTv : Route
    data object Settings : Route
    /** A settings page: "profile", "home", "playback", "subtitles", "display", "gestures", "about"… */
    data class SettingsPage(val page: String) : Route
    data class Player(val id: UUID) : Route
    /** A title that's on Seerr but not on the server (search, Discover…). */
    data class SeerrTitle(val mediaType: String, val tmdbId: Int) : Route
    /** A Discover row's "See all" (a [io.github.arkmind.seedy.core.requests.TitlesKind] key). */
    data class SeerrTitles(val kind: String) : Route
    /** A server page for admins: "server" (the hub), "users", "user" (with [id]), "libraries", "playback"… */
    data class Admin(val page: String, val id: String? = null) : Route
    /** A music library (board "Android · Music"): Home, Albums, Artists, Playlists, Songs. */
    data class Music(val id: UUID) : Route
    /** An album or a playlist (board "Android · Album"). */
    data class Album(val id: UUID) : Route
    /** Board "Android · Artist". */
    data class Artist(val id: UUID) : Route
    /** The full-screen music player (board "Android · Now playing"). */
    data object NowPlaying : Route
    /** A books library (board "Android · Books"): All, Ebooks, Audiobooks, Comics, Series. */
    data class Books(val id: UUID) : Route
    /** A book or an audiobook (board "Android · Book details"). */
    data class Book(val id: UUID) : Route
    /** The reader (board "Android · Reader"): EPUB, comics, PDF. */
    data class Reader(val id: UUID) : Route
    /** The full-screen audiobook player (board "Android · Audiobook"). */
    data object AudiobookPlayer : Route
}

/** Moves between pages; every screen reaches it through [LocalMobileNav]. */
class MobileNav(private val stack: SnapshotStateList<Any>, private val selectTab: (io.github.arkmind.seedy.mobile.parts.Tab) -> Unit = {}) {
    /** Back to the tabs, on [tab] (the tab bar under a page). */
    fun toTab(tab: io.github.arkmind.seedy.mobile.parts.Tab) {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        selectTab(tab)
    }

    fun go(route: Route) {
        stack.add(route)
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /**
     * Opens a title where it belongs: an episode opens its show, a library or folder its grid (music and books their own
     * pages), a channel plays, a song its album, a book or audiobook its page.
     */
    fun open(item: BaseItemDto) = when (item.type) {
        BaseItemKind.EPISODE, BaseItemKind.SEASON -> go(Route.Details(item.seriesId ?: item.id))
        BaseItemKind.COLLECTION_FOLDER, BaseItemKind.USER_VIEW -> openLibrary(item)
        BaseItemKind.FOLDER -> go(Route.Library(item.id))
        BaseItemKind.TV_CHANNEL -> go(Route.Player(item.id))
        BaseItemKind.MUSIC_ALBUM -> go(Route.Album(item.id))
        BaseItemKind.PLAYLIST -> if (item.mediaType == MediaType.AUDIO) go(Route.Album(item.id)) else go(Route.Details(item.id))
        BaseItemKind.MUSIC_ARTIST -> go(Route.Artist(item.id))
        BaseItemKind.AUDIO -> item.albumId?.let { go(Route.Album(it)) } ?: Unit
        BaseItemKind.BOOK, BaseItemKind.AUDIO_BOOK -> go(Route.Book(item.id))
        else -> go(Route.Details(item.id))
    }

    /** A library's page: music and books have their own, Live TV its guide, the rest the grid. */
    fun openLibrary(view: BaseItemDto) = when (view.collectionType) {
        CollectionType.MUSIC -> go(Route.Music(view.id))
        CollectionType.BOOKS -> go(Route.Books(view.id))
        CollectionType.LIVETV -> go(Route.LiveTv)
        else -> go(Route.Library(view.id))
    }

    /** "Play": songs, albums, artists and audiobooks go to the audio player (its mini bar), everything else to the video one. */
    fun play(item: BaseItemDto) {
        val audio = audio
        if (audio != null && isAudioItem(item)) audio.playItem(item) else go(Route.Player(item.id))
    }

    /** The app's audio player, set by the shell. */
    var audio: AudioEngine? = null
}

val LocalMobileNav = staticCompositionLocalOf<MobileNav> { error("No phone navigation here") }
