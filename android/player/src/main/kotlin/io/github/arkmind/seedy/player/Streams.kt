package io.github.arkmind.seedy.player

import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.delete
import org.jellyfin.sdk.api.client.extensions.post
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import java.net.URLEncoder

/** Why playback couldn't start, with Jellyfin's codes ("NotAllowed", "NoCompatibleStream", "RateLimitExceeded"). */
class PlaybackProblem(val code: String) : Exception(code)

/** What to ask the server for. Stream indexes are Jellyfin's; subtitle -1 is off. */
data class StreamOptions(
    val mediaSourceId: String? = null,
    val audioIndex: Int? = null,
    val subtitleIndex: Int = -1,
    val maxBitrate: Int = Quality.Auto.bitrate,
    val startTicks: Long = 0,
    /** Convert even though the file would play as it is (a picture subtitle to burn in, a lower quality). */
    val forceTranscode: Boolean = false,
)

/** A stream the player can open: where it is, how it's delivered, and the file it comes from. */
data class ActiveStream(
    val playSessionId: String?,
    val source: MediaSourceInfo,
    val method: PlayMethod,
    val url: String,
    val isHls: Boolean,
    val audioIndex: Int?,
    val subtitleIndex: Int,
    /** The server draws the chosen subtitle into the picture. */
    val burnIn: Boolean,
    val options: StreamOptions,
) {
    val audioStreams: List<MediaStream> get() = source.mediaStreams.orEmpty().filter { it.type == MediaStreamType.AUDIO }
    val subtitleStreams: List<MediaStream> get() = source.mediaStreams.orEmpty().filter { it.type == MediaStreamType.SUBTITLE }
    val videoStream: MediaStream? get() = source.mediaStreams.orEmpty().firstOrNull { it.type == MediaStreamType.VIDEO }
}

/** PlaybackInfo → a stream URL, and the Sessions/Playing reports around it (web/src/player/playback.ts, for Android). */
class Streams(
    private val session: UserSession,
    private val profile: () -> DeviceProfile,
    private val liveProfile: () -> DeviceProfile = profile,
) {
    private val api get() = session.api
    private val base get() = api.baseUrl.orEmpty().trimEnd('/')
    private val token get() = api.accessToken.orEmpty()
    private val deviceId get() = session.account.deviceId

    suspend fun request(item: BaseItemDto, opts: StreamOptions): ActiveStream = withContext(Dispatchers.IO) {
        val live = item.type == BaseItemKind.TV_CHANNEL
        val msId = if (live) null else opts.mediaSourceId ?: item.mediaSources?.firstOrNull()?.id ?: item.id.toString()
        val burn = opts.forceTranscode && opts.subtitleIndex >= 0
        val info = api.mediaInfoApi.getPostedPlaybackInfo(
            item.id,
            PlaybackInfoDto(
                userId = session.userId,
                maxStreamingBitrate = opts.maxBitrate,
                startTimeTicks = opts.startTicks,
                audioStreamIndex = opts.audioIndex,
                subtitleStreamIndex = opts.subtitleIndex,
                mediaSourceId = msId,
                deviceProfile = if (live) liveProfile() else profile(),
                enableDirectPlay = !live && !opts.forceTranscode,
                enableDirectStream = true,
                enableTranscoding = true,
                allowVideoStreamCopy = true,
                allowAudioStreamCopy = true,
                autoOpenLiveStream = true,
                alwaysBurnInSubtitleWhenTranscoding = burn,
            ),
        ).content
        info.errorCode?.let { throw PlaybackProblem(it.serialName) }
        val source = (if (live) null else info.mediaSources.firstOrNull { it.id == msId }) ?: info.mediaSources.firstOrNull()
            ?: throw PlaybackProblem("NoCompatibleStream")
        val audioIndex = opts.audioIndex ?: source.defaultAudioStreamIndex
        if (source.supportsDirectPlay && !live && !opts.forceTranscode) {
            val container = (source.container ?: "mp4").substringBefore(',')
            val q = buildMap {
                put("static", "true")
                put("mediaSourceId", source.id.orEmpty())
                put("deviceId", deviceId)
                info.playSessionId?.let { put("PlaySessionId", it) }
                source.eTag?.let { put("Tag", it) }
                put("ApiKey", token)
            }
            return@withContext ActiveStream(
                playSessionId = info.playSessionId, source = source, method = PlayMethod.DIRECT_PLAY,
                url = "$base/Videos/${item.id}/stream.$container?${query(q)}", isHls = false,
                audioIndex = audioIndex, subtitleIndex = opts.subtitleIndex, burnIn = false, options = opts,
            )
        }
        val path = source.transcodingUrl ?: throw PlaybackProblem("NoCompatibleStream")
        val url = withApiKey(path)
        val reasons = Regex("[?&]TranscodeReasons=([^&]*)").find(url)?.groupValues?.get(1).orEmpty()
        val videoReasons = Regex("Video|ContainerBitrateExceedsLimit|SubtitleCodecNotSupported|RefFrames|Anamorphic|Interlaced|Unknown")
        val sub = source.mediaStreams.orEmpty().firstOrNull { it.type == MediaStreamType.SUBTITLE && it.index == opts.subtitleIndex }
        ActiveStream(
            playSessionId = info.playSessionId, source = source,
            method = if (reasons.isNotEmpty() && !videoReasons.containsMatchIn(reasons)) PlayMethod.DIRECT_STREAM else PlayMethod.TRANSCODE,
            url = url, isHls = url.contains(".m3u8", ignoreCase = true) || source.transcodingSubProtocol.serialName == "hls",
            audioIndex = audioIndex, subtitleIndex = opts.subtitleIndex,
            burnIn = sub != null && sub.deliveryMethod == SubtitleDeliveryMethod.ENCODE,
            options = opts,
        )
    }

    /** The WebVTT address of a text subtitle (the server converts SRT, ASS… and extracts embedded ones). */
    fun subtitleUrl(item: BaseItemDto, source: MediaSourceInfo, stream: MediaStream): String {
        val url = stream.deliveryUrl
        if (url != null && stream.isExternalUrl == true) return url
        if (url != null) return withApiKey(url.replace(Regex("/Stream\\.[a-z0-9]+(\\?|$)", RegexOption.IGNORE_CASE), "/Stream.vtt$1"))
        return withApiKey("/Videos/${item.id}/${source.id}/Subtitles/${stream.index}/0/Stream.vtt")
    }

    fun withApiKey(path: String): String {
        val full = if (path.startsWith("http")) path else base + path
        if (Regex("[?&](ApiKey|api_key)=", RegexOption.IGNORE_CASE).containsMatchIn(full)) return full
        return full + (if ('?' in full) "&" else "?") + "ApiKey=" + enc(token)
    }

    suspend fun reportStart(r: Report) = quietly {
        api.sessionApi.reportPlaybackStart(
            PlaybackStartInfo(
                canSeek = r.canSeek, itemId = r.item.id, isPaused = r.paused, isMuted = false, playMethod = r.stream.method,
                repeatMode = RepeatMode.REPEAT_NONE, playbackOrder = PlaybackOrder.DEFAULT, mediaSourceId = r.stream.source.id,
                playSessionId = r.stream.playSessionId, positionTicks = r.positionTicks, audioStreamIndex = r.stream.audioIndex,
                subtitleStreamIndex = r.subtitleIndex, liveStreamId = r.stream.source.liveStreamId,
            ),
        )
    }

    suspend fun reportProgress(r: Report) = quietly {
        api.sessionApi.reportPlaybackProgress(
            PlaybackProgressInfo(
                canSeek = r.canSeek, itemId = r.item.id, isPaused = r.paused, isMuted = false, playMethod = r.stream.method,
                repeatMode = RepeatMode.REPEAT_NONE, playbackOrder = PlaybackOrder.DEFAULT, mediaSourceId = r.stream.source.id,
                playSessionId = r.stream.playSessionId, positionTicks = r.positionTicks, audioStreamIndex = r.stream.audioIndex,
                subtitleStreamIndex = r.subtitleIndex, liveStreamId = r.stream.source.liveStreamId,
            ),
        )
    }

    suspend fun reportStopped(r: Report, failed: Boolean = false) = quietly {
        api.sessionApi.reportPlaybackStopped(
            PlaybackStopInfo(
                itemId = r.item.id, failed = failed, mediaSourceId = r.stream.source.id, playSessionId = r.stream.playSessionId,
                positionTicks = r.positionTicks, liveStreamId = r.stream.source.liveStreamId,
            ),
        )
    }

    suspend fun ping(stream: ActiveStream) = quietly { stream.playSessionId?.let { api.sessionApi.pingPlaybackSession(it) } }

    /** Ends the server's conversion for a stream that's no longer played. */
    suspend fun stopEncoding(stream: ActiveStream) = quietly {
        if (stream.method == PlayMethod.DIRECT_PLAY) return@quietly
        api.delete<Unit>("/Videos/ActiveEncodings", queryParameters = mapOf("deviceId" to deviceId, "playSessionId" to stream.playSessionId))
    }

    /** Frees the tuner a live channel holds. */
    suspend fun closeLive(stream: ActiveStream) = quietly {
        val id = stream.source.liveStreamId ?: return@quietly
        api.post<Unit>("/LiveStreams/Close", queryParameters = mapOf("liveStreamId" to id))
    }

    /** One report's facts: what plays, where it is, and how. */
    data class Report(
        val item: BaseItemDto,
        val stream: ActiveStream,
        val positionTicks: Long,
        val paused: Boolean,
        val speed: Float,
        val subtitleIndex: Int,
    ) {
        val canSeek: Boolean get() = stream.source.liveStreamId == null
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        withContext(Dispatchers.IO) { runCatching { block() } }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun query(q: Map<String, String>) = q.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
}
