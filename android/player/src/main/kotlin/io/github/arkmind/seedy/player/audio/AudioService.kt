package io.github.arkmind.seedy.player.audio

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.arkmind.seedy.core.i18n.t
import kotlin.math.roundToLong

/**
 * Keeps music and audiobooks playing with the screen off or the app in the background, and puts them in the system's
 * media controls (notification shade and lock screen, boards "Android · Shade" and "Android · Lock screen"): shuffle,
 * repeat and favourite for music, 30-second skips for audiobooks. [AudioEngine] connects to it when playback starts.
 */
@OptIn(UnstableApi::class)
class AudioService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val engine = AudioEngine.current ?: return
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        session = MediaSession.Builder(this, engine.sessionPlayer)
            .setId("seedy-audio")
            .setCallback(Callback(engine))
            .apply { open?.let(::setSessionActivity) }
            .build()
        engine.mediaSession = session
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away stops the service unless something plays. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        AudioEngine.current?.let { if (it.mediaSession === session) it.mediaSession = null }
        session?.release()
        session = null
        super.onDestroy()
    }

    private class Callback(private val engine: AudioEngine) : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(ShuffleCommand).add(RepeatCommand).add(FavoriteCommand).build(),
                )
                .setMediaButtonPreferences(preferences(engine.state.value))
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ShuffleCommand.customAction -> engine.toggleShuffle()
                RepeatCommand.customAction -> engine.cycleRepeat()
                FavoriteCommand.customAction -> engine.state.value.current?.track?.let { engine.toggleFavorite(it) }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    companion object {
        private val ShuffleCommand = SessionCommand("seedy.shuffle", Bundle.EMPTY)
        private val RepeatCommand = SessionCommand("seedy.repeat", Bundle.EMPTY)
        private val FavoriteCommand = SessionCommand("seedy.favorite", Bundle.EMPTY)

        internal fun buttons(session: MediaSession, state: AudioState) {
            session.setMediaButtonPreferences(preferences(state))
        }

        private fun preferences(s: AudioState): List<CommandButton> {
            if (s.isBook) {
                return listOf(
                    CommandButton.Builder(CommandButton.ICON_SKIP_BACK_30).setPlayerCommand(Player.COMMAND_SEEK_BACK).setDisplayName(t("Back 30 seconds"))
                        .setSlots(CommandButton.SLOT_BACK).build(),
                    CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30).setPlayerCommand(Player.COMMAND_SEEK_FORWARD).setDisplayName(t("Forward 30 seconds"))
                        .setSlots(CommandButton.SLOT_FORWARD).build(),
                )
            }
            val favorite = s.current?.track?.userData?.isFavorite == true
            return listOf(
                CommandButton.Builder(if (s.shuffle) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
                    .setSessionCommand(ShuffleCommand).setDisplayName(t("Shuffle")).setSlots(CommandButton.SLOT_OVERFLOW).build(),
                CommandButton.Builder(if (favorite) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                    .setSessionCommand(FavoriteCommand).setDisplayName(if (favorite) t("Remove from favorites") else t("Add to favorites"))
                    .setSlots(CommandButton.SLOT_OVERFLOW).build(),
                CommandButton.Builder(
                    when (s.repeat) {
                        Repeat.Off -> CommandButton.ICON_REPEAT_OFF
                        Repeat.All -> CommandButton.ICON_REPEAT_ALL
                        Repeat.One -> CommandButton.ICON_REPEAT_ONE
                    },
                ).setSessionCommand(RepeatCommand).setDisplayName(t("Repeat")).setSlots(CommandButton.SLOT_OVERFLOW).build(),
            )
        }
    }
}

/**
 * The player as the system's controls see it: Next and Previous go through the queue (or an audiobook's chapters),
 * times cover the whole book, and play asks the engine (which loads the song first after a restart).
 */
@OptIn(UnstableApi::class)
internal class SessionPlayer(player: Player, private val engine: AudioEngine) : ForwardingPlayer(player) {
    private val book get() = engine.state.value.isBook
    private val step get() = if (book) 30_000L else 10_000L

    override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
        .addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        ).build()

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    override fun play() = engine.play()
    override fun pause() = engine.pause()
    override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) engine.play() else engine.pause()
    override fun stop() = engine.pause()

    override fun seekToNext() = engine.next()
    override fun seekToNextMediaItem() = engine.next()
    override fun seekToPrevious() = engine.previous()
    override fun seekToPreviousMediaItem() = engine.previous()
    override fun hasNextMediaItem(): Boolean = true
    override fun hasPreviousMediaItem(): Boolean = true

    override fun getSeekBackIncrement(): Long = step
    override fun getSeekForwardIncrement(): Long = step
    override fun seekBack() = engine.skip(-step / 1000.0)
    override fun seekForward() = engine.skip(step / 1000.0)
    override fun seekTo(positionMs: Long) = engine.seek(positionMs / 1000.0)
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = engine.seek(positionMs / 1000.0)

    override fun getCurrentPosition(): Long = (engine.position() * 1000).roundToLong()
    override fun getContentPosition(): Long = currentPosition
    override fun getDuration(): Long = engine.time.value.duration.takeIf { it > 0 }?.let { (it * 1000).roundToLong() } ?: C.TIME_UNSET
    override fun getContentDuration(): Long = duration
    override fun getBufferedPosition(): Long = currentPosition + (super.getBufferedPosition() - super.getCurrentPosition()).coerceAtLeast(0)
}
