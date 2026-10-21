package io.github.arkmind.seedy.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemDto

/**
 * The guide's muted live preview (web: screens/livetv/preview.ts): opens the focused channel's live stream after a
 * short pause on it, plays it without sound and closes it when the remote moves on. It reports no playback, so a
 * preview never counts as watching.
 */
class LivePreview(context: Context, session: UserSession, private val scope: CoroutineScope) {
    enum class State { Idle, Loading, Playing, Failed }

    private val caps = CapabilitiesCache.get(context)
    private val streams = Streams(session, profile = { Profiles.buildLive(caps, 20_000_000) })
    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val exo: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        volume = 0f
        addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) _state.value = State.Playing
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                _state.value = State.Failed
            }
        })
    }

    private var job: Job? = null
    private var open: ActiveStream? = null

    /** Shows [channel] (null: nothing), waiting [dwellMs] first so scrolling past channels doesn't open them all. */
    fun show(channel: BaseItemDto?, dwellMs: Long = 900) {
        job?.cancel()
        close()
        _state.value = State.Idle
        if (channel == null) return
        job = scope.launch {
            delay(dwellMs)
            _state.value = State.Loading
            val s = runCatching { streams.request(channel, StreamOptions(maxBitrate = 20_000_000)) }.getOrNull()
            if (s == null) {
                _state.value = State.Failed
                return@launch
            }
            open = s
            exo.setMediaItem(MediaItem.Builder().setUri(s.url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    private fun close() {
        exo.stop()
        val s = open ?: return
        open = null
        CoroutineScope(Dispatchers.IO).launch {
            withContext(NonCancellable) {
                streams.stopEncoding(s)
                streams.closeLive(s)
            }
        }
    }

    fun release() {
        job?.cancel()
        close()
        exo.release()
    }
}
