package io.github.arkmind.seedy.mobile.music

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.music.AlbumViewModel
import io.github.arkmind.seedy.core.music.ArtistViewModel
import io.github.arkmind.seedy.core.music.MusicApi
import io.github.arkmind.seedy.core.music.MusicLibraryViewModel
import io.github.arkmind.seedy.core.music.NowPlayingViewModel
import io.github.arkmind.seedy.core.music.albumArtistOf
import io.github.arkmind.seedy.core.music.artistsOf
import io.github.arkmind.seedy.core.music.dot
import io.github.arkmind.seedy.mobile.audio.LocalAudio
import io.github.arkmind.seedy.mobile.audio.miniBarSpace
import io.github.arkmind.seedy.mobile.audio.openOutputSwitcher
import io.github.arkmind.seedy.mobile.audio.rememberAudioOutput
import io.github.arkmind.seedy.mobile.manage.ItemMenuSheet
import io.github.arkmind.seedy.mobile.manage.MenuEntry
import io.github.arkmind.seedy.mobile.nav.Route
import io.github.arkmind.seedy.player.audio.Source
import io.github.arkmind.seedy.player.audio.SourceKind
import io.github.arkmind.seedy.ui.components.coverUrl
import io.github.arkmind.seedy.ui.components.webId
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.currentSession
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/** Opens a card where it belongs: an album or any playlist in the album page, an artist in theirs, the rest as the app does. */
private fun MusicController.open(item: BaseItemDto) = when (item.type) {
    BaseItemKind.PLAYLIST, BaseItemKind.MUSIC_ALBUM -> nav.go(Route.Album(item.id))
    BaseItemKind.MUSIC_ARTIST -> nav.go(Route.Artist(item.id))
    else -> nav.open(item)
}

/** The player's state as the pages' "what plays" (nothing in screenshot tests). */
@Composable
private fun rememberPlaying(): Playing {
    val audio = LocalAudio.current ?: return Playing()
    val s by audio.state.collectAsStateWithLifecycle()
    return Playing.of(s)
}

/** Board "Android · Music": a music library's Home, Albums, Artists, Playlists, Songs and Genres. */
@Composable
fun MusicRoute(id: UUID) {
    val session = currentSession()
    val audio = LocalAudio.current
    val c = rememberMusicController()
    val vm = viewModel(key = "music:$id") {
        MusicLibraryViewModel(session, id) {
            audio?.recent?.value.orEmpty().filter { it.kind == SourceKind.Album || it.kind == SourceKind.Playlist || it.kind == SourceKind.Artist }.map { it.id to it.at }
        }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val playing = rememberPlaying()
    if (audio != null) {
        LaunchedEffect(audio) {
            var n = 0
            audio.played.collect { if (++n % 3 == 0) vm.reloadJump() }
        }
        LaunchedEffect(audio) { audio.favorites.collect { (tid, on) -> vm.patchFavorite(tid, on) } }
    }
    // A new queue joins Jump back in.
    LaunchedEffect(playing.sourceId) { if (playing.sourceId != null) vm.reloadJump() }
    // Back from a genre's albums goes to all genres first.
    BackHandler(enabled = state.genre != null) { vm.openGenre(null) }
    Box(Modifier.fillMaxSize()) {
        MusicLibraryScreen(
            state, playing,
            MusicActions(
                onBack = c.nav::back,
                onSearch = { c.nav.go(Route.Search) },
                onCast = { c.openCast() },
                onTab = vm::setTab,
                onOpen = { c.open(it) },
                onMix = { m ->
                    vm.mixTracks(m, { list -> c.play(list, 0, Source(SourceKind.Mix, m.seed, m.title)) }, { c.say(t("Couldn’t start the mix")) })
                },
                onRecentAll = { vm.showAlbums("DateCreated") },
                onNewPlaylist = c::newPlaylist,
                onGenre = vm::openGenre,
                onGenrePlay = { g -> vm.genreSongs(g) { c.play(it, 0, Source(SourceKind.Genre, g.id.toString(), g.name.orEmpty())) } },
                onGenreMix = { g -> vm.genreMix(g) { c.play(it, 0, Source(SourceKind.Mix, g.id.toString(), t("{genre} mix", "genre" to g.name.orEmpty()))) } },
                onFavorites = vm::setFavorites,
                onSort = vm::setSort,
                onMore = vm::loadMore,
                onSong = { list, i ->
                    if (playing.isTrack(list[i].id)) audio?.togglePlay() else c.play(list, i, Source(SourceKind.Library, id.toString(), state.name))
                },
                onSongMenu = { c.openMenu(it) },
                onRetry = vm::retry,
            ),
            bottomPadding = miniBarSpace(),
        )
        MusicOverlays(c, onListsChanged = vm::playlistsChanged)
    }
}

/** Board "Android · Album": an album or a playlist. */
@Composable
fun AlbumRoute(id: UUID) {
    val session = currentSession()
    val audio = LocalAudio.current
    val images = LocalViewer.current.images
    val c = rememberMusicController()
    val vm = viewModel(key = "album:$id") { AlbumViewModel(session, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val playing = rememberPlaying()
    var menu by remember { mutableStateOf(false) }
    if (audio != null) {
        LaunchedEffect(audio) { audio.played.collect(vm::onPlayed) }
        LaunchedEffect(audio) { audio.favorites.collect { (tid, on) -> vm.patchFavorite(tid, on) } }
    }
    LaunchedEffect(state.message) { state.message?.let { c.say(it); vm.messageShown() } }
    val item = state.item
    val tracks = state.tracks.orEmpty()
    val playlist = state.playlist
    val name = item?.name.orEmpty()
    val source = Source(if (playlist) SourceKind.Playlist else SourceKind.Album, id.toString(), name)
    val tintFrom = if (playlist) tracks.firstOrNull() else item
    val tint = rememberCoverTint(coverUrl(tintFrom, images, 64), webId(tintFrom?.albumId ?: tintFrom?.id)).first
    val here = item != null && playing.trackId != null &&
        (playing.isSource(item.id) || (!playlist && playing.albumId == item.id && playing.sourceKind == SourceKind.Album))
    val artist = state.artistRef
    Box(Modifier.fillMaxSize()) {
        AlbumScreen(
            state, playing, tint,
            AlbumActions(
                onBack = c.nav::back,
                onCast = { c.openCast(tracks.map { it.id }) },
                onMore = { if (item != null) menu = true },
                onArtist = { artist?.let { c.nav.go(Route.Artist(it.id)) } },
                onFavorite = vm::toggleFavorite,
                onDownload = { c.download(tracks, dot(item?.let(::albumArtistOf), name)) },
                onQueue = { c.queue(tracks, false, name) },
                onShuffle = { c.play(tracks, 0, source, shuffle = true) },
                onPlay = { if (here) audio?.togglePlay() else c.play(tracks, 0, source) },
                onTrack = { i -> if (playing.isTrack(tracks[i].id)) audio?.togglePlay() else c.play(tracks, i, source) },
                onTrackMenu = { tk ->
                    c.openMenu(
                        tk, id,
                        if (playlist && tk.playlistItemId != null) listOf(MenuEntry(MusicIcons.Trash, t("Remove from this playlist"), run = { vm.removeFromPlaylist(tk) })) else emptyList(),
                    )
                },
                onOpen = { c.open(it) },
                onDiscography = { artist?.let { c.nav.go(Route.Artist(it.id)) } },
                onRetry = vm::loadTracks,
            ),
            bottomPadding = miniBarSpace(),
        )
        if (menu && item != null) {
            val entries = buildList {
                add(MenuEntry(MusicIcons.QueueNext, t("Play next"), run = { c.queue(tracks, true, name) }))
                add(MenuEntry(MusicIcons.Queue, t("Add to queue"), run = { c.queue(tracks, false, name) }))
                add(MenuEntry(MusicIcons.Mix, t("Instant mix"), run = { vm.mix { c.play(it, 0, Source(SourceKind.Mix, id.toString(), t("{name} mix", "name" to name))) } }))
                if (!playlist) add(MenuEntry(MusicIcons.PlaylistAdd, t("Add to playlist"), more = true, run = { c.addToPlaylist(listOf(item), name) }))
                else if (tracks.isNotEmpty()) add(MenuEntry(MusicIcons.PlaylistAdd, t("Add to playlist"), more = true, run = { c.addToPlaylist(tracks, name) }))
                if (!playlist && artist != null) add(MenuEntry(MusicIcons.Artist, t("Go to artist"), line = true, run = { c.nav.go(Route.Artist(artist.id)) }))
            }
            ItemMenuSheet(name, entries) { menu = false }
        }
        MusicOverlays(c)
    }
}

/** Board "Android · Artist". */
@Composable
fun ArtistRoute(id: UUID) {
    val session = currentSession()
    val audio = LocalAudio.current
    val images = LocalViewer.current.images
    val c = rememberMusicController()
    val vm = viewModel(key = "artist:$id") { ArtistViewModel(session, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val playing = rememberPlaying()
    var menu by remember { mutableStateOf(false) }
    if (audio != null) {
        LaunchedEffect(audio) { audio.played.collect { vm.onPlayed() } }
        LaunchedEffect(audio) { audio.favorites.collect { (tid, on) -> vm.patchFavorite(tid, on) } }
    }
    LaunchedEffect(state.message) { state.message?.let { c.say(it); vm.messageShown() } }
    val name = state.item?.name.orEmpty()
    val source = Source(SourceKind.Artist, id.toString(), name)
    val palette = rememberNearestPalette(coverUrl(state.latest, images, 64), webId(id))
    val radio = { vm.radio { c.play(it, 0, Source(SourceKind.Mix, id.toString(), t("{name} radio", "name" to name))) } }
    Box(Modifier.fillMaxSize()) {
        ArtistScreen(
            state, playing, palette,
            ArtistActions(
                onBack = c.nav::back,
                onMore = { if (state.item != null) menu = true },
                onPlay = { if (playing.isSource(id)) audio?.togglePlay() else vm.allTracks { c.play(it, 0, source) } },
                onShuffle = { vm.allTracks { c.play(it, 0, source, shuffle = true) } },
                onRadio = radio,
                onFavorite = vm::toggleFavorite,
                onSong = { i ->
                    val top = state.popular
                    if (playing.isTrack(top[i].id)) audio?.togglePlay() else c.play(top, i, source)
                },
                onSongMenu = { c.openMenu(it, id) },
                onOpen = { c.open(it) },
            ),
            bottomPadding = miniBarSpace(),
        )
        if (menu) {
            ItemMenuSheet(
                name,
                listOf(
                    MenuEntry(MusicIcons.QueueNext, t("Play next"), run = { vm.allTracks { c.queue(it, true, name) } }),
                    MenuEntry(MusicIcons.Queue, t("Add to queue"), run = { vm.allTracks { c.queue(it, false, name) } }),
                    MenuEntry(MusicIcons.Radio, t("Artist radio"), run = radio),
                ),
            ) { menu = false }
        }
        MusicOverlays(c)
    }
}

/** Board "Android · Now playing": the full-screen music player. */
@Composable
fun NowPlayingRoute() {
    val audio = LocalAudio.current ?: return
    val session = currentSession()
    val context = LocalContext.current
    val c = rememberMusicController()
    val nav = c.nav
    val state by audio.state.collectAsStateWithLifecycle()
    val time by audio.time.collectAsStateWithLifecycle()
    val vm = viewModel(key = "now-playing") { NowPlayingViewModel(session) }
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val facts by vm.facts.collectAsStateWithLifecycle()
    val tk = state.current?.track
    LaunchedEffect(tk?.id) { vm.track(tk) }
    if (tk == null) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val output = rememberAudioOutput()
    val tint = rememberCoverTint(audio.artwork(tk, 64), webId(tk.albumId ?: tk.id)).first
    var panel by remember { mutableStateOf<String?>(null) }
    // Following a link leaves the player, as the web closes it.
    val leaveTo = { route: Route -> nav.back(); nav.go(route) }
    val artist = tk.artistItems?.firstOrNull() ?: tk.albumArtists?.firstOrNull()
    Box(Modifier.fillMaxSize()) {
        NowPlayingScreen(
            state, time, lyrics, output, tint,
            NowPlayingActions(
                onClose = nav::back,
                onSource = {
                    val src = state.source
                    val sid = src?.id?.let { runCatching { UUID.fromString(MusicApi.dashed(it)) }.getOrNull() }
                    when {
                        src == null || sid == null -> tk.albumId?.let { leaveTo(Route.Album(it)) }
                        src.kind == SourceKind.Album || src.kind == SourceKind.Playlist -> leaveTo(Route.Album(sid))
                        src.kind == SourceKind.Artist -> leaveTo(Route.Artist(sid))
                        src.kind == SourceKind.Library -> leaveTo(Route.Music(sid))
                        src.kind == SourceKind.Mix -> vm.item(sid) { item -> nav.back(); c.open(item) }
                        src.kind == SourceKind.Genre -> Unit
                        else -> tk.albumId?.let { leaveTo(Route.Album(it)) }
                    }
                },
                onMore = { panel = "menu" },
                onArtist = { artist?.let { leaveTo(Route.Artist(it.id)) } },
                onFavorite = { audio.toggleFavorite(tk) },
                onSeek = audio::seek,
                onShuffle = audio::toggleShuffle,
                onPrevious = audio::previous,
                onToggle = audio::togglePlay,
                onNext = audio::next,
                onRepeat = audio::cycleRepeat,
                onOutput = { openOutputSwitcher(context) },
                onLyrics = { panel = "lyrics" },
                onQueue = { panel = "queue" },
            ),
        )
        when (panel) {
            "lyrics" -> LyricsPanel(tk.name.orEmpty(), dot(tk.name, artistsOf(tk)), lyrics, time.position, tint, audio::seek) { panel = null }
            "queue" -> QueueSheet(state, audio::jumpTo, audio::removeFromQueue, audio::moveInQueue, audio::clearQueue) { panel = null }
            "about" -> {
                LaunchedEffect(tk.id) { vm.loadFacts(tk) }
                AboutSongSheet(facts?.takeIf { it.track.id == tk.id }, onAlbum = { panel = null; tk.albumId?.let { leaveTo(Route.Album(it)) } }) { panel = null }
            }
            "menu" -> ItemMenuSheet(
                tk.name.orEmpty(),
                buildList {
                    add(MenuEntry(MusicIcons.Mix, t("Song radio"), run = { c.songRadio(tk) }))
                    add(MenuEntry(MusicIcons.PlaylistAdd, t("Add to playlist"), more = true, run = { c.addToPlaylist(listOf(tk), tk.name.orEmpty()) }))
                    add(MenuEntry(MusicIcons.Info, t("About the song"), run = { panel = "about" }))
                    add(MenuEntry(MusicIcons.Cast, t("Play on another device"), run = { c.openCast() }))
                    tk.albumId?.let { a -> add(MenuEntry(MusicIcons.Album, t("Go to album"), line = true, run = { leaveTo(Route.Album(a)) })) }
                    artist?.let { a -> add(MenuEntry(MusicIcons.Artist, t("Go to artist"), line = tk.albumId == null, run = { leaveTo(Route.Artist(a.id)) })) }
                },
            ) { if (panel == "menu") panel = null }
        }
        MusicOverlays(c)
    }
}
