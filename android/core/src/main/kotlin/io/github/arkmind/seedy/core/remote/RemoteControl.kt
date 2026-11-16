package io.github.arkmind.seedy.core.remote

import android.os.SystemClock
import io.github.arkmind.seedy.core.admin.AdminHttp
import io.github.arkmind.seedy.core.admin.people.DeviceKind
import io.github.arkmind.seedy.core.admin.people.kindOf
import io.github.arkmind.seedy.core.admin.realDate
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.session.UserSession
import io.github.arkmind.seedy.core.settings.deviceKind
import io.github.arkmind.seedy.core.settings.myDevices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.get
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.GeneralCommand
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.PlayCommand
import org.jellyfin.sdk.model.api.PlaystateCommand
import java.time.Instant
import java.util.UUID

/** The screens this phone may control, and the ones it controls ([controlled], in tab order; [active] is the tab shown). */
data class RemoteState(
    /** Where Seedy is open; null until the first look. */
    val screens: List<RemoteScreen>? = null,
    /** Signed in to the profile, Seedy not open there. */
    val closed: List<ClosedScreen> = emptyList(),
    /** Device ids. */
    val controlled: List<String> = emptyList(),
    val active: String? = null,
    /** "Play in sync": pause, play and skip go to every controlled screen. */
    val sync: Boolean = false,
    /** A command didn't reach the screen. */
    val message: String? = null,
) {
    val controlledScreens: List<RemoteScreen> get() = controlled.mapNotNull { id -> screens?.firstOrNull { it.deviceId == id } }
    val activeScreen: RemoteScreen? get() = screens?.firstOrNull { it.deviceId == active } ?: controlledScreens.firstOrNull()
    val controlling: Boolean get() = controlled.isNotEmpty()
}

/**
 * The phone as a remote (boards "Android · Control a screen", "Remote for a TV", "Remote for a computer", "Remote ·
 * typing on the TV"). Lists the Jellyfin sessions this person may control, sends them Jellyfin's remote commands, and
 * reads what Seedy screens say beyond Jellyfin's play state (asking for text, the focused title, full screen). One per
 * signed-in session; it outlives the screens so the choice stays while the person browses.
 */
class RemoteControl private constructor(private val session: UserSession) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(RemoteState())
    val state: StateFlow<RemoteState> = _state.asStateFlow()

    private val myDevice get() = session.api.deviceInfo.id
    private var watchers = 0
    private var poller: Job? = null
    private var closedAt = 0L

    /** What the phone changed and the screen hasn't reported yet: kept over the polls until then. */
    private val pending = mutableMapOf<String, Pending>()

    private data class Pending(val paused: Boolean? = null, val positionMs: Long? = null, val volume: Int? = null, val until: Long)

    /** Looks at the screens every [everyMs] while someone watches (a picker or a remote is open); call the result to stop. */
    fun watch(everyMs: Long = 1_000): () -> Unit {
        watchers++
        if (poller?.isActive != true) {
            poller = scope.launch {
                while (isActive) {
                    refresh()
                    delay(everyMs)
                }
            }
        }
        var done = false
        return {
            if (!done) {
                done = true
                watchers--
                if (watchers <= 0) {
                    watchers = 0
                    poller?.cancel()
                    poller = null
                }
            }
        }
    }

    suspend fun refresh() {
        val now = SystemClock.elapsedRealtime()
        val sessions = withContext(Dispatchers.IO) {
            runCatching { session.api.sessionApi.getSessions(controllableByUserId = session.userId, activeWithinSeconds = 960).content }.getOrNull()
        } ?: return
        val states = if (session.seedyApi != null) {
            withContext(Dispatchers.IO) { runCatching { session.api.get<List<ScreenState>>("/Seedy/Remote/States").content }.getOrDefault(emptyList()) }
        } else emptyList()
        val byDevice = states.associateBy { it.deviceId }
        val previous = _state.value.screens.orEmpty().associateBy { it.deviceId }
        val screens = sessions
            .filter { it.supportsRemoteControl && !it.deviceId.isNullOrEmpty() && it.deviceId != myDevice }
            .distinctBy { it.deviceId }
            .map { remoteScreen(it, byDevice[it.deviceId], now) }
            .map { s -> keepRunning(s, previous[s.deviceId], now) }
            .map { s -> withPending(s, now) }
            .sortedWith(compareBy({ if (it.playing) 0 else if (it.now != null) 1 else 2 }, { it.name.lowercase() }))
        if (now - closedAt > 15_000) {
            closedAt = now
            val open = screens.map { it.deviceId }.toSet()
            val closed = withContext(Dispatchers.IO) { runCatching { closedScreens(open) }.getOrDefault(_state.value.closed) }
            _state.update { it.copy(closed = closed.filter { c -> c.id !in open }) }
        }
        _state.update { st ->
            val present = screens.map { it.deviceId }.toSet()
            // A screen that closed leaves the tabs; it comes back if it opens again while the remote is open.
            val controlled = st.controlled.filter { it in present || st.screens == null }
            st.copy(
                screens = screens,
                closed = st.closed.filter { c -> c.id !in present },
                controlled = controlled,
                active = st.active?.takeIf { it in controlled } ?: controlled.firstOrNull(),
            )
        }
    }

    /** The same position read again while playing: it keeps running from when it was first seen, not from this poll. */
    private fun keepRunning(s: RemoteScreen, before: RemoteScreen?, now: Long): RemoteScreen {
        val n = s.now ?: return s
        val b = before?.now ?: return s
        if (b.item.id != n.item.id || b.paused != n.paused || b.positionMs != n.positionMs) return s
        return s.copy(now = n.copy(seenAt = b.seenAt.takeIf { it > 0 } ?: now))
    }

    private fun withPending(s: RemoteScreen, now: Long): RemoteScreen {
        val p = pending[s.deviceId] ?: return s
        val n = s.now ?: return s
        if (now > p.until) {
            pending.remove(s.deviceId)
            return s
        }
        // Once the screen reports what the phone asked for, the screen's word counts again.
        val pausedDone = p.paused == null || p.paused == n.paused
        val volumeDone = p.volume == null || p.volume == n.volume
        val seekDone = p.positionMs == null || kotlin.math.abs(n.positionMs - p.positionMs) < 3_000
        if (pausedDone && volumeDone && seekDone) {
            pending.remove(s.deviceId)
            return s
        }
        return s.copy(
            now = n.copy(
                paused = p.paused ?: n.paused,
                positionMs = p.positionMs ?: n.positionMs,
                seenAt = if (p.positionMs != null) (pendingSeenAt[s.deviceId] ?: n.seenAt) else n.seenAt,
                volume = p.volume ?: n.volume,
            ),
        )
    }

    private val pendingSeenAt = mutableMapOf<String, Long>()

    /** Devices signed in to the profile, Seedy not open there: TVs and computers, used in the last two months. */
    private suspend fun closedScreens(open: Set<String>): List<ClosedScreen> {
        val cutoff = Instant.now().minusSeconds(60L * 24 * 3600)
        val seedy = session.seedyApi
        val list = if (seedy != null) {
            seedy.myDevices()
                .filter { !it.current && it.id !in open && (realDate(it.lastActiveAt) ?: Instant.EPOCH).isAfter(cutoff) }
                .sortedByDescending { it.lastActiveAt.orEmpty() }
                .map { ClosedScreen(it.id, it.name.ifEmpty { it.reportedName }, deviceKind(it), screenApp(it.appName, it.reportedName)) }
        } else if (session.user.policy?.isAdministrator == true) {
            myDevices(AdminHttp(session.api), session.userId.toString())
                .filter { it.id != myDevice && it.id !in open && (realDate(it.dateLastActivity) ?: Instant.EPOCH).isAfter(cutoff) }
                .map { ClosedScreen(it.id.orEmpty(), it.customName?.takeIf { n -> n.isNotBlank() } ?: it.name.orEmpty(), kindOf(it), screenApp(it.appName.orEmpty(), it.name.orEmpty())) }
        } else emptyList()
        return list.filter { it.kind == DeviceKind.Tv || it.kind == DeviceKind.Computer }.distinctBy { it.id }.take(3)
    }

    /** The picker's choice: these screens become the tabs (the active one stays when it's still there). */
    fun control(deviceIds: List<String>, sync: Boolean) {
        _state.update { st ->
            st.copy(controlled = deviceIds, active = st.active?.takeIf { it in deviceIds } ?: deviceIds.firstOrNull(), sync = sync && deviceIds.size > 1)
        }
    }

    fun select(deviceId: String) = _state.update { it.copy(active = deviceId) }

    /** Stops controlling one screen (its tab goes); the last one ends the remote. */
    fun release(deviceId: String) = _state.update { st ->
        val left = st.controlled - deviceId
        st.copy(controlled = left, active = st.active?.takeIf { it in left } ?: left.firstOrNull(), sync = st.sync && left.size > 1)
    }

    fun releaseAll() = _state.update { it.copy(controlled = emptyList(), active = null, sync = false) }

    fun messageShown() = _state.update { it.copy(message = null) }

    /* ── Commands ── */

    private fun target(): RemoteScreen? = _state.value.activeScreen

    private fun send(s: RemoteScreen, block: suspend () -> Unit) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { block() }.isSuccess }
            if (!ok) _state.update { it.copy(message = t("Couldn't reach {device}", "device" to s.name)) }
        }
    }

    private fun general(s: RemoteScreen, name: GeneralCommandType, args: Map<String, String> = emptyMap()) = send(s) {
        session.api.sessionApi.sendFullGeneralCommand(s.sessionId, GeneralCommand(name, session.userId, args))
    }

    private fun playstate(s: RemoteScreen, command: PlaystateCommand, seekTicks: Long? = null) = send(s) {
        session.api.sessionApi.sendPlaystateCommand(s.sessionId, command, seekTicks, session.userId.toString())
    }

    /** Pause, play and skip: the active screen, or every controlled screen playing something with Play in sync. */
    private fun together(): List<RemoteScreen> {
        val st = _state.value
        val active = st.activeScreen ?: return emptyList()
        return if (st.sync) (listOf(active) + st.controlledScreens.filter { it.deviceId != active.deviceId && it.now != null }) else listOf(active)
    }

    /* The TV remote. */
    fun up() = target()?.let { general(it, GeneralCommandType.MOVE_UP) }
    fun down() = target()?.let { general(it, GeneralCommandType.MOVE_DOWN) }
    fun left() = target()?.let { general(it, GeneralCommandType.MOVE_LEFT) }
    fun right() = target()?.let { general(it, GeneralCommandType.MOVE_RIGHT) }
    fun select() = target()?.let { general(it, GeneralCommandType.SELECT) }
    fun back() = target()?.let { general(it, GeneralCommandType.BACK) }
    fun home() = target()?.let { general(it, GeneralCommandType.GO_HOME) }
    fun options() = target()?.let { general(it, GeneralCommandType.TOGGLE_CONTEXT_MENU) }
    fun volumeUp() = target()?.let { general(it, GeneralCommandType.VOLUME_UP) }
    fun volumeDown() = target()?.let { general(it, GeneralCommandType.VOLUME_DOWN) }

    /** Typing on the TV: opens its Search unless it already asks for text. */
    fun startTyping() = target()?.let { if (it.asking == null) general(it, GeneralCommandType.GO_TO_SEARCH) }

    /** The whole text typed so far (the screen puts it in its field, replacing what was there). */
    fun type(text: String) = target()?.let { general(it, GeneralCommandType.SEND_STRING, mapOf("String" to text)) }

    /** Opens [item]'s page on the screen. */
    fun show(item: BaseItemDto) = target()?.let { s ->
        send(s) { session.api.sessionApi.displayContent(s.sessionId, item.type, item.id.toString(), item.name.orEmpty()) }
    }

    /* Playback, on what the screen plays. */
    fun playPause() = together().forEach { s ->
        val n = s.now ?: return@forEach
        expect(s, paused = !n.paused)
        playstate(s, if (n.paused) PlaystateCommand.UNPAUSE else PlaystateCommand.PAUSE)
    }

    /** Jumps by [seconds] (back when negative), from where the screen is now. */
    fun skip(seconds: Int) = together().forEach { s ->
        val n = s.now ?: return@forEach
        val to = (n.positionAt(SystemClock.elapsedRealtime()) + seconds * 1000L).coerceIn(0, n.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
        expect(s, positionMs = to)
        playstate(s, PlaystateCommand.SEEK, to * 10_000)
    }

    fun seekTo(ms: Long) = target()?.let { s ->
        expect(s, positionMs = ms)
        playstate(s, PlaystateCommand.SEEK, ms * 10_000)
    }

    fun next() = together().forEach { playstate(it, PlaystateCommand.NEXT_TRACK) }
    fun stop() = target()?.let { playstate(it, PlaystateCommand.STOP) }

    fun audio(index: Int) = target()?.let { general(it, GeneralCommandType.SET_AUDIO_STREAM_INDEX, mapOf("Index" to index.toString())) }
    fun subtitle(index: Int) = target()?.let { general(it, GeneralCommandType.SET_SUBTITLE_STREAM_INDEX, mapOf("Index" to index.toString())) }
    fun fullscreen() = target()?.let { general(it, GeneralCommandType.TOGGLE_FULLSCREEN) }

    /** Seedy web's mini player (picture in picture): Jellyfin has no command for it, so it goes as a key. */
    fun miniPlayer() = target()?.let { general(it, GeneralCommandType.SEND_KEY, mapOf("Key" to "PictureInPicture")) }

    /** The player's own volume on the screen (0–100). */
    fun setVolume(level: Int) = target()?.let { s ->
        expect(s, volume = level)
        general(s, GeneralCommandType.SET_VOLUME, mapOf("Volume" to level.coerceIn(0, 100).toString()))
    }

    /** "Browse here, watch there": [ids] start on the active screen, at [startTicks] when given. */
    fun play(ids: List<UUID>, startTicks: Long? = null): RemoteScreen? {
        val s = target() ?: return null
        send(s) { session.api.sessionApi.play(s.sessionId, PlayCommand.PLAY_NOW, ids.take(100), startTicks) }
        return s
    }

    /** Shows the change at once; the next looks keep it until the screen reports it (or 4 s pass). */
    private fun expect(s: RemoteScreen, paused: Boolean? = null, positionMs: Long? = null, volume: Int? = null) {
        val now = SystemClock.elapsedRealtime()
        val before = pending[s.deviceId]
        pending[s.deviceId] = Pending(paused ?: before?.paused, positionMs ?: before?.positionMs, volume ?: before?.volume, now + 4_000)
        if (positionMs != null) pendingSeenAt[s.deviceId] = now
        _state.update { st ->
            st.copy(screens = st.screens?.map { x -> if (x.deviceId == s.deviceId) withPending(x, now) else x })
        }
    }

    companion object {
        private var last: RemoteControl? = null

        /** The remote of [session] (the same one while that session lasts). */
        fun of(session: UserSession): RemoteControl {
            val r = last
            if (r != null && r.session === session) return r
            r?.scope?.coroutineContext?.get(Job)?.cancel()
            return RemoteControl(session).also { last = it }
        }
    }
}
