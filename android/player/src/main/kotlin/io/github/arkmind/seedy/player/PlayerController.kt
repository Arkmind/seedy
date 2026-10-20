package io.github.arkmind.seedy.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.arkmind.seedy.core.format.TICKS_PER_SECOND
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.client.extensions.mediaSegmentApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.PlayMethod
import java.util.UUID

/** Everything the player screens draw. Stream indexes are Jellyfin's; subtitle -1 is off. */
data class PlayerState(
    val item: BaseItemDto? = null,
    val loading: Boolean = true,
    /** Why playback stopped: a Jellyfin code ("NotAllowed"…), "network", "media", "serverError" or "liveUnavailable". */
    val problem: String? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val audio: List<MediaStream> = emptyList(),
    val subtitles: List<MediaStream> = emptyList(),
    val audioIndex: Int? = null,
    val subtitleIndex: Int = -1,
    val subtitleOffsetMs: Long = 0,
    /** Text subtitle lines on screen now (Seedy draws them). */
    val lines: List<CharSequence> = emptyList(),
    /** Picture subtitles (PGS, VobSub) on screen now, as the file has them. */
    val pictureCues: List<Cue> = emptyList(),
    val speed: Float = 1f,
    val quality: Quality = Quality.Auto,
    val method: PlayMethod? = null,
    /** "Direct play · 4K HDR10 · 5.1". */
    val streamLabel: String = "",
    val segments: List<MediaSegmentDto> = emptyList(),
    /** The intro, recap or credits the Skip button jumps over right now. */
    val skip: MediaSegmentDto? = null,
    val next: BaseItemDto? = null,
    /** The show's seasons and the playing episode's season, for the Episodes panel. */
    val seasons: List<BaseItemDto> = emptyList(),
    val episodes: List<BaseItemDto> = emptyList(),
    /** A live channel's programme on now (refreshed every minute). */
    val program: BaseItemDto? = null,
) {
    val live: Boolean get() = item?.type == BaseItemKind.TV_CHANNEL
}

/**
 * One playback session: asks the server for a stream fitted to this device, plays it in ExoPlayer, keeps the server
 * told (start, progress every 10 s, stop) and handles tracks, skips and the next episode.
 */
@OptIn(UnstableApi::class)
class PlayerController(context: Context, private val session: UserSession, private val scope: CoroutineScope) {
    private val caps = CapabilitiesCache.get(context)
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val streams = Streams(
        session,
        profile = { Profiles.build(caps, _state.value.quality.bitrate) },
        liveProfile = { Profiles.buildLive(caps, _state.value.quality.bitrate) },
    )
    private val prefs = session.seedy?.prefs?.playback

    val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setSeekBackIncrementMs((prefs?.seekBackSeconds ?: 10) * 1000L)
        .setSeekForwardIncrementMs((prefs?.seekForwardSeconds ?: 30) * 1000L)
        .build()

    private var item: BaseItemDto? = null
    private var stream: ActiveStream? = null
    private var text: TextSubtitles? = null
    private var started = false
    private var retriedAsTranscode = false
    private var skipped = mutableSetOf<UUID>()
    private var jobs = mutableListOf<Job>()

    val seekBackSeconds: Int get() = prefs?.seekBackSeconds ?: 10
    val seekForwardSeconds: Int get() = prefs?.seekForwardSeconds ?: 30

    init {
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(playing = isPlaying) }
                if (started) report()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.update { it.copy(buffering = playbackState == Player.STATE_BUFFERING) }
                if (playbackState == Player.STATE_READY && !started) {
                    started = true
                    _state.update { it.copy(loading = false) }
                    scope.launch { reportNow { streams.reportStart(it) } }
                }
                if (playbackState == Player.STATE_ENDED) onEnded()
            }

            override fun onTracksChanged(tracks: Tracks) = applyFileTracks()

            override fun onCues(cueGroup: CueGroup) {
                _state.update { it.copy(pictureCues = cueGroup.cues.filter { c -> c.bitmap != null }) }
            }

            override fun onPlayerError(error: PlaybackException) = onError(error)
        })
    }

    /** Starts [id] where the person left it (or from the start once watched). */
    fun open(id: UUID) {
        scope.launch {
            val loaded = runCatching {
                withIo {
                    session.api.libraryApi.getItems(
                        userId = session.userId, ids = listOf(id),
                        fields = listOf(ItemFields.MEDIA_SOURCES, ItemFields.MEDIA_STREAMS, ItemFields.CHAPTERS, ItemFields.OVERVIEW),
                    ).content.items.firstOrNull()
                }
            }.getOrNull()
            if (loaded == null) return@launch _state.update { it.copy(loading = false, problem = "network") }
            start(loaded)
            if (loaded.type == BaseItemKind.TV_CHANNEL) followProgram(loaded.id)
        }
    }

    private suspend fun start(it: BaseItemDto) {
        item = it
        started = false
        retriedAsTranscode = false
        skipped.clear()
        _state.update { s -> PlayerState(item = it, quality = s.quality, speed = s.speed, subtitleOffsetMs = s.subtitleOffsetMs) }
        val resumeTicks = if (it.userData?.played == true) 0L else it.userData?.playbackPositionTicks ?: 0L
        val source = it.mediaSources?.firstOrNull()
        val opts = StreamOptions(
            mediaSourceId = source?.id,
            audioIndex = source?.defaultAudioStreamIndex,
            subtitleIndex = source?.defaultSubtitleStreamIndex ?: -1,
            maxBitrate = _state.value.quality.bitrate,
            startTicks = resumeTicks,
        )
        openStream(opts)
        startTicking()
        scope.launch { loadSegments(it) }
        scope.launch { loadEpisodes(it) }
    }

    private suspend fun openStream(opts: StreamOptions, keepPaused: Boolean = false) {
        val it = item ?: return
        val old = stream
        val s = try {
            streams.request(it, opts)
        } catch (e: PlaybackProblem) {
            return _state.update { st -> st.copy(loading = false, problem = e.code) }
        } catch (_: Exception) {
            return _state.update { st -> st.copy(loading = false, problem = "network") }
        }
        stream = s
        val media = MediaItem.Builder().setUri(s.url).apply { if (s.isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        val wasPlaying = exo.playWhenReady
        exo.setMediaItem(media, opts.startTicks / 10_000)
        exo.prepare()
        exo.playWhenReady = if (keepPaused) wasPlaying else true
        if (old != null && old.playSessionId != s.playSessionId) scope.launch { streams.stopEncoding(old); streams.closeLive(old) }
        _state.update { st ->
            st.copy(
                audio = s.audioStreams, subtitles = s.subtitleStreams, audioIndex = s.audioIndex, subtitleIndex = s.subtitleIndex,
                method = s.method, streamLabel = label(s), problem = null,
            )
        }
        loadText(s.subtitleIndex)
        applyFileTracks()
    }

    /** Picks the chosen audio and picture-subtitle tracks inside the file (when it plays as it is). */
    private fun applyFileTracks() {
        val s = stream ?: return
        val params = exo.trackSelectionParameters.buildUpon()
        val groups = exo.currentTracks.groups
        if (s.method == PlayMethod.DIRECT_PLAY) {
            val audioGroups = groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            val audioPos = s.audioStreams.filter { !it.isExternal }.sortedBy { it.index }.indexOfFirst { it.index == _state.value.audioIndex }
            audioGroups.getOrNull(audioPos)?.let { params.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0)) }
        }
        val sub = s.subtitleStreams.firstOrNull { it.index == _state.value.subtitleIndex }
        val picture = sub != null && !sub.isTextSubtitleStream && s.method == PlayMethod.DIRECT_PLAY
        if (picture) {
            val textGroups = groups.filter { it.type == C.TRACK_TYPE_TEXT }
            val pos = s.subtitleStreams.filter { !it.isExternal }.sortedBy { it.index }.indexOfFirst { it.index == sub!!.index }
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            textGroups.getOrNull(pos)?.let { params.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0)) }
        } else {
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        }
        exo.trackSelectionParameters = params.build()
    }

    private fun loadText(index: Int) {
        text = null
        _state.update { it.copy(lines = emptyList()) }
        val s = stream ?: return
        val it = item ?: return
        val sub = s.subtitleStreams.firstOrNull { st -> st.index == index } ?: return
        if (!sub.isTextSubtitleStream || s.burnIn) return
        scope.launch {
            text = runCatching { TextSubtitles.load(streams.subtitleUrl(it, s.source, sub)) }.getOrNull()
        }
    }

    fun playPause() {
        if (_state.value.ended) seekTo(0)
        exo.playWhenReady = !exo.playWhenReady
    }

    fun seekTo(ms: Long) {
        exo.seekTo(ms.coerceIn(0, (_state.value.durationMs - 250).coerceAtLeast(0)))
        _state.update { it.copy(positionMs = exo.currentPosition, ended = false) }
        if (started) report()
    }

    fun seekBy(ms: Long) = seekTo(exo.currentPosition + ms)

    fun selectAudio(index: Int) {
        val s = stream ?: return
        _state.update { it.copy(audioIndex = index) }
        if (s.method == PlayMethod.DIRECT_PLAY) {
            stream = s.copy(audioIndex = index)
            applyFileTracks()
            report()
        } else {
            reopen(s.options.copy(audioIndex = index))
        }
    }

    fun selectSubtitle(index: Int) {
        val s = stream ?: return
        val sub = s.subtitleStreams.firstOrNull { it.index == index }
        _state.update { it.copy(subtitleIndex = index) }
        when {
            // A picture subtitle the server would have to burn in: convert from here on.
            sub != null && !sub.isTextSubtitleStream && s.method != PlayMethod.DIRECT_PLAY ->
                reopen(s.options.copy(subtitleIndex = index, forceTranscode = true))
            // Leaving a burnt-in subtitle: back to the plain stream.
            s.burnIn -> reopen(s.options.copy(subtitleIndex = index, forceTranscode = false))
            else -> {
                stream = s.copy(subtitleIndex = index)
                loadText(index)
                applyFileTracks()
                report()
            }
        }
    }

    fun setSubtitleOffset(ms: Long) = _state.update { it.copy(subtitleOffsetMs = ms) }

    fun setSpeed(speed: Float) {
        exo.setPlaybackSpeed(speed)
        _state.update { it.copy(speed = speed) }
    }

    fun setQuality(q: Quality) {
        val s = stream ?: return
        _state.update { it.copy(quality = q) }
        reopen(s.options.copy(maxBitrate = q.bitrate))
    }

    /** Jumps past the intro, recap or credits on screen. */
    fun skip() {
        val seg = _state.value.skip ?: return
        skipped += seg.id
        seekTo(seg.endTicks / 10_000)
    }

    fun playNext() {
        val next = _state.value.next ?: return
        play(next)
    }

    /** Switches to another episode (the Episodes panel). */
    fun play(other: BaseItemDto) {
        scope.launch {
            stopReporting()
            open(other.id)
        }
    }

    /** Restarts the stream where it is with new options (an audio track or quality the server has to convert). */
    private fun reopen(opts: StreamOptions) {
        scope.launch {
            val at = exo.currentPosition
            if (started) reportNow { streams.reportProgress(it) }
            openStream(opts.copy(startTicks = at * 10_000), keepPaused = true)
        }
    }

    private fun onError(error: PlaybackException) {
        val s = stream
        // A file this device turned out not to decode: let the server convert it, once.
        if (s != null && s.method == PlayMethod.DIRECT_PLAY && !retriedAsTranscode && error.errorCode !in NetworkErrors) {
            retriedAsTranscode = true
            reopen(s.options.copy(forceTranscode = true))
            return
        }
        // The server answered with an error: a live channel whose source is down, or a conversion that failed.
        val problem = when {
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> if (_state.value.live) "liveUnavailable" else "serverError"
            error.errorCode in NetworkErrors -> "network"
            else -> "media"
        }
        _state.update { it.copy(problem = problem, loading = false) }
    }

    private fun onEnded() {
        _state.update { it.copy(ended = true, playing = false, positionMs = it.durationMs) }
        scope.launch {
            reportNow { streams.reportStopped(it.copy(positionTicks = _state.value.durationMs * 10_000)) }
            started = false
            if (prefs?.autoPlayNext != false) _state.value.next?.let { play(it) }
        }
    }

    private fun startTicking() {
        jobs.forEach { it.cancel() }
        jobs = mutableListOf(
            scope.launch {
                while (isActive) {
                    tick()
                    delay(200)
                }
            },
            scope.launch {
                while (isActive) {
                    delay(10_000)
                    if (started && exo.isPlaying) report()
                }
            },
            scope.launch {
                while (isActive) {
                    delay(30_000)
                    stream?.takeIf { it.method != PlayMethod.DIRECT_PLAY }?.let { streams.ping(it) }
                }
            },
        )
    }

    private fun tick() {
        val pos = exo.currentPosition
        val dur = exo.duration.takeIf { it > 0 } ?: ((item?.runTimeTicks ?: 0L) / 10_000)
        val st = _state.value
        val lines = text?.at(pos, st.subtitleOffsetMs).orEmpty()
        val ticks = pos * 10_000
        val seg = st.segments.firstOrNull { ticks >= it.startTicks && ticks < it.endTicks - TICKS_PER_SECOND && skipMode(it.type) != "off" }
        if (seg != null && skipMode(seg.type) == "auto" && seg.id !in skipped) {
            skipped += seg.id
            seekTo(seg.endTicks / 10_000)
            return
        }
        _state.update { it.copy(positionMs = pos, durationMs = dur, bufferedMs = exo.bufferedPosition, lines = lines, skip = seg?.takeIf { s -> s.id !in skipped }) }
    }

    private fun skipMode(type: MediaSegmentType): String = when (type) {
        MediaSegmentType.INTRO -> prefs?.skipIntro ?: "button"
        MediaSegmentType.RECAP, MediaSegmentType.PREVIEW -> prefs?.skipRecap ?: "button"
        MediaSegmentType.OUTRO -> prefs?.skipCredits ?: "button"
        else -> "off"
    }

    private suspend fun loadSegments(it: BaseItemDto) {
        val segments = runCatching {
            withIo { session.api.mediaSegmentApi.getItemSegments(it.id).content.items }
        }.getOrDefault(emptyList())
        _state.update { s -> s.copy(segments = segments) }
    }

    private suspend fun loadEpisodes(it: BaseItemDto) {
        if (it.type != BaseItemKind.EPISODE) return
        val seriesId = it.seriesId ?: return
        runCatching {
            val all = withIo {
                session.api.showApi.getEpisodes(
                    seriesId = seriesId, userId = session.userId,
                    fields = listOf(ItemFields.OVERVIEW, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO), enableUserData = true,
                ).content.items
            }
            val seasons = withIo { session.api.showApi.getSeasons(seriesId = seriesId, userId = session.userId).content.items }
            val i = all.indexOfFirst { e -> e.id == it.id }
            _state.update { s ->
                s.copy(
                    next = all.getOrNull(i + 1),
                    seasons = seasons,
                    episodes = all.filter { e -> e.seasonId == it.seasonId },
                )
            }
        }
    }

    /** A channel's programme on now, refreshed every minute while it plays. */
    private fun followProgram(channelId: UUID) {
        jobs += scope.launch {
            while (isActive) {
                val program = runCatching { withIo { session.api.liveTvApi.getChannel(channelId, session.userId).content.currentProgram } }.getOrNull()
                _state.update { it.copy(program = program ?: it.program) }
                delay(60_000)
            }
        }
    }

    /** The episodes of another season, for the Episodes panel's season switcher. */
    suspend fun season(seasonId: UUID): List<BaseItemDto> {
        val seriesId = item?.seriesId ?: return emptyList()
        return runCatching {
            withIo {
                session.api.showApi.getEpisodes(
                    seriesId = seriesId, userId = session.userId, seasonId = seasonId,
                    fields = listOf(ItemFields.OVERVIEW, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO), enableUserData = true,
                ).content.items
            }
        }.getOrDefault(emptyList())
    }

    private fun report() {
        scope.launch { reportNow { streams.reportProgress(it) } }
    }

    private suspend fun reportNow(send: suspend (Streams.Report) -> Unit) {
        val it = item ?: return
        val s = stream ?: return
        send(Streams.Report(it, s, exo.currentPosition * 10_000, !exo.playWhenReady, exo.playbackParameters.speed, _state.value.subtitleIndex))
    }

    private suspend fun stopReporting() {
        val s = stream ?: return
        if (started) reportNow { streams.reportStopped(it) }
        started = false
        streams.stopEncoding(s)
        streams.closeLive(s)
        stream = null
    }

    /** Reports the stop, ends any conversion and frees the decoder. Call once, when the player closes. */
    fun release() {
        jobs.forEach { it.cancel() }
        val snapshot = Triple(item, stream, exo.currentPosition)
        val paused = !exo.playWhenReady
        val speed = exo.playbackParameters.speed
        exo.release()
        val (it, s, pos) = snapshot
        if (it != null && s != null) {
            // The screen is gone: send the last reports on their own.
            CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                withContext(NonCancellable) {
                    if (started) streams.reportStopped(Streams.Report(it, s, pos * 10_000, paused, speed, _state.value.subtitleIndex))
                    streams.stopEncoding(s)
                    streams.closeLive(s)
                }
            }
        }
    }

    private fun label(s: ActiveStream): String {
        val method = when (s.method) {
            PlayMethod.DIRECT_PLAY -> "Direct play"
            PlayMethod.DIRECT_STREAM -> "Direct stream"
            PlayMethod.TRANSCODE -> "Transcoding"
        }
        val v = s.videoStream
        val res = v?.width?.let { w ->
            when {
                w >= 3800 -> "4K"
                w >= 1900 -> "1080p"
                w >= 1260 -> "720p"
                else -> "${v.height ?: w}p"
            }
        }
        val range = when (v?.videoRangeType?.serialName) {
            "HDR10" -> "HDR10"
            "HDR10Plus" -> "HDR10+"
            "HLG" -> "HLG"
            null, "SDR", "Unknown" -> null
            else -> "Dolby Vision"
        }
        val channels = s.audioStreams.firstOrNull { it.index == s.audioIndex }?.channels?.let {
            when (it) {
                1 -> "Mono"
                2 -> "Stereo"
                6 -> "5.1"
                8 -> "7.1"
                else -> "$it ch"
            }
        }
        val picture = listOfNotNull(res, range).joinToString(" ")
        return listOf(method, picture, channels).filter { !it.isNullOrBlank() }.joinToString(" · ")
    }

    private suspend fun <T> withIo(block: suspend () -> T): T = withContext(kotlinx.coroutines.Dispatchers.IO) { block() }

    private companion object {
        val NetworkErrors = setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT,
        )
    }
}

/** Device capabilities don't change while the app runs; reading the decoder list takes a moment. */
object CapabilitiesCache {
    @Volatile private var caps: Capabilities? = null

    fun get(context: Context): Capabilities = caps ?: Capabilities.detect(context.applicationContext).also { caps = it }
}
