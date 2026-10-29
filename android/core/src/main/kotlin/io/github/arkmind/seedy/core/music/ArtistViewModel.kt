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
import java.util.UUID

/**
 * An artist (board "Android · Artist", web Artist.tsx): their songs by plays (Popular and the plays line), the
 * discography (albums, singles and EPs, appears on, music videos), similar artists; favourite, Play, Shuffle, Radio.
 */
class ArtistViewModel(session: UserSession, private val id: UUID) : ViewModel() {
    private val music = MusicApi(session)

    data class State(
        val item: BaseItemDto? = null,
        val error: Boolean = false,
        val songs: List<BaseItemDto>? = null,
        val disc: Discography? = null,
        val similar: List<BaseItemDto>? = null,
        val message: String? = null,
    ) {
        /** Every play of their songs by this profile. */
        val plays: Int get() = songs.orEmpty().sumOf { it.userData?.playCount ?: 0 }

        /** Popular: the five most played (or the first five when none was played). */
        val popular: List<BaseItemDto>
            get() {
                val list = songs.orEmpty()
                return (if (list.any { (it.userData?.playCount ?: 0) > 0 }) list.filter { (it.userData?.playCount ?: 0) > 0 } else list).take(5)
            }

        /** Their latest release, whose cover colours the hero. */
        val latest: BaseItemDto? get() = disc?.albums?.firstOrNull() ?: disc?.singles?.firstOrNull()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val item = runCatching { music.item(id) }.getOrNull() ?: return@launch _state.update { it.copy(error = true) }
            _state.update { it.copy(item = item) }
            launch { loadSongs() }
            launch { runCatching { music.discography(id) }.onSuccess { d -> _state.update { it.copy(disc = d) } }.onFailure { _state.update { it.copy(disc = Discography()) } } }
            launch { _state.update { it.copy(similar = music.similarArtists(id)) } }
        }
    }

    private suspend fun loadSongs() {
        runCatching { music.artistSongs(id) }.onSuccess { list -> _state.update { it.copy(songs = list) } }.onFailure { _state.update { it.copy(songs = it.songs ?: emptyList()) } }
    }

    /** The player finished a song: plays move (every other one, as the web does). */
    private var played = 0
    fun onPlayed() {
        if (++played % 2 == 0) viewModelScope.launch { loadSongs() }
    }

    fun patchFavorite(trackId: UUID, on: Boolean) = _state.update { s ->
        s.copy(songs = s.songs?.map { if (it.id == trackId) it.copy(userData = it.userData?.copy(isFavorite = on)) else it })
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

    /** All their songs in order (Play, Shuffle). */
    fun allTracks(onReady: (List<BaseItemDto>) -> Unit) {
        viewModelScope.launch {
            runCatching { music.audio.artistTracks(id) }.onSuccess { if (it.isEmpty()) say(t("Nothing to play here yet")) else onReady(it) }
                .onFailure { say(t("Couldn’t start playback")) }
        }
    }

    fun radio(onReady: (List<BaseItemDto>) -> Unit) {
        viewModelScope.launch {
            runCatching { music.audio.instantMix(id, 150) }.onSuccess { if (it.isEmpty()) say(t("Couldn’t start the mix")) else onReady(it) }
                .onFailure { say(t("Couldn’t start the mix")) }
        }
    }

    fun say(message: String) = _state.update { it.copy(message = message) }
    fun messageShown() = _state.update { it.copy(message = null) }
}
