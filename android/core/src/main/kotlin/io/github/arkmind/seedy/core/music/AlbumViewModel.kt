package io.github.arkmind.seedy.core.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.NameGuidPair
import java.util.UUID

/**
 * An album, or a music playlist in the same layout (board "Android · Album", web Album.tsx): the item, its songs, its
 * artist (the avatar), More by the artist; favourite, removing a playlist's song.
 */
class AlbumViewModel(session: UserSession, private val id: UUID) : ViewModel() {
    private val music = MusicApi(session)

    data class State(
        val item: BaseItemDto? = null,
        val error: Boolean = false,
        val tracks: List<BaseItemDto>? = null,
        val tracksError: Boolean = false,
        /** The album's artist with their picture (the avatar). */
        val artist: BaseItemDto? = null,
        val moreBy: List<Pair<BaseItemDto, String>>? = null,
        val canDownload: Boolean = false,
        val message: String? = null,
    ) {
        val playlist: Boolean get() = item?.type == BaseItemKind.PLAYLIST
        val artistRef: NameGuidPair? get() = item?.albumArtists?.firstOrNull() ?: item?.artistItems?.firstOrNull()
    }

    private val _state = MutableStateFlow(State(canDownload = music.canDownload))
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val item = runCatching { music.item(id) }.getOrNull() ?: return@launch _state.update { it.copy(error = true) }
            _state.update { it.copy(item = item) }
            loadTracks()
            val artist = _state.value.artistRef
            if (item.type != BaseItemKind.PLAYLIST && artist != null) {
                launch { runCatching { music.item(artist.id) }.onSuccess { a -> _state.update { it.copy(artist = a) } } }
                launch {
                    val more = runCatching { music.moreBy(item, artist.id) }.getOrDefault(emptyList())
                    _state.update { it.copy(moreBy = more) }
                }
            }
        }
    }

    fun loadTracks() {
        val item = _state.value.item ?: return
        viewModelScope.launch {
            runCatching { if (item.type == BaseItemKind.PLAYLIST) music.audio.playlistTracks(id) else music.audio.albumTracks(id) }
                .onSuccess { list -> _state.update { it.copy(tracks = list, tracksError = false) } }
                .onFailure { _state.update { it.copy(tracksError = it.tracks == null) } }
        }
    }

    /** The player finished one of these songs: play counts move. */
    fun onPlayed(trackId: UUID) {
        if (_state.value.tracks.orEmpty().any { it.id == trackId }) loadTracks()
    }

    fun patchFavorite(trackId: UUID, on: Boolean) = _state.update { s ->
        s.copy(tracks = s.tracks?.map { if (it.id == trackId) it.copy(userData = it.userData?.copy(isFavorite = on)) else it })
    }

    fun toggleFavorite() {
        val item = _state.value.item ?: return
        val on = item.userData?.isFavorite != true
        fun patch(v: Boolean) = _state.update { s -> s.copy(item = s.item?.copy(userData = s.item.userData?.copy(isFavorite = v))) }
        patch(on)
        viewModelScope.launch {
            runCatching { music.setFavorite(item.id, on) }.onFailure {
                patch(!on)
                say(t("Couldn’t update {name}", "name" to item.name.orEmpty()))
            }
        }
    }

    /** Jellyfin's instant mix of the album (or its songs when it has none). */
    fun mix(onReady: (List<BaseItemDto>) -> Unit) {
        viewModelScope.launch {
            val list = runCatching { music.audio.instantMix(id, 150) }.getOrNull()
            when {
                !list.isNullOrEmpty() -> onReady(list)
                !_state.value.tracks.isNullOrEmpty() -> onReady(_state.value.tracks!!)
                else -> say(t("Couldn’t start the mix"))
            }
        }
    }

    fun removeFromPlaylist(track: BaseItemDto) {
        val item = _state.value.item ?: return
        val entry = track.playlistItemId ?: return
        viewModelScope.launch {
            runCatching { music.removeFromPlaylist(item.id, entry) }
                .onSuccess {
                    _state.update { s -> s.copy(tracks = s.tracks?.filter { it.playlistItemId != entry }) }
                    say(t("Removed {name} from {list}", "name" to track.name.orEmpty(), "list" to item.name.orEmpty()))
                }
                .onFailure { say(t("Couldn’t update {name}", "name" to item.name.orEmpty())) }
        }
    }

    fun say(message: String) = _state.update { it.copy(message = message) }
    fun messageShown() = _state.update { it.copy(message = null) }
}
