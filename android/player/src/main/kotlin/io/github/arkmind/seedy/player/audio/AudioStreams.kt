package io.github.arkmind.seedy.player.audio

import android.content.Context
import io.github.arkmind.seedy.core.format.TICKS_PER_SECOND
import io.github.arkmind.seedy.core.session.UserSession
import io.github.arkmind.seedy.player.CapabilitiesCache
import io.github.arkmind.seedy.player.PlaybackProblem
import io.github.arkmind.seedy.player.Profiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.QueueItem
import org.jellyfin.sdk.model.api.RepeatMode
import java.net.URLEncoder
import java.util.UUID
import kotlin.math.roundToLong

/** A song's or book file's stream: [offset] seconds into the file is where a conversion's time 0 starts. */
internal data class AudioStream(val url: String, val method: PlayMethod, val source: MediaSourceInfo, val playSessionId: String?, val offset: Double)

/** One report's facts (the web's Report body). */
internal data class AudioReport(
    val itemId: UUID,
    val stream: AudioStream,
    val positionTicks: Long,
    val paused: Boolean,
    val speed: Float,
    val repeat: Repeat,
    val shuffle: Boolean,
    val queue: List<QueueItem>?,
)

/** PlaybackInfo → an audio stream, and the Sessions/Playing reports around it (web/src/audio/api.ts). */
internal class AudioStreams(private val context: Context, private val session: UserSession) {
    private val api get() = session.api
    private val base get() = api.baseUrl.orEmpty().trimEnd('/')
    private val token get() = api.accessToken.orEmpty()

    /** Asks the server how to play [id] here: the file as it is when the phone can, otherwise a conversion. */
    suspend fun open(id: UUID, startSeconds: Double, convert: Boolean): AudioStream = withContext(Dispatchers.IO) {
        val maxBitrate = 140_000_000
        val startTicks = (startSeconds * TICKS_PER_SECOND).roundToLong()
        val info = api.mediaInfoApi.getPostedPlaybackInfo(
            id,
            PlaybackInfoDto(
                userId = session.userId, maxStreamingBitrate = maxBitrate, startTimeTicks = startTicks,
                deviceProfile = Profiles.build(CapabilitiesCache.get(context), maxBitrate),
                enableDirectPlay = !convert, enableDirectStream = !convert, enableTranscoding = true, allowAudioStreamCopy = !convert,
            ),
        ).content
        info.errorCode?.let { throw PlaybackProblem(it.serialName) }
        val source = info.mediaSources.firstOrNull() ?: throw PlaybackProblem("NoCompatibleStream")
        if (!convert && source.supportsDirectPlay) {
            val q = buildMap {
                put("static", "true")
                put("mediaSourceId", source.id.orEmpty())
                put("deviceId", session.account.deviceId)
                info.playSessionId?.let { put("PlaySessionId", it) }
                source.eTag?.let { put("Tag", it) }
                put("ApiKey", token)
            }
            return@withContext AudioStream("$base/Audio/$id/stream?${query(q)}", PlayMethod.DIRECT_PLAY, source, info.playSessionId, 0.0)
        }
        val path = source.transcodingUrl ?: throw PlaybackProblem("NoCompatibleStream")
        var url = withApiKey(path.replace(Regex("^/audio/"), "/Audio/"))
        if (startTicks > 0 && !Regex("StartTimeTicks=", RegexOption.IGNORE_CASE).containsMatchIn(url)) url += "&StartTimeTicks=$startTicks"
        val copy = Regex("AudioCodec=copy|static=true", RegexOption.IGNORE_CASE).containsMatchIn(url) || source.supportsDirectStream
        AudioStream(url, if (copy && !convert) PlayMethod.DIRECT_STREAM else PlayMethod.TRANSCODE, source, info.playSessionId, if (startTicks > 0) startSeconds else 0.0)
    }

    private fun withApiKey(path: String): String {
        val full = if (path.startsWith("http")) path else base + path
        if (Regex("[?&](ApiKey|api_key)=", RegexOption.IGNORE_CASE).containsMatchIn(full)) return full
        return full + (if ('?' in full) "&" else "?") + "ApiKey=" + enc(token)
    }

    suspend fun reportStart(r: AudioReport) = quietly {
        api.sessionApi.reportPlaybackStart(
            PlaybackStartInfo(
                canSeek = true, itemId = r.itemId, isPaused = r.paused, isMuted = false, playMethod = r.stream.method,
                repeatMode = r.repeat.jellyfin, playbackOrder = if (r.shuffle) PlaybackOrder.SHUFFLE else PlaybackOrder.DEFAULT,
                mediaSourceId = r.stream.source.id, playSessionId = r.stream.playSessionId, positionTicks = r.positionTicks, nowPlayingQueue = r.queue,
            ),
        )
    }

    suspend fun reportProgress(r: AudioReport) = quietly {
        api.sessionApi.reportPlaybackProgress(
            PlaybackProgressInfo(
                canSeek = true, itemId = r.itemId, isPaused = r.paused, isMuted = false, playMethod = r.stream.method,
                repeatMode = r.repeat.jellyfin, playbackOrder = if (r.shuffle) PlaybackOrder.SHUFFLE else PlaybackOrder.DEFAULT,
                mediaSourceId = r.stream.source.id, playSessionId = r.stream.playSessionId, positionTicks = r.positionTicks, nowPlayingQueue = r.queue,
            ),
        )
    }

    suspend fun reportStopped(r: AudioReport) = quietly {
        api.sessionApi.reportPlaybackStopped(
            PlaybackStopInfo(
                itemId = r.itemId, failed = false, mediaSourceId = r.stream.source.id, playSessionId = r.stream.playSessionId, positionTicks = r.positionTicks,
                nowPlayingQueue = r.queue,
            ),
        )
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        withContext(Dispatchers.IO) { runCatching { block() } }
    }

    private val Repeat.jellyfin get() = when (this) {
        Repeat.Off -> RepeatMode.REPEAT_NONE
        Repeat.All -> RepeatMode.REPEAT_ALL
        Repeat.One -> RepeatMode.REPEAT_ONE
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun query(q: Map<String, String>) = q.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
}
