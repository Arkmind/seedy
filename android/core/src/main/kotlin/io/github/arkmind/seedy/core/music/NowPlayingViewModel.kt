package io.github.arkmind.seedy.core.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

/** One line of a song's lyrics; [start] in seconds when the lyrics are synced. */
data class LyricLine(val text: String, val start: Double? = null)

/** A song's lyrics: null [lines] while loading; empty when the server has none. */
data class Lyrics(val trackId: UUID? = null, val lines: List<LyricLine>? = null) {
    val synced: Boolean get() = !lines.isNullOrEmpty() && lines.all { it.start != null }
    val waiting: Boolean get() = trackId != null && lines == null

    /** The line sung at [position] seconds (the web's 0.2 s lead), -1 before the first. */
    fun currentAt(position: Double): Int {
        val list = lines ?: return -1
        if (!synced) return -1
        var cur = -1
        for (i in list.indices) if ((list[i].start ?: 0.0) <= position + 0.2) cur = i
        return cur
    }
}

/** The song's facts for About the song: the song with its people and streams, and its album. */
data class SongFacts(val track: BaseItemDto, val album: BaseItemDto?)

/**
 * What Now playing loads beside the player (board "Android · Now playing", web NowPlaying.tsx): the song's lyrics, About
 * the song, the devices to play on, and the item a source opens.
 */
class NowPlayingViewModel(session: UserSession) : ViewModel() {
    private val music = MusicApi(session)

    private val _lyrics = MutableStateFlow(Lyrics())
    val lyrics: StateFlow<Lyrics> = _lyrics.asStateFlow()
    private val _facts = MutableStateFlow<SongFacts?>(null)
    val facts: StateFlow<SongFacts?> = _facts.asStateFlow()
    private val _targets = MutableStateFlow<List<CastTarget>?>(null)
    val targets: StateFlow<List<CastTarget>?> = _targets.asStateFlow()
    private var lyricsJob: Job? = null

    /** Loads [track]'s lyrics unless they're loaded already. */
    fun track(track: BaseItemDto?) {
        if (track == null || _lyrics.value.trackId == track.id) return
        lyricsJob?.cancel()
        _lyrics.value = Lyrics(track.id, null)
        _facts.value = null
        if (track.hasLyrics == false) {
            _lyrics.value = Lyrics(track.id, emptyList())
            return
        }
        lyricsJob = viewModelScope.launch {
            val dto = music.audio.lyrics(track.id)
            val lines = dto?.lyrics.orEmpty().map { LyricLine(it.text, it.start?.let { s -> s / 10_000_000.0 }) }
            _lyrics.value = Lyrics(track.id, lines)
        }
    }

    fun loadFacts(track: BaseItemDto) {
        if (_facts.value?.track?.id == track.id) return
        viewModelScope.launch {
            val full = runCatching { music.audio.audioItem(track.id) }.getOrDefault(track)
            val album = track.albumId?.let { runCatching { music.audio.audioItem(it) }.getOrNull() }
            _facts.value = SongFacts(full, album)
        }
    }

    fun loadTargets(deviceId: String?) {
        _targets.value = null
        viewModelScope.launch { _targets.value = runCatching { music.castTargets(deviceId) }.getOrDefault(emptyList()) }
    }

    fun castTo(target: CastTarget, ids: List<UUID>, startTicks: Long, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(runCatching { music.castTo(target.id, ids, startTicks) }.isSuccess) }
    }

    /** The item a "Playing from" source is (a mix's seed: a song, album or artist), to open it. */
    fun item(id: UUID, onReady: (BaseItemDto) -> Unit) {
        viewModelScope.launch { runCatching { music.item(id) }.onSuccess(onReady) }
    }

    fun mix(id: UUID, onReady: (List<BaseItemDto>) -> Unit, onFail: () -> Unit) {
        viewModelScope.launch { runCatching { music.audio.instantMix(id) }.onSuccess { if (it.isEmpty()) onFail() else onReady(it) }.onFailure { onFail() } }
    }
}
