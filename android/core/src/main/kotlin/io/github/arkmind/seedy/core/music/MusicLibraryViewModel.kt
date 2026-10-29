package io.github.arkmind.seedy.core.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import java.util.UUID

/** The music library's views (board "Android · Music" chips; the web's Overview is Home). */
enum class MusicTab(val label: String) { Home("Home"), Albums("Albums"), Artists("Artists"), Playlists("Playlists"), Songs("Songs"), Genres("Genres") }

/** A list loaded a page at a time. */
data class Paged(
    val items: List<BaseItemDto> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = false,
    val done: Boolean = false,
    val error: Boolean = false,
    val loaded: Boolean = false,
)

/** Home's sections (the web's Overview); null while loading. */
data class MusicHome(
    val jump: List<BaseItemDto>? = null,
    val mixes: List<Mix>? = null,
    val recent: List<BaseItemDto>? = null,
    val playlists: List<PlaylistPreview>? = null,
    /** The artists this profile plays most; empty when nothing was played (then [artists] lists some). */
    val top: List<TopArtist>? = null,
    val artists: List<BaseItemDto>? = null,
    val genres: List<GenreSample>? = null,
)

/**
 * A music library (board "Android · Music", web MusicLibrary.tsx and tabs.tsx): Home (Jump back in, Made for you,
 * Recently added, Your playlists, Artists you play most, Genres), and Albums, Artists, Playlists, Songs and Genres with
 * the web's sorts and favourites filter. [recent] gives the player's recent sources (id, epoch millis) for Jump back in.
 */
class MusicLibraryViewModel(
    session: UserSession,
    private val id: UUID,
    private val recent: () -> List<Pair<String, Long>> = { emptyList() },
) : ViewModel() {
    private val music = MusicApi(session)

    data class State(
        val view: BaseItemDto? = null,
        val error: Boolean = false,
        val tab: MusicTab = MusicTab.Home,
        /** Albums, artists and songs: only favourites. */
        val favorites: Boolean = false,
        val albumSort: MusicSort = MusicSorts.Albums[0],
        val artistSort: MusicSort = MusicSorts.Artists[0],
        val songSort: MusicSort = MusicSorts.Songs[0],
        val home: MusicHome = MusicHome(),
        val albums: Paged = Paged(),
        val artists: Paged = Paged(),
        val songs: Paged = Paged(),
        val playlists: List<PlaylistPreview>? = null,
        val playlistsError: Boolean = false,
        val genres: List<BaseItemDto>? = null,
        val genreSamples: Map<UUID, BaseItemDto?> = emptyMap(),
        /** The genre whose albums show (Genres tab). */
        val genre: BaseItemDto? = null,
        val genreAlbums: Paged = Paged(),
        /** The mix being fetched to play. */
        val busyMix: String? = null,
    ) {
        val name: String get() = view?.name.orEmpty()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val jobs = HashMap<String, Job>()

    init {
        viewModelScope.launch {
            val view = runCatching { music.item(id) }.getOrNull()
            if (view == null) {
                _state.update { it.copy(error = true) }
                return@launch
            }
            _state.update { it.copy(view = view) }
            loadHome()
        }
    }

    fun retry() {
        _state.update { State() }
        viewModelScope.launch {
            val view = runCatching { music.item(id) }.getOrNull() ?: return@launch _state.update { it.copy(error = true) }
            _state.update { it.copy(view = view) }
            loadHome()
        }
    }

    /* ── Home ── */

    private fun loadHome() {
        reloadJump()
        viewModelScope.launch {
            val top = runCatching { music.topArtists(id) }.getOrDefault(emptyList())
            _state.update { it.copy(home = it.home.copy(top = top)) }
            if (top.isEmpty()) {
                val list = runCatching { music.albumArtistsList(id, random = false, limit = 7) }.getOrDefault(emptyList())
                _state.update { it.copy(home = it.home.copy(artists = list)) }
            }
            launch {
                val mixes = runCatching { music.mixes(id, top) }.getOrDefault(emptyList())
                _state.update { it.copy(home = it.home.copy(mixes = mixes)) }
                if (mixes.isNotEmpty()) {
                    val more = runCatching { music.moreMixes(id, top) }.getOrDefault(emptyList())
                    val have = mixes.map { it.key }.toSet()
                    _state.update { s -> s.copy(home = s.home.copy(mixes = s.home.mixes.orEmpty() + more.filter { it.key !in have })) }
                }
            }
        }
        viewModelScope.launch {
            val list = runCatching { music.recentAlbums(id) }.getOrDefault(emptyList())
            _state.update { it.copy(home = it.home.copy(recent = list)) }
        }
        viewModelScope.launch {
            val list = runCatching { music.playlistPreviews(5) }.getOrDefault(emptyList())
            _state.update { it.copy(home = it.home.copy(playlists = list)) }
        }
        viewModelScope.launch {
            val list = runCatching { music.genreSamples(id) }.getOrDefault(emptyList())
            _state.update { it.copy(home = it.home.copy(genres = list)) }
        }
    }

    /** Jump back in again (after the player finished a song or started another queue). */
    fun reloadJump() {
        jobs["jump"]?.cancel()
        jobs["jump"] = viewModelScope.launch {
            val list = runCatching { music.jumpBackIn(id, recent()) }.getOrNull() ?: return@launch
            _state.update { it.copy(home = it.home.copy(jump = list)) }
        }
    }

    /** The songs of a mix card, fetched when it's played. */
    fun mixTracks(mix: Mix, onReady: (List<BaseItemDto>) -> Unit, onFail: () -> Unit) {
        mix.tracks?.let { return onReady(it) }
        if (_state.value.busyMix != null) return
        _state.update { it.copy(busyMix = mix.key) }
        viewModelScope.launch {
            val list = runCatching { if (mix.key == "favs") music.favouritesMix(id) else emptyList() }.getOrNull()
            _state.update { it.copy(busyMix = null) }
            if (list.isNullOrEmpty()) onFail() else onReady(list)
        }
    }

    /* ── Tabs ── */

    fun setTab(tab: MusicTab) {
        _state.update { it.copy(tab = tab, genre = null) }
        ensure(tab)
    }

    /** The Albums tab sorted by [sortId] (Recently added's See all). */
    fun showAlbums(sortId: String) {
        val sort = MusicSorts.Albums.firstOrNull { it.id == sortId } ?: MusicSorts.Albums[0]
        _state.update { it.copy(tab = MusicTab.Albums, albumSort = sort, albums = Paged()) }
        ensure(MusicTab.Albums)
    }

    fun setFavorites(on: Boolean) {
        _state.update { it.copy(favorites = on, albums = Paged(), artists = Paged(), songs = Paged()) }
        ensure(_state.value.tab)
    }

    fun setSort(sort: MusicSort) {
        _state.update {
            when (it.tab) {
                MusicTab.Albums -> it.copy(albumSort = sort, albums = Paged())
                MusicTab.Artists -> it.copy(artistSort = sort, artists = Paged())
                MusicTab.Songs -> it.copy(songSort = sort, songs = Paged())
                else -> it
            }
        }
        ensure(_state.value.tab)
    }

    private fun ensure(tab: MusicTab) {
        val s = _state.value
        when (tab) {
            MusicTab.Albums -> if (!s.albums.loaded) loadMore(tab)
            MusicTab.Artists -> if (!s.artists.loaded) loadMore(tab)
            MusicTab.Songs -> if (!s.songs.loaded) loadMore(tab)
            MusicTab.Playlists -> if (s.playlists == null) loadPlaylists()
            MusicTab.Genres -> if (s.genres == null) loadGenres()
            MusicTab.Home -> Unit
        }
    }

    fun loadPlaylists() {
        _state.update { it.copy(playlistsError = false) }
        viewModelScope.launch {
            runCatching { music.playlistPreviews() }
                .onSuccess { list -> _state.update { it.copy(playlists = list) } }
                .onFailure { _state.update { it.copy(playlistsError = true) } }
        }
    }

    /** A playlist was made or changed (its sheet): the lists show it. */
    fun playlistsChanged() {
        viewModelScope.launch {
            val home = runCatching { music.playlistPreviews(5) }.getOrNull()
            val all = if (_state.value.playlists != null) runCatching { music.playlistPreviews() }.getOrNull() else null
            _state.update { it.copy(home = if (home != null) it.home.copy(playlists = home) else it.home, playlists = all ?: it.playlists) }
        }
    }

    private fun loadGenres() {
        viewModelScope.launch {
            val list = runCatching { music.genres(id, byCount = false) }.getOrNull() ?: return@launch _state.update { it.copy(genres = emptyList()) }
            _state.update { it.copy(genres = list) }
            val samples = coroutineScope { list.map { g -> async { g.id to runCatching { music.genreSample(id, g.id) }.getOrNull() } }.awaitAll() }
            _state.update { it.copy(genreSamples = samples.toMap()) }
        }
    }

    fun openGenre(genre: BaseItemDto?) {
        _state.update { it.copy(tab = MusicTab.Genres, genre = genre, genreAlbums = Paged()) }
        if (genre != null) loadMore(MusicTab.Genres) else ensure(MusicTab.Genres)
    }

    /** The tab's next page (70 albums or artists, 100 songs, as the web asks). */
    fun loadMore(tab: MusicTab = _state.value.tab) {
        val s = _state.value
        val key = tab.name + (if (tab == MusicTab.Genres) s.genre?.id else "")
        val paged = paged(s, tab) ?: return
        if (jobs[key]?.isActive == true || (paged.done && paged.loaded)) return
        val start = paged.items.size
        val limit = if (tab == MusicTab.Songs) 100 else 70
        set(tab) { it.copy(loading = true, error = false) }
        jobs[key] = viewModelScope.launch {
            val result = runCatching<BaseItemDtoQueryResult> {
                when (tab) {
                    MusicTab.Albums -> music.albums(id, s.albumSort, s.favorites, start, limit)
                    MusicTab.Artists -> music.albumArtists(id, s.artistSort, s.favorites, start, limit)
                    MusicTab.Songs -> music.songs(id, s.songSort, s.favorites, start, limit)
                    MusicTab.Genres -> music.albums(id, MusicSorts.Albums[0], false, start, limit, s.genre?.id)
                    else -> error("not paged")
                }
            }
            result.onSuccess { r ->
                set(tab) { p ->
                    val seen = p.items.mapTo(HashSet()) { it.id }
                    val fresh = r.items.filter { it.id !in seen }
                    val items = p.items + fresh
                    val random = (if (tab == MusicTab.Artists) s.artistSort else if (tab == MusicTab.Songs) s.songSort else s.albumSort).id == "Random"
                    p.copy(items = items, total = r.totalRecordCount, loading = false, loaded = true, done = items.size >= r.totalRecordCount || fresh.isEmpty() || (random && tab != MusicTab.Genres))
                }
            }.onFailure { set(tab) { it.copy(loading = false, error = true, loaded = true) } }
        }
    }

    private fun paged(s: State, tab: MusicTab): Paged? = when (tab) {
        MusicTab.Albums -> s.albums
        MusicTab.Artists -> s.artists
        MusicTab.Songs -> s.songs
        MusicTab.Genres -> if (s.genre != null) s.genreAlbums else null
        else -> null
    }

    private fun set(tab: MusicTab, f: (Paged) -> Paged) = _state.update {
        when (tab) {
            MusicTab.Albums -> it.copy(albums = f(it.albums))
            MusicTab.Artists -> it.copy(artists = f(it.artists))
            MusicTab.Songs -> it.copy(songs = f(it.songs))
            MusicTab.Genres -> it.copy(genreAlbums = f(it.genreAlbums))
            else -> it
        }
    }

    /** A song's favourite changed from a player or a menu: its row follows. */
    fun patchFavorite(itemId: UUID, on: Boolean) = _state.update { s ->
        s.copy(songs = s.songs.copy(items = s.songs.items.map { if (it.id == itemId) it.copy(userData = it.userData?.copy(isFavorite = on)) else it }))
    }

    /** Songs for "Play" on a genre (its albums' songs, random). */
    fun genreSongs(genre: BaseItemDto, onReady: (List<BaseItemDto>) -> Unit) {
        viewModelScope.launch { runCatching { music.audio.genreTracks(genre.id, id) }.onSuccess(onReady) }
    }

    fun genreMix(genre: BaseItemDto, onReady: (List<BaseItemDto>) -> Unit) {
        viewModelScope.launch { runCatching { music.genreMix(genre.id, 150) }.onSuccess(onReady) }
    }
}
