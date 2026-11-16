package io.github.arkmind.seedy.core.remote

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.post
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.sockets.SocketApiState
import org.jellyfin.sdk.model.api.ClientCapabilitiesDto
import org.jellyfin.sdk.model.api.GeneralCommand
import org.jellyfin.sdk.model.api.GeneralCommandMessage
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlayCommand
import org.jellyfin.sdk.model.api.PlayMessage
import org.jellyfin.sdk.model.api.PlaystateCommand
import org.jellyfin.sdk.model.api.PlaystateMessage
import java.util.UUID

/** What a phone controlling this screen asked for. */
sealed interface ScreenCommand {
    /** A remote key: the D-pad, OK, Back or Menu ([code] is Android's KeyEvent code). */
    data class Key(val code: Int) : ScreenCommand
    data object Home : ScreenCommand
    data object Search : ScreenCommand
    /** The whole text typed on the phone, for the field asking for it (the TV's Search). */
    data class Text(val text: String) : ScreenCommand
    /** Open a title's page. */
    data class Show(val id: UUID) : ScreenCommand
    data class Play(val ids: List<UUID>, val startTicks: Long?) : ScreenCommand
    data class Playback(val command: PlaystateCommand, val seekTicks: Long?) : ScreenCommand
    data class Audio(val index: Int) : ScreenCommand
    data class Subtitle(val index: Int) : ScreenCommand
    data class Message(val text: String) : ScreenCommand
}

/**
 * Makes this device a screen phones can control (the TV app): Jellyfin's websocket stays open while [run] runs, the
 * session says which commands it takes, and the commands come out of [commands]. The volume is the TV's own and is
 * handled here. What Jellyfin can't tell the phones (asking for text, the focused title) goes to Seedy with [report].
 */
class ScreenReceiver(context: Context, private val session: UserSession) {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val _commands = MutableSharedFlow<ScreenCommand>(extraBufferCapacity = 32)
    val commands: SharedFlow<ScreenCommand> = _commands.asSharedFlow()

    /** Keeps the screen reachable until cancelled. */
    suspend fun run() = coroutineScope {
        val socket = session.api.webSocket
        launch {
            socket.state.collect { s ->
                if (s is SocketApiState.Connected) {
                    withContext(Dispatchers.IO) { runCatching { session.api.sessionApi.postFullCapabilities(data = Capabilities) } }
                    resend()
                }
            }
        }
        launch {
            socket.subscribeAll().collect { m ->
                when (m) {
                    is GeneralCommandMessage -> m.data?.let(::general)
                    is PlaystateMessage -> m.data?.let { d -> _commands.emit(ScreenCommand.Playback(d.command, d.seekPositionTicks)) }
                    is PlayMessage -> m.data?.let { d ->
                        val ids = d.itemIds.orEmpty()
                        if (d.playCommand == PlayCommand.PLAY_NOW && ids.isNotEmpty()) _commands.emit(ScreenCommand.Play(ids, d.startPositionTicks))
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun general(c: GeneralCommand) {
        val args = c.arguments.orEmpty()
        val cmd: ScreenCommand? = when (c.name) {
            GeneralCommandType.MOVE_UP -> ScreenCommand.Key(KeyEvent.KEYCODE_DPAD_UP)
            GeneralCommandType.MOVE_DOWN -> ScreenCommand.Key(KeyEvent.KEYCODE_DPAD_DOWN)
            GeneralCommandType.MOVE_LEFT -> ScreenCommand.Key(KeyEvent.KEYCODE_DPAD_LEFT)
            GeneralCommandType.MOVE_RIGHT -> ScreenCommand.Key(KeyEvent.KEYCODE_DPAD_RIGHT)
            GeneralCommandType.SELECT -> ScreenCommand.Key(KeyEvent.KEYCODE_DPAD_CENTER)
            GeneralCommandType.BACK -> ScreenCommand.Key(KeyEvent.KEYCODE_BACK)
            GeneralCommandType.TOGGLE_CONTEXT_MENU, GeneralCommandType.TOGGLE_OSD_MENU -> ScreenCommand.Key(KeyEvent.KEYCODE_MENU)
            GeneralCommandType.GO_HOME -> ScreenCommand.Home
            GeneralCommandType.GO_TO_SEARCH -> ScreenCommand.Search
            GeneralCommandType.SEND_STRING -> ScreenCommand.Text(args["String"].orEmpty())
            GeneralCommandType.DISPLAY_CONTENT -> args["ItemId"]?.let(::uuid)?.let { ScreenCommand.Show(it) }
            GeneralCommandType.SET_AUDIO_STREAM_INDEX -> args["Index"]?.toIntOrNull()?.let { ScreenCommand.Audio(it) }
            GeneralCommandType.SET_SUBTITLE_STREAM_INDEX -> args["Index"]?.toIntOrNull()?.let { ScreenCommand.Subtitle(it) }
            GeneralCommandType.DISPLAY_MESSAGE -> listOfNotNull(args["Header"], args["Text"]).filter { it.isNotBlank() }.joinToString(": ").ifEmpty { null }?.let { ScreenCommand.Message(it) }
            GeneralCommandType.VOLUME_UP -> { volume(AudioManager.ADJUST_RAISE); null }
            GeneralCommandType.VOLUME_DOWN -> { volume(AudioManager.ADJUST_LOWER); null }
            GeneralCommandType.MUTE -> { volume(AudioManager.ADJUST_MUTE); null }
            GeneralCommandType.UNMUTE -> { volume(AudioManager.ADJUST_UNMUTE); null }
            GeneralCommandType.TOGGLE_MUTE -> { volume(AudioManager.ADJUST_TOGGLE_MUTE); null }
            GeneralCommandType.SET_VOLUME -> {
                args["Volume"]?.toIntOrNull()?.let { v ->
                    runCatching {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v.coerceIn(0, 100) * max + 50) / 100, AudioManager.FLAG_SHOW_UI)
                    }
                }
                null
            }
            else -> null
        }
        if (cmd != null) _commands.tryEmit(cmd)
    }

    private fun volume(direction: Int) {
        runCatching { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI) }
    }

    private var last: ScreenReport? = null
    private var sent: ScreenReport? = null

    /** Tells the phones what this screen asks for and has focused (Seedy only); the same report isn't sent twice. */
    suspend fun report(r: ScreenReport) {
        last = r
        if (r == sent || session.seedyApi == null) return
        val ok = withContext(Dispatchers.IO) { runCatching { session.api.post<Unit>("/Seedy/Remote/State", requestBody = r) }.isSuccess }
        if (ok) sent = r
    }

    /** After a reconnection, the server may have restarted and forgotten. */
    private suspend fun resend() {
        val r = last ?: ScreenReport()
        sent = null
        report(r)
    }

    private fun uuid(s: String): UUID? = runCatching { UUID.fromString(s) }.getOrNull()
        ?: runCatching { UUID.fromString(s.replace(Regex("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})"), "$1-$2-$3-$4-$5")) }.getOrNull()

    companion object {
        /** What the TV app does when a phone asks (the Seedy remote's commands, and the ones other Jellyfin remotes send). */
        val Capabilities = ClientCapabilitiesDto(
            playableMediaTypes = listOf(MediaType.VIDEO, MediaType.AUDIO),
            supportedCommands = listOf(
                GeneralCommandType.MOVE_UP, GeneralCommandType.MOVE_DOWN, GeneralCommandType.MOVE_LEFT, GeneralCommandType.MOVE_RIGHT,
                GeneralCommandType.SELECT, GeneralCommandType.BACK, GeneralCommandType.TOGGLE_CONTEXT_MENU, GeneralCommandType.TOGGLE_OSD_MENU,
                GeneralCommandType.GO_HOME, GeneralCommandType.GO_TO_SEARCH, GeneralCommandType.SEND_STRING, GeneralCommandType.DISPLAY_CONTENT,
                GeneralCommandType.VOLUME_UP, GeneralCommandType.VOLUME_DOWN, GeneralCommandType.SET_VOLUME, GeneralCommandType.MUTE,
                GeneralCommandType.UNMUTE, GeneralCommandType.TOGGLE_MUTE, GeneralCommandType.SET_AUDIO_STREAM_INDEX,
                GeneralCommandType.SET_SUBTITLE_STREAM_INDEX, GeneralCommandType.DISPLAY_MESSAGE, GeneralCommandType.PLAY,
                GeneralCommandType.PLAY_STATE,
            ),
            supportsMediaControl = true,
            supportsPersistentIdentifier = true,
        )
    }
}
