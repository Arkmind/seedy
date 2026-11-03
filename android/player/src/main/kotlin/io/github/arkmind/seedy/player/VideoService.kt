package io.github.arkmind.seedy.player

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/**
 * Settings · Playback › "Keep the sound going with the screen off": while a video plays, this service holds its media
 * session, so the system shows its controls and lets it run in the background (a foreground service while it plays).
 * The video player connects when it opens with the setting on, and lets go when it closes.
 */
@OptIn(UnstableApi::class)
class VideoService : MediaSessionService() {
    override fun onCreate() {
        super.onCreate()
        current?.let { session ->
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                session.setSessionActivity(PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            addSession(session)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = current

    /** Swiping the app away stops the sound. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        current?.player?.pause()
        stopSelf()
    }

    override fun onDestroy() {
        current?.let { if (isSessionAdded(it)) removeSession(it) }
        super.onDestroy()
    }

    companion object {
        /** The playing video's session (one player at a time). */
        @Volatile internal var current: MediaSession? = null

        /** Hands [session] to the service; the controller it returns keeps the service bound until [stop]. */
        internal fun start(context: Context, session: MediaSession): ListenableFuture<MediaController> {
            current = session
            return MediaController.Builder(context, SessionToken(context, ComponentName(context, VideoService::class.java))).buildAsync()
        }

        internal fun stop(context: Context, session: MediaSession, controller: ListenableFuture<MediaController>?) {
            controller?.let { runCatching { MediaController.releaseFuture(it) } }
            if (current === session) {
                current = null
                runCatching { context.stopService(Intent(context, VideoService::class.java)) }
            }
        }
    }
}
