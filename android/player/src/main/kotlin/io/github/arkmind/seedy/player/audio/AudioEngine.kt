package io.github.arkmind.seedy.player.audio

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.arkmind.seedy.core.SeedyCore
import io.github.arkmind.seedy.core.audio.AudioApi
import io.github.arkmind.seedy.core.audio.AudiobookParts
import io.github.arkmind.seedy.core.audio.Reading
import io.github.arkmind.seedy.core.audio.audioStreamOf
import io.github.arkmind.seedy.core.audio.authorsOf
import io.github.arkmind.seedy.core.audio.narratorOf
import io.github.arkmind.seedy.core.audio.qualityLabel
import io.github.arkmind.seedy.core.format.Images
import io.github.arkmind.seedy.core.format.TICKS_PER_SECOND
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.session.SessionState
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PersonKind
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.QueueItem
import java.util.UUID
import kotlin.math.roundToLong

/**
 * The audio player behind the mini bar, Now playing and the audiobook player (web/src/audio/engine.ts, for Android).
 *
 * One ExoPlayer plays a queue: the songs of what was played (an album, playlist, artist, mix…, "Next from …") with the
 * songs the person queued themselves ("Added by you") played first. Audiobooks play alone, with chapters, speed and a
 * sleep timer. Playback is reported to Jellyfin (play counts, last played, resume points), shows in the system's media
 * controls through [AudioService] (so it keeps playing with the screen off), and the queue survives a restart.
 *
 * One per process ([get]); it follows the signed-in profile and stops when they sign out. Call it on the main thread.
 */
@OptIn(UnstableApi::class)
class AudioEngine private constructor(private val context: Context, core: SeedyCore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = AudioStore(context)

    private val _state = MutableStateFlow(AudioState(speed = store.speed))
    val state: StateFlow<AudioState> = _state.asStateFlow()
    private val _time = MutableStateFlow(AudioTime())
    val time: StateFlow<AudioTime> = _time.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Things to tell the person ("Couldn't play …"), for a toast. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    private val _played = MutableSharedFlow<UUID>(extraBufferCapacity = 8)
    /** A song or book file stopped: pages showing play counts or resume points can refresh. */
    val played: SharedFlow<UUID> = _played.asSharedFlow()
    private val _favorites = MutableSharedFlow<Pair<UUID, Boolean>>(extraBufferCapacity = 8)
    /** A song's favourite state changed from a player. */
    val favorites: SharedFlow<Pair<UUID, Boolean>> = _favorites.asSharedFlow()
    private val _recent = MutableStateFlow<List<RecentSource>>(emptyList())
    /** What this profile played lately, newest first ("Jump back in"). */
    val recent: StateFlow<List<RecentSource>> = _recent.asStateFlow()

    private var session: UserSession? = null
    /** "server:user" of [session]: the saved queue and recent sources belong to them. */
    private var who: String? = null
    private var api: AudioApi? = null
    private var streams: AudioStreams? = null

    private var stream: AudioStream? = null
    private var loadSeq = 0
    private var started = false
    private var converted = false
    /** Between two songs: the old one's pause isn't the person pausing. */
    private var switching = false
    private var lastSave = 0L
    private var lastPlace = 0L
    private var lastReportedPause: Boolean? = null
    private var keySeq = 0
    private var ticker: Job? = null
    private var progressJob: Job? = null
    private var sleepJob: Job? = null
    /** Queue entries in the order they were first played, so turning shuffle on keeps them behind the current song. */
    private val playedKeys = ArrayDeque<String>()
    private var controller: ListenableFuture<MediaController>? = null
    private var sessionChapter: Int? = null

    /** Set by [AudioService] while it shows the player in the system's media controls. */
    internal var mediaSession: MediaSession? = null
        set(value) {
            field = value
            value?.let { refreshButtons() }
        }

    internal val exo: ExoPlayer by lazy {
        ExoPlayer.Builder(context)
            .setAudioAttributes(attributes(AudioMode.Music), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .also { it.addListener(listener) }
    }

    /** What the system's controls drive: the queue's Next and Previous, the whole book's time and chapters. */
    internal val sessionPlayer: Player by lazy { SessionPlayer(exo, this) }

    init {
        scope.launch {
            core.sessions.state.collect { s ->
                when (s) {
                    is SessionState.SignedIn -> attach(s.session)
                    is SessionState.SignedOut, SessionState.NoServer -> detach()
                    else -> Unit
                }
            }
        }
    }

    /* ───────── The profile ───────── */

    private fun attach(s: UserSession) {
        val key = "${s.server.id}:${s.userId}"
        session = s
        api = AudioApi(s)
        streams = AudioStreams(context, s)
        if (key == who) return
        if (who != null) stop(forget = false)
        who = key
        _recent.value = store.recent(key)
        restore()
    }

    private fun detach() {
        if (who == null) return
        stop()
        who = null
        session = null
        api = null
        streams = null
        _recent.value = emptyList()
    }

    /* ───────── ExoPlayer ───────── */

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && (switching || exo.playbackState == Player.STATE_ENDED)) return
            set { copy(playing = playWhenReady) }
            if (playWhenReady) {
                startTicking()
            } else {
                stopTicking()
                report()
                save(true)
                place(now = true)
            }
            syncSession()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) return
            switching = false
            set { copy(loading = false) }
            if (!started) begin() else report()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> set { copy(loading = true) }
                Player.STATE_READY -> set { copy(loading = false) }
                Player.STATE_ENDED -> onEnded()
            }
        }

        override fun onPlayerError(error: PlaybackException) = onError()
    }

    private fun attributes(mode: AudioMode) = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(if (mode == AudioMode.Audiobook) C.AUDIO_CONTENT_TYPE_SPEECH else C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    private fun startTicking() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                onTime()
                delay(250)
            }
        }
    }

    private fun stopTicking() {
        ticker?.cancel()
        ticker = null
        onTime()
    }

    /** Seconds into the current song or (an audiobook) the whole book. */
    fun position(): Double {
        val s = stream ?: return _time.value.position
        return partStart() + s.offset + exo.currentPosition / 1000.0
    }

    /** Where this file starts in its book (a book in several files), in seconds; 0 for anything else. */
    private fun partStart(entry: Entry? = _state.value.current): Double {
        val s = _state.value
        if (s.mode != AudioMode.Audiobook || entry == null) return 0.0
        return s.book?.parts?.firstOrNull { it.id == entry.track.id.toString() }?.start ?: 0.0
    }

    /** The file of the book playing at [at] seconds; -1 for a book in one file. */
    private fun partAt(book: BookInfo?, at: Double): Int {
        val parts = book?.parts?.takeIf { it.isNotEmpty() } ?: return -1
        var i = 0
        while (i + 1 < parts.size && parts[i + 1].start <= at) i++
        return i
    }

    private fun durationOf(entry: Entry?): Double {
        val s = _state.value
        if (s.mode == AudioMode.Audiobook && s.book != null) return s.book.duration
        val d = seconds(entry?.track?.runTimeTicks)
        if (d > 0) return d
        val ms = if (stream != null) exo.duration else C.TIME_UNSET
        return if (ms != C.TIME_UNSET && ms > 0) ms / 1000.0 + (stream?.offset ?: 0.0) else 0.0
    }

    private fun onTime() {
        val pos = position()
        _time.value = AudioTime(pos, durationOf(_state.value.current))
        checkSleep(pos)
        if (System.currentTimeMillis() - lastSave > 5000) save()
        if (_state.value.playing) place()
        val chapter = if (_state.value.isBook) chapterAt(pos)?.index else null
        if (chapter != sessionChapter) syncSession()
    }

    /* ───────── Loading ───────── */

    private fun load(entry: Entry, start: Double, autoplay: Boolean, convert: Boolean = false) {
        val seq = ++loadSeq
        if (entry.key !in playedKeys) {
            playedKeys.addLast(entry.key)
            if (playedKeys.size > 1000) playedKeys.removeFirst()
        }
        switching = autoplay
        end(finished = false)
        converted = convert
        set { copy(current = entry, loading = true, stream = null, playing = if (autoplay) playing || autoplay else false) }
        _time.value = AudioTime(partStart(entry) + start, durationOf(entry))
        if (autoplay) ensureService()
        scope.launch {
            val st = streams ?: return@launch
            val s = runCatching { st.open(entry.track.id, start, convert) }.getOrNull()
            if (seq != loadSeq) return@launch
            if (s == null) {
                switching = false
                failed(entry)
                return@launch
            }
            stream = s
            started = false
            lastReportedPause = null
            set { copy(stream = StreamInfo(s.method, qualityLabel(audioStreamOf(BaseItemDtoWithSource(entry.track, s)), " "))) }
            val media = MediaItem.Builder().setUri(s.url).setMediaId(entry.key).setMediaMetadata(metadata(entry)).build()
            exo.setAudioAttributes(attributes(_state.value.mode ?: AudioMode.Music), true)
            exo.setPlaybackSpeed(if (_state.value.isBook) _state.value.speed else 1f)
            exo.setMediaItem(media, if (s.offset == 0.0 && start > 0) (start * 1000).roundToLong() else 0L)
            exo.prepare()
            exo.playWhenReady = autoplay
            if (!autoplay) set { copy(loading = false) }
            sessionChapter = null
            syncSession()
            save(true)
        }
    }

    /** The stream's own source carries the codec the quality label reads. */
    @Suppress("FunctionName")
    private fun BaseItemDtoWithSource(track: BaseItemDto, s: AudioStream): BaseItemDto = track.copy(mediaSources = listOf(s.source))

    private fun onError() {
        val s = _state.value
        val cur = s.current ?: return
        if (stream == null) return
        val at = position() - partStart()
        // The phone couldn't play the file as it is: ask the server to convert it once.
        if (!converted) {
            load(cur, at, autoplay = true, convert = true)
            return
        }
        failed(cur)
    }

    private fun failed(entry: Entry) {
        end()
        set { copy(playing = false, loading = false) }
        say(t("Couldn’t play {name}", "name" to entry.track.name.orEmpty()))
        val s = _state.value
        // In a queue, carry on with the next song.
        if (s.mode == AudioMode.Music && peek(s) != null) {
            scope.launch {
                delay(1200)
                if (_state.value.current?.key == entry.key) next()
            }
        }
    }

    private fun onEnded() {
        val s = _state.value
        end(finished = true)
        if (s.mode == AudioMode.Audiobook) {
            // The book's next file, else the end of the book.
            val nextPart = if (s.book?.parts != null) s.context.getOrNull(s.pos + 1) else null
            if (nextPart != null) {
                set { copy(pos = s.pos + 1) }
                load(nextPart, 0.0, autoplay = true)
                return
            }
            // Done: back to the start of the book, paused.
            place(now = true, finished = true)
            val first = if (s.book?.parts != null) s.context.firstOrNull() else null
            set { copy(playing = false, sleep = null, pos = if (first != null) 0 else pos, current = first ?: current) }
            _time.value = AudioTime(0.0, durationOf(s.current))
            save(true)
            syncSession()
            return
        }
        if (s.repeat == Repeat.One && s.current != null) {
            load(s.current, 0.0, autoplay = true)
            return
        }
        peek(s)?.let {
            apply(it, autoplay = true)
            return
        }
        // The end of the queue: back to its first song, paused.
        set { copy(playing = false) }
        s.context.firstOrNull()?.let { first ->
            set { copy(pos = 0, fromAdded = false) }
            load(first, 0.0, autoplay = false)
        }
    }

    /* ───────── Reports ───────── */

    private fun body(): AudioReport? {
        val s = _state.value
        val cur = s.current ?: return null
        val st = stream ?: return null
        val queue = if (s.mode == AudioMode.Music) (listOf(cur) + s.upNext).take(60).map { QueueItem(id = it.track.id, playlistItemId = it.key) } else null
        return AudioReport(
            itemId = cur.track.id, stream = st, positionTicks = ticks(position() - partStart()), paused = !exo.playWhenReady,
            speed = exo.playbackParameters.speed, repeat = s.repeat, shuffle = s.shuffle, queue = queue,
        )
    }

    private fun begin() {
        val b = body() ?: return
        started = true
        lastReportedPause = b.paused
        val st = streams ?: return
        scope.launch { st.reportStart(b) }
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(10_000)
                if (exo.playWhenReady) report()
            }
        }
    }

    private fun report() {
        if (!started) return
        val b = body() ?: return
        val st = streams ?: return
        lastReportedPause = b.paused
        scope.launch { st.reportProgress(b) }
    }

    /** Ends the current stream: tells the server where it stopped (at the end when [finished]). */
    private fun end(finished: Boolean = false) {
        progressJob?.cancel()
        val st = streams
        if (stream != null && started && st != null) {
            body()?.let { b ->
                val cur = _state.value.current
                val stop = if (finished && cur != null) b.copy(positionTicks = cur.track.runTimeTicks ?: b.positionTicks) else b
                scope.launch {
                    st.reportStopped(stop)
                    // Give the server a moment to count it before pages ask again.
                    delay(600)
                    _played.tryEmit(stop.itemId)
                }
            }
        }
        started = false
        stream = null
    }

    /* ───────── Queue ───────── */

    private data class Step(val entry: Entry, val patch: AudioState.() -> AudioState)

    private fun peek(s: AudioState): Step? = when {
        s.added.isNotEmpty() -> Step(s.added.first()) { copy(added = added.drop(1), fromAdded = true) }
        s.pos + 1 < s.context.size -> Step(s.context[s.pos + 1]) { copy(pos = pos + 1, fromAdded = false) }
        s.repeat == Repeat.All && s.context.isNotEmpty() -> Step(s.context.first()) { copy(pos = 0, fromAdded = false) }
        else -> null
    }

    private fun apply(step: Step, autoplay: Boolean = true) {
        set(step.patch)
        load(step.entry, 0.0, autoplay)
    }

    fun next() {
        val s = _state.value
        if (s.current == null) return
        if (s.mode == AudioMode.Audiobook) return nextChapter()
        peek(s)?.let { apply(it, autoplay = true) }
    }

    fun previous() {
        val s = _state.value
        val cur = s.current ?: return
        if (s.mode == AudioMode.Audiobook) return previousChapter()
        if (position() > 3) return seek(0.0)
        if (s.fromAdded) {
            // Back to the song before the queued one; the queued one goes back to the top of the queue.
            val back = s.context.getOrNull(s.pos) ?: return seek(0.0)
            set { copy(added = listOf(cur) + added, fromAdded = false) }
            load(back, 0.0, autoplay = true)
            return
        }
        when {
            s.pos > 0 -> {
                set { copy(pos = s.pos - 1) }
                load(s.context[s.pos - 1], 0.0, autoplay = true)
            }
            s.repeat == Repeat.All && s.context.size > 1 -> {
                set { copy(pos = s.context.size - 1) }
                load(s.context.last(), 0.0, autoplay = true)
            }
            else -> seek(0.0)
        }
    }

    /** Plays a song from Up next: queued songs before it are skipped (dropped); songs of the context before it count as played. */
    fun jumpTo(key: String) {
        val s = _state.value
        val ai = s.added.indexOfFirst { it.key == key }
        if (ai >= 0) {
            set { copy(added = s.added.drop(ai + 1), fromAdded = true) }
            load(s.added[ai], 0.0, autoplay = true)
            return
        }
        val ci = s.context.withIndex().indexOfFirst { (i, e) -> e.key == key && i > s.pos }
        if (ci >= 0) {
            set { copy(pos = ci, fromAdded = false) }
            load(s.context[ci], 0.0, autoplay = true)
        }
    }

    /** Queues songs, after the current one ([next]) or at the end of "Added by you"; starts them when nothing plays. */
    fun addToQueue(tracks: List<BaseItemDto>, next: Boolean = false) {
        if (tracks.isEmpty()) return
        val s = _state.value
        if (s.mode != AudioMode.Music || s.current == null) {
            playTracks(tracks, 0, PlayOptions(source = Source(SourceKind.Songs, name = t("Songs"))))
            return
        }
        val entries = tracks.map(::mk)
        set { copy(added = if (next) entries + added else added + entries) }
        save(true)
        say(if (tracks.size == 1) t("Added to the queue") else t("{n} songs added to the queue", "n" to tracks.size))
    }

    fun removeFromQueue(key: String) {
        val s = _state.value
        if (s.added.any { it.key == key }) {
            set { copy(added = added.filter { it.key != key }) }
        } else {
            val i = s.context.indexOfFirst { it.key == key }
            if (i > s.pos) set { copy(context = context.filter { it.key != key }, original = original?.filter { it.key != key }) }
        }
        save(true)
    }

    /** Moves a song within its part of Up next: [added] ("Added by you") or the context ("Next from …"); indexes count from the top of that part. */
    fun moveInQueue(added: Boolean, from: Int, to: Int) {
        val s = _state.value
        if (added) {
            val list = s.added.toMutableList()
            if (from !in list.indices) return
            val x = list.removeAt(from)
            list.add(to.coerceIn(0, list.size), x)
            set { copy(added = list) }
        } else {
            val head = s.context.take(s.pos + 1)
            val rest = s.context.drop(s.pos + 1).toMutableList()
            if (from !in rest.indices) return
            val x = rest.removeAt(from)
            rest.add(to.coerceIn(0, rest.size), x)
            set { copy(context = head + rest) }
        }
        save(true)
    }

    fun clearQueue() {
        set { copy(added = emptyList(), context = context.take(pos + 1), original = null) }
        save(true)
    }

    fun toggleShuffle() {
        val s = _state.value
        if (s.mode != AudioMode.Music) return
        if (!s.shuffle) {
            // What already played stays behind the current song, in the order it played; the rest is shuffled after it.
            val here = s.context.getOrNull(s.pos)
            val before = playedKeys.mapNotNull { k -> s.context.firstOrNull { it.key == k } }.filter { it != here }
            val rest = s.context.filter { it != here && it !in before }.shuffled()
            val context = if (here != null) before + here + rest else before + rest
            set { copy(shuffle = true, original = s.context, context = context, pos = if (here != null) before.size else (before.size - 1).coerceAtLeast(0)) }
        } else {
            val here = s.context.getOrNull(s.pos)
            val original = s.original ?: s.context
            val i = here?.let { h -> original.indexOfFirst { it.key == h.key } } ?: -1
            set { copy(shuffle = false, original = null, context = original, pos = i.coerceAtLeast(0)) }
        }
        report()
        save(true)
        refreshButtons()
    }

    fun cycleRepeat() {
        set { copy(repeat = when (repeat) { Repeat.Off -> Repeat.All; Repeat.All -> Repeat.One; Repeat.One -> Repeat.Off }) }
        report()
        save(true)
        refreshButtons()
    }

    /* ───────── Transport ───────── */

    fun togglePlay() {
        if (exo.playWhenReady && stream != null) pause() else play()
    }

    fun play() {
        val s = _state.value
        val cur = s.current ?: return
        if (stream == null) return load(cur, _time.value.position - partStart(), autoplay = true)
        ensureService()
        exo.playWhenReady = true
    }

    fun pause() {
        if (stream != null) exo.playWhenReady = false
    }

    /** Seeks the song, or the whole book, to [to] seconds. */
    fun seek(to: Double) {
        val s = _state.value
        val cur = s.current ?: return
        val dur = durationOf(cur)
        val at = (if (dur > 0) minOf(to, dur - 0.25) else to).coerceAtLeast(0.0)
        _time.value = AudioTime(at, dur)
        // Another file of the book: load it there (or only point at it while nothing's loaded).
        val part = if (s.mode == AudioMode.Audiobook) partAt(s.book, at) else -1
        if (part >= 0 && part != s.pos && s.context.getOrNull(part) != null) {
            val entry = s.context[part]
            if (stream == null) {
                set { copy(pos = part, current = entry) }
                _time.value = AudioTime(at, dur)
                save(true)
                return
            }
            set { copy(pos = part) }
            load(entry, at - partStart(entry), autoplay = exo.playWhenReady)
            return
        }
        val st = stream ?: return save(true)
        val local = at - partStart()
        // A conversion can't seek in the file: start a new one there.
        if (st.method != PlayMethod.DIRECT_PLAY) return load(cur, local, autoplay = exo.playWhenReady, convert = converted)
        exo.seekTo((local * 1000).roundToLong())
        report()
        save(true)
    }

    fun skip(by: Double) = seek(position() + by)

    /** Audiobook speed, kept on this phone. */
    fun setSpeed(v: Float) {
        set { copy(speed = v) }
        store.speed = v
        if (_state.value.isBook) exo.setPlaybackSpeed(v)
        report()
    }

    /** Stops and forgets everything (signing out, or the person closing the player); [forget] also drops the saved queue. */
    fun stop(forget: Boolean = true) {
        place(now = true)
        ++loadSeq
        end()
        stopTicking()
        sleepJob?.cancel()
        if (controller != null || mediaSession != null) {
            exo.stop()
            exo.clearMediaItems()
        }
        _state.value = AudioState(speed = _state.value.speed)
        _time.value = AudioTime()
        if (forget) store.saveQueue(null)
        controller?.let { f -> runCatching { MediaController.releaseFuture(f) } }
        controller = null
    }

    /* ───────── Favourites ───────── */

    fun toggleFavorite(track: BaseItemDto, on: Boolean = track.userData?.isFavorite != true) {
        fun patchAll(value: Boolean) {
            fun Entry.patched() = if (this.track.id == track.id) copy(track = this.track.copy(userData = this.track.userData?.copy(isFavorite = value))) else this
            set { copy(current = current?.patched(), context = context.map { it.patched() }, added = added.map { it.patched() }, original = original?.map { it.patched() }) }
            _favorites.tryEmit(track.id to value)
            refreshButtons()
        }
        patchAll(on)
        val s = session ?: return
        scope.launch {
            val ok = runCatching {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    if (on) s.api.userDataApi.markFavoriteItem(itemId = track.id, userId = s.userId)
                    else s.api.userDataApi.unmarkFavoriteItem(itemId = track.id, userId = s.userId)
                }
            }.isSuccess
            if (ok) save(true) else {
                patchAll(!on)
                say(t("Couldn’t update {name}", "name" to track.name.orEmpty()))
            }
        }
    }

    /* ───────── Audiobooks ───────── */

    /** The chapter of the book playing at [pos] seconds. */
    fun chapterAt(pos: Double = position()): BookChapter? {
        val chapters = _state.value.book?.chapters?.takeIf { it.isNotEmpty() } ?: return null
        return chapters.lastOrNull { it.start <= pos + 0.01 } ?: chapters.first()
    }

    fun seekChapter(index: Int) {
        _state.value.book?.chapters?.getOrNull(index)?.let { seek(it.start) }
    }

    fun previousChapter() {
        val c = chapterAt() ?: return seek(0.0)
        if (position() - c.start > 3 || c.index == 0) seek(c.start) else seekChapter(c.index - 1)
    }

    fun nextChapter() {
        val c = chapterAt() ?: return
        val book = _state.value.book ?: return
        if (c.index + 1 < book.chapters.size) seekChapter(c.index + 1)
    }

    fun setSleep(sleep: Sleep?) {
        sleepJob?.cancel()
        set { copy(sleep = sleep) }
        if (sleep is Sleep.Time) {
            sleepJob = scope.launch {
                delay((sleep.at - System.currentTimeMillis()).coerceAtLeast(0))
                if (_state.value.sleep == sleep) {
                    pause()
                    set { copy(sleep = null) }
                }
            }
        }
    }

    private fun checkSleep(pos: Double) {
        val s = _state.value
        val sleep = s.sleep as? Sleep.Chapter ?: return
        val c = s.book?.chapters?.getOrNull(sleep.index) ?: return
        if (pos >= c.end - 0.4) {
            pause()
            set { copy(sleep = null) }
        }
    }

    /** Saves the place in the book with Seedy (every 15 s while playing, and on pause, close and the end). */
    private fun place(now: Boolean = false, finished: Boolean = false) {
        val s = _state.value
        if (s.mode != AudioMode.Audiobook || s.current == null) return
        val book = s.book ?: return
        if (!now && System.currentTimeMillis() - lastPlace < 15_000) return
        lastPlace = System.currentTimeMillis()
        val at = if (finished) book.duration else position()
        val ch = chapterAt(at)
        val a = api ?: return
        val id = runCatching { UUID.fromString(book.id) }.getOrNull() ?: return
        val reading = Reading(
            locator = "%.1f".format(java.util.Locale.ROOT, at), percent = if (book.duration > 0) minOf(100.0, at / book.duration * 100) else 0.0,
            chapter = ch?.name, chapterIndex = ch?.let { it.index + 1 }, chapterCount = book.chapters.size, finished = finished,
        )
        scope.launch { runCatching { a.saveReading(id, reading) } }
    }

    private fun bookInfo(item: BaseItemDto, multi: AudiobookParts?): BookInfo {
        val duration = seconds(item.runTimeTicks)
        val list = item.chapters.orEmpty().sortedBy { it.startPositionTicks }
        val chapters = if (list.isNotEmpty()) {
            list.mapIndexed { i, c ->
                BookChapter(
                    i, c.name?.takeIf { it.isNotBlank() } ?: t("Chapter {n}", "n" to i + 1), seconds(c.startPositionTicks),
                    if (i + 1 < list.size) seconds(list[i + 1].startPositionTicks) else duration,
                )
            }
        } else {
            listOf(BookChapter(0, item.name.orEmpty(), 0.0, duration))
        }
        val author = item.people.orEmpty().firstOrNull { it.type == PersonKind.AUTHOR || it.type == PersonKind.WRITER }?.name
            ?: item.albumArtist ?: item.artists?.firstOrNull() ?: authorsOf(item).firstOrNull()?.first
        val narrator = narratorOf(item)
        val parts = multi?.parts?.map { BookPart(it.id.dashed(), seconds(it.start), seconds(it.runTimeTicks)) }
        val name = if (multi != null || item.album == null || item.album == item.name) item.name.orEmpty() else item.album.orEmpty()
        return BookInfo(item.id.toString(), name, author, narrator.takeIf { it != author }, duration, chapters, parts)
    }

    /** One file of a book in several files, as the player's track: its own id and length, the book's name and cover. */
    private fun partTrack(book: BaseItemDto, p: io.github.arkmind.seedy.core.audio.AudioPart): BaseItemDto {
        val tag = book.imageTags?.get(ImageType.PRIMARY)
        return book.copy(
            id = UUID.fromString(p.id.dashed()), name = p.name, type = BaseItemKind.AUDIO_BOOK, mediaType = MediaType.AUDIO, runTimeTicks = p.runTimeTicks,
            album = book.name, parentId = book.id, imageTags = null, chapters = null, mediaSources = null, overview = null,
            parentPrimaryImageItemId = if (tag != null) book.id else book.parentPrimaryImageItemId,
            parentPrimaryImageTag = tag ?: book.parentPrimaryImageTag,
        )
    }

    /* ───────── Starting playback ───────── */

    private fun mk(track: BaseItemDto) = Entry("${System.currentTimeMillis().toString(36)}-${(++keySeq).toString(36)}", track)

    private fun playList(tracks: List<BaseItemDto>, start: Int, opts: PlayOptions) {
        if (tracks.isEmpty()) return say(t("Nothing to play here yet"))
        var entries = tracks.map(::mk)
        var original: List<Entry>? = null
        var pos = start.coerceIn(0, entries.size - 1)
        if (opts.shuffle) {
            original = entries
            val first = if (opts.startId != null || start > 0) entries[pos] else entries.random()
            entries = listOf(first) + entries.filter { it != first }.shuffled()
            pos = 0
        }
        val s = _state.value
        set {
            copy(
                mode = AudioMode.Music, context = entries, original = original, pos = pos, fromAdded = false, source = opts.source, shuffle = opts.shuffle,
                added = if (s.mode == AudioMode.Music) s.added else emptyList(), book = null, sleep = null,
            )
        }
        sleepJob?.cancel()
        opts.source?.takeIf { it.kind != SourceKind.Songs }?.let(::remember)
        load(entries[pos], seconds(opts.startTicks), autoplay = true)
        refreshButtons()
    }

    /** Plays songs already fetched (a page's track list) from [start], replacing the queue. */
    fun playTracks(tracks: List<BaseItemDto>, start: Int = 0, opts: PlayOptions = PlayOptions()) = playList(tracks, start, opts)

    /** Plays these songs (Audio item ids) from [startIndex], replacing the queue. */
    fun playIds(ids: List<UUID>, startIndex: Int = 0, opts: PlayOptions = PlayOptions()) = launch {
        val tracks = it.tracks(ids)
        playList(tracks, tracks.indexOfFirst { t -> t.id == ids.getOrNull(startIndex) }.coerceAtLeast(0), opts)
    }

    /** Plays an album, playlist, artist, genre or library by id: their songs in order (or shuffled). */
    fun playContainer(id: UUID, opts: PlayOptions = PlayOptions()) = launch { a ->
        val item = a.audioItem(id)
        if (item.type == BaseItemKind.AUDIO_BOOK) return@launch startAudiobook(a, id, opts)
        val tracks = a.containerTracks(item)
        val start = opts.startId?.let { sid -> tracks.indexOfFirst { it.id == sid }.coerceAtLeast(0) } ?: 0
        playList(tracks, start, opts.copy(source = opts.source ?: Source(kindOf(item), item.id.toString(), item.name.orEmpty())))
    }

    /** Jellyfin's instant mix of a song, album, artist, playlist or genre. */
    fun playMix(item: BaseItemDto) = launch { a ->
        playList(a.instantMix(item.id), 0, PlayOptions(source = Source(SourceKind.Mix, item.id.toString(), t("{name} radio", "name" to item.name.orEmpty()))))
    }

    /** "Play" on any audio item: a song plays with the rest of its album after it. */
    fun playItem(item: BaseItemDto, opts: PlayOptions = PlayOptions()) {
        when {
            item.type == BaseItemKind.AUDIO_BOOK -> playAudiobook(item.id, opts)
            item.type == BaseItemKind.AUDIO && item.albumId != null -> playContainer(item.albumId!!, opts.copy(startId = item.id))
            item.type == BaseItemKind.AUDIO -> playTracks(listOf(item), 0, opts)
            else -> playContainer(item.id, opts)
        }
    }

    /**
     * Plays an audiobook (an AudioBook item, the folder of a book in several files, or any of its files), resuming where
     * this profile left off unless [PlayOptions.startTicks] (in the whole book) is given.
     */
    fun playAudiobook(id: UUID, opts: PlayOptions = PlayOptions()) = launch { a -> startAudiobook(a, id, opts) }

    private suspend fun startAudiobook(a: AudioApi, id: UUID, opts: PlayOptions) {
        val multi = a.audiobook(id)
        val item = multi?.item ?: a.withPlace(a.audioItem(id))
        val book = bookInfo(item, multi)
        var start = opts.startTicks?.let(::seconds) ?: seconds(item.userData?.playbackPositionTicks)
        if (start >= book.duration - 5) start = 0.0
        val context = multi?.parts?.map { mk(partTrack(multi.item, it)) } ?: listOf(mk(item))
        val pos = partAt(book, start).coerceAtLeast(0)
        val entry = context[pos]
        sleepJob?.cancel()
        set {
            copy(
                mode = AudioMode.Audiobook, current = entry, context = context, original = null, pos = pos, added = emptyList(), fromAdded = false,
                source = Source(SourceKind.Audiobook, item.id.toString(), book.name), shuffle = false, repeat = Repeat.Off, book = book, sleep = null,
            )
        }
        load(entry, start - (book.parts?.getOrNull(pos)?.start ?: 0.0), autoplay = true)
        refreshButtons()
    }

    private fun launch(block: suspend (AudioApi) -> Unit): Job = scope.launch {
        val a = api ?: return@launch
        runCatching { block(a) }.onFailure { say(t("Couldn’t start playback")) }
    }

    private fun kindOf(item: BaseItemDto) = when (item.type) {
        BaseItemKind.MUSIC_ALBUM -> SourceKind.Album
        BaseItemKind.PLAYLIST -> SourceKind.Playlist
        BaseItemKind.MUSIC_ARTIST -> SourceKind.Artist
        BaseItemKind.MUSIC_GENRE, BaseItemKind.GENRE -> SourceKind.Genre
        BaseItemKind.COLLECTION_FOLDER, BaseItemKind.USER_VIEW -> SourceKind.Library
        BaseItemKind.FOLDER -> SourceKind.Folder
        else -> SourceKind.Songs
    }

    /* ───────── Recently played sources ───────── */

    private fun remember(src: Source) {
        val id = src.id ?: return
        val key = who ?: return
        val list = listOf(RecentSource(src.kind, id, src.name, System.currentTimeMillis())) + store.recent(key).filter { it.id != id }
        _recent.value = list.take(24)
        store.saveRecent(key, _recent.value)
    }

    /* ───────── Saving the queue ───────── */

    private fun slim(e: Entry): Entry {
        val t = e.track
        val audio = audioStreamOf(t)
        return e.copy(
            track = t.copy(
                mediaSources = null, mediaStreams = audio?.let(::listOf), overview = null, chapters = null, externalUrls = null, providerIds = null,
                people = if (t.type == BaseItemKind.AUDIO_BOOK) t.people else null, backdropImageTags = null, parentBackdropImageTags = null,
                imageBlurHashes = null, remoteTrailers = null, studios = null, tags = null, taglines = null, genreItems = null,
            ),
        )
    }

    private fun save(now: Boolean = false) {
        if (!now && System.currentTimeMillis() - lastSave < 5000) return
        lastSave = System.currentTimeMillis()
        val key = who ?: return
        val s = _state.value
        val cur = s.current ?: return store.saveQueue(null)
        if (now) {
            // Long contexts (a shuffled library) keep the part around the current song.
            val from = (s.pos - 50).coerceAtLeast(0)
            store.saveQueue(
                SavedQueue(
                    who = key, mode = s.mode, current = slim(cur), fromAdded = s.fromAdded, context = s.context.drop(from).take(500).map(::slim),
                    original = s.original?.takeIf { it.size <= 500 }?.map(::slim), pos = s.pos - from, added = s.added.take(200).map(::slim),
                    source = s.source, shuffle = s.shuffle, repeat = s.repeat, book = s.book,
                ),
            )
        }
        store.savePosition(position())
    }

    private fun restore() {
        if (_state.value.current != null) return
        val key = who ?: return
        val (q, at) = store.queue(key) ?: return
        keySeq = maxOf(keySeq, 1000)
        _state.value = AudioState(
            mode = q.mode, current = q.current, fromAdded = q.fromAdded, context = q.context, original = q.original, pos = q.pos, added = q.added,
            source = q.source, shuffle = q.shuffle, repeat = q.repeat, book = q.book, speed = _state.value.speed,
        )
        _time.value = AudioTime(at, durationOf(q.current))
    }

    /* ───────── System media controls ───────── */

    /** Connects to [AudioService], which keeps playing in the background and shows the controls. */
    private fun ensureService() {
        if (controller != null) return
        controller = MediaController.Builder(context, SessionToken(context, ComponentName(context, AudioService::class.java))).buildAsync()
    }

    private fun metadata(entry: Entry): MediaMetadata {
        val s = _state.value
        val tk = entry.track
        val chapter = if (s.isBook) chapterAt(_time.value.position) else null
        val book = s.book
        return MediaMetadata.Builder()
            .setTitle(if (chapter != null && book != null && book.chapters.size > 1) chapter.name else tk.name)
            .setArtist(if (s.isBook) book?.author.orEmpty() else tk.artists?.joinToString(", ")?.ifEmpty { null } ?: tk.albumArtist.orEmpty())
            .setAlbumTitle(if (s.isBook) book?.name.orEmpty() else tk.album.orEmpty())
            .setArtworkUri(artwork(tk)?.let(Uri::parse))
            .setMediaType(if (s.isBook) MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER else MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
    }

    /** A song's picture: its own, its album's, or (a book's file) the book's. */
    fun artwork(track: BaseItemDto, maxWidth: Int = 512): String? {
        val images = Images(session?.api ?: return null)
        track.imageTags?.get(ImageType.PRIMARY)?.let { return images.item(track.id, ImageType.PRIMARY, it, maxWidth) }
        val albumTag = track.albumPrimaryImageTag
        val albumId = track.albumId
        if (albumTag != null && albumId != null) return images.item(albumId, ImageType.PRIMARY, albumTag, maxWidth)
        val parentTag = track.parentPrimaryImageTag
        val parentId = track.parentPrimaryImageItemId
        if (parentTag != null && parentId != null) return images.item(parentId, ImageType.PRIMARY, parentTag, maxWidth)
        return null
    }

    /** Puts the chapter (audiobooks) or song in the system's controls. */
    private fun syncSession() {
        val entry = _state.value.current ?: return
        sessionChapter = if (_state.value.isBook) chapterAt(position())?.index else null
        if (exo.mediaItemCount == 0) return
        val item = exo.getMediaItemAt(0)
        if (item.mediaId != entry.key) return
        exo.replaceMediaItem(0, item.buildUpon().setMediaMetadata(metadata(entry)).build())
    }

    /** Shuffle, repeat and favourite in the notification follow the player. */
    internal fun refreshButtons() {
        mediaSession?.let { AudioService.buttons(it, _state.value) }
    }

    private fun say(message: String) {
        _messages.tryEmit(message)
    }

    private fun set(patch: AudioState.() -> AudioState) = _state.update(patch)

    companion object {
        @Volatile private var instance: AudioEngine? = null

        fun get(context: Context, core: SeedyCore): AudioEngine =
            instance ?: synchronized(this) { instance ?: AudioEngine(context.applicationContext, core).also { instance = it } }

        /** The engine, if the app made one ([AudioService] asks). */
        internal val current: AudioEngine? get() = instance

        private fun seconds(ticks: Long?) = (ticks ?: 0L) / TICKS_PER_SECOND.toDouble()
        private fun ticks(s: Double) = (s * TICKS_PER_SECOND).roundToLong()
        private fun String.dashed(): String =
            if (length == 32) "${substring(0, 8)}-${substring(8, 12)}-${substring(12, 16)}-${substring(16, 20)}-${substring(20)}" else this
    }
}
