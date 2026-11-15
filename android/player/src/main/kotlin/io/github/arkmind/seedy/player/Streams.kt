package io.github.arkmind.seedy.player

import io.github.arkmind.seedy.core.data.ioOrNull
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.session.UserSession
import io.github.arkmind.seedy.core.session.queryString
import io.github.arkmind.seedy.core.session.serverBase
import io.github.arkmind.seedy.core.session.withApiKey
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

/** Why playback couldn't start, with Jellyfin's codes ("NotAllowed", "NoCompatibleStream", "RateLimitExceeded"). */
class PlaybackProblem(val code: String) : Exception(code)

/** A problem's title and explanation for the player's error page: Jellyfin's codes and the player's own. */
fun problemText(code: String): Pair<String, String> = when (code) {
    "NotAllowed" -> t("Not allowed on this profile") to t("Your profile isn't allowed to play this title. Ask the server's admin if that's unexpected.")
    "NoCompatibleStream" -> t("Can't play this here") to t("This device can't play the file and the server couldn't convert it.")
    "RateLimitExceeded" -> t("Server is busy") to t("Too many streams are playing on this server right now. Try again in a moment.")
    "network" -> t("Can't reach the server") to t("The stream stopped loading. Check your connection and try again.")
    "media" -> t("Playback stopped") to t("The video couldn't be decoded on this device.")
    "serverError" -> t("Couldn't start playback") to t("The server couldn't prepare this title for playback. Try again in a moment.")
    "liveUnavailable" -> t("This channel isn't available right now") to t("The server couldn't open the channel's stream. Try another channel or come back later.")
    else -> t("Couldn't start playback") to code
}

/** A subtitle or sound offset in the delay controls: "+0.5 s", "−1.0 s", "0 s". */
fun offsetLabel(ms: Long): String = when {
    ms > 0 -> "+%.1f s".format(ms / 1000.0)
    ms < 0 -> "−%.1f s".format(-ms / 1000.0)
    else -> "0 s"
}

/** What to ask the server for. Stream indexes are Jellyfin's; subtitle -1 is off. */
data class StreamOptions(
    val mediaSourceId: String? = null,
    val audioIndex: Int? = null,
    val subtitleIndex: Int = -1,
    val maxBitrate: Int = Quality.Auto.bitrate,
    val startTicks: Long = 0,
    /** Convert even though the file would play as it is (a picture subtitle to burn in, a lower quality). */
    val forceTranscode: Boolean = false,
    /** The server converts the picture to H.264 instead of copying it: this device failed to decode it. */
    val convertVideo: Boolean = false,
    /** The server converts the sound to stereo AAC instead of copying it: this device failed to play it. */
    val convertAudio: Boolean = false,
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
    /** The device profile for a file whose picture is this stream. */
    private val profile: (MediaStream?) -> DeviceProfile,
    private val liveProfile: () -> DeviceProfile = { profile(null) },
) {
    private val api get() = session.api
    private val deviceId get() = session.account.deviceId

    suspend fun request(item: BaseItemDto, opts: StreamOptions): ActiveStream = withContext(Dispatchers.IO) {
        val live = item.type == BaseItemKind.TV_CHANNEL
        val msId = if (live) null else opts.mediaSourceId ?: item.mediaSources?.firstOrNull()?.id ?: item.id.toString()
        val burn = opts.forceTranscode && opts.subtitleIndex >= 0
        val convert = opts.convertVideo || opts.convertAudio
        val directPlay = !live && !opts.forceTranscode && !convert
        val info = api.mediaInfoApi.getPostedPlaybackInfo(
            item.id,
            PlaybackInfoDto(
                userId = session.userId,
                maxStreamingBitrate = opts.maxBitrate,
                startTimeTicks = opts.startTicks,
                audioStreamIndex = opts.audioIndex,
                subtitleStreamIndex = opts.subtitleIndex,
                mediaSourceId = msId,
                deviceProfile = Profiles.converting(if (live) liveProfile() else profile(video(item, msId)), opts.convertVideo, opts.convertAudio),
                enableDirectPlay = directPlay,
                enableDirectStream = !convert,
                enableTranscoding = true,
                allowVideoStreamCopy = !opts.convertVideo,
                allowAudioStreamCopy = !opts.convertAudio,
                autoOpenLiveStream = true,
                alwaysBurnInSubtitleWhenTranscoding = burn,
            ),
        ).content
        info.errorCode?.let { throw PlaybackProblem(it.serialName) }
        val source = (if (live) null else info.mediaSources.firstOrNull { it.id == msId }) ?: info.mediaSources.firstOrNull()
            ?: throw PlaybackProblem("NoCompatibleStream")
        val audioIndex = opts.audioIndex ?: source.defaultAudioStreamIndex
        if (source.supportsDirectPlay && directPlay) {
            val container = (source.container ?: "mp4").substringBefore(',')
            return@withContext ActiveStream(
                playSessionId = info.playSessionId, source = source, method = PlayMethod.DIRECT_PLAY,
                url = "${api.serverBase}/Videos/${item.id}/stream.$container?${session.fileQuery(source, info.playSessionId)}", isHls = false,
                audioIndex = audioIndex, subtitleIndex = opts.subtitleIndex, burnIn = false, options = opts,
            )
        }
        val path = source.transcodingUrl ?: throw PlaybackProblem("NoCompatibleStream")
        val url = api.withApiKey(path)
        val reasons = Regex("[?&]TranscodeReasons=([^&]*)").find(url)?.groupValues?.get(1).orEmpty()
        val videoReasons = Regex("Video|ContainerBitrateExceedsLimit|SubtitleCodecNotSupported|RefFrames|Anamorphic|Interlaced|Unknown")
        val sub = source.mediaStreams.orEmpty().firstOrNull { it.type == MediaStreamType.SUBTITLE && it.index == opts.subtitleIndex }
        ActiveStream(
            playSessionId = info.playSessionId, source = source,
            method = if (!opts.convertVideo && reasons.isNotEmpty() && !videoReasons.containsMatchIn(reasons)) PlayMethod.DIRECT_STREAM else PlayMethod.TRANSCODE,
            url = url, isHls = url.contains(".m3u8", ignoreCase = true) || source.transcodingSubProtocol.serialName == "hls",
            audioIndex = audioIndex, subtitleIndex = opts.subtitleIndex,
            burnIn = sub != null && sub.deliveryMethod == SubtitleDeliveryMethod.ENCODE,
            options = opts,
        )
    }

    private fun video(item: BaseItemDto, mediaSourceId: String?): MediaStream? =
        (item.mediaSources?.firstOrNull { it.id == mediaSourceId } ?: item.mediaSources?.firstOrNull())
            ?.mediaStreams?.firstOrNull { it.type == MediaStreamType.VIDEO }

    /** The WebVTT address of a text subtitle (the server converts SRT, ASS… and extracts embedded ones). */
    fun subtitleUrl(item: BaseItemDto, source: MediaSourceInfo, stream: MediaStream): String {
        val url = stream.deliveryUrl
        if (url != null && stream.isExternalUrl == true) return url
        if (url != null) return api.withApiKey(url.replace(Regex("/Stream\\.[a-z0-9]+(\\?|$)", RegexOption.IGNORE_CASE), "/Stream.vtt$1"))
        return api.withApiKey("/Videos/${item.id}/${source.id}/Subtitles/${stream.index}/0/Stream.vtt")
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
}

/** The query of a file streamed as it is ("static"), for Videos/{id}/stream and Audio/{id}/stream. */
internal fun UserSession.fileQuery(source: MediaSourceInfo, playSessionId: String?): String = queryString(
    buildMap {
        put("static", "true")
        put("mediaSourceId", source.id.orEmpty())
        put("deviceId", account.deviceId)
        playSessionId?.let { put("PlaySessionId", it) }
        source.eTag?.let { put("Tag", it) }
        put("ApiKey", api.accessToken.orEmpty())
    },
)

/** A playback report or session call: off the main thread, and a failure is dropped. */
internal suspend fun quietly(block: suspend () -> Unit) {
    ioOrNull { block() }
}
