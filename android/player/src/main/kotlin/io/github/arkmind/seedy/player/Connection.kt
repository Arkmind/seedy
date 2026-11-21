package io.github.arkmind.seedy.player

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl

/**
 * How the player rides out a weak or dropped connection: a deeper buffer than ExoPlayer's, a longer wait before going
 * on after running dry, and reconnecting where it stood when the stream breaks (Wi-Fi dropping, the server restarting).
 */
object Connection {
    /**
     * Read ahead two minutes, kept topped up (ExoPlayer keeps 50 s), so a dip in the Wi-Fi or a server restart plays
     * from the buffer. ExoPlayer's memory budget for the tracks still caps it: a 4K remux keeps its ~15 s.
     */
    const val BufferMs = 120_000

    /** To start, and after a seek: as soon as 2.5 s are in (ExoPlayer's own). */
    const val StartMs = 2_500

    /**
     * After running dry while playing: 10 s in before going on. ExoPlayer waits 5 s, which on a connection a little
     * slower than the stream froze the picture every few seconds.
     */
    const val RebufferMs = 10_000

    /**
     * The waits between reconnect attempts once ExoPlayer's own retries gave up: close together at first, then every
     * 15 s (so the picture is back soon after the line), well over a minute and a half in all, more than a server
     * restart takes.
     */
    val ReconnectDelaysMs: List<Long> = listOf(1_000L, 2_000L, 4_000L, 8_000L) + List(6) { 15_000L }

    /** Playing this long without a hitch refills the reconnect attempts. */
    const val SteadyMs = 30_000L

    /**
     * Past this since the stream first broke, Seedy says the server can't be reached instead of trying on (each attempt
     * at a file played as it is includes ExoPlayer's own retries, which can take half a minute against a dead line).
     */
    const val GiveUpMs = 180_000L

    @OptIn(UnstableApi::class)
    fun loadControl(): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(BufferMs, BufferMs, StartMs, RebufferMs)
        .build()

    /**
     * The player lost the server: no answer, the connection dropping, or the proxy in front of the server saying it's
     * gone ([httpStatus] 502–504, while it restarts). Not an answer from the server itself, like a failed conversion.
     */
    fun lost(errorCode: Int, httpStatus: Int? = null): Boolean =
        errorCode in NetworkErrors || (errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && httpStatus in 502..504)

    val NetworkErrors = setOf(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_TIMEOUT,
    )
}

/**
 * The reconnect attempts of one video: spent one at a time, for [Connection.GiveUpMs] at most since the stream first
 * broke, and refilled by [Connection.SteadyMs] of steady playback. Times come from a monotonic clock.
 */
class Reconnects(
    private val delaysMs: List<Long> = Connection.ReconnectDelaysMs,
    private val steadyMs: Long = Connection.SteadyMs,
    private val giveUpMs: Long = Connection.GiveUpMs,
) {
    var spent = 0
        private set
    private var lostAt = -1L
    private var steadySince = -1L

    /** The wait before the next attempt, or null once it's time to give up. */
    fun next(now: Long): Long? {
        if (lostAt < 0) lostAt = now
        if (now - lostAt > giveUpMs) return null
        return delaysMs.getOrNull(spent)?.also { spent++ }
    }

    /** Told a few times a second whether the video is playing. */
    fun playing(playing: Boolean, now: Long) {
        if (!playing) {
            steadySince = -1
            return
        }
        if (steadySince < 0) steadySince = now
        else if (now - steadySince >= steadyMs) {
            spent = 0
            lostAt = -1
        }
    }

    fun reset() {
        spent = 0
        lostAt = -1
        steadySince = -1
    }
}
