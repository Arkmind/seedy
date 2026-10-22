package io.github.arkmind.seedy.player

import org.jellyfin.sdk.model.api.CodecProfile
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DirectPlayProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.ProfileCondition
import org.jellyfin.sdk.model.api.ProfileConditionType
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.SubtitleProfile
import org.jellyfin.sdk.model.api.TranscodingProfile

/** Quality choices, as on the web (Settings · Playback and the player's Quality menu). */
enum class Quality(val label: String, val bitrate: Int) {
    Auto("Auto", 120_000_000),
    Uhd("4K · 80 Mbps", 80_000_000),
    FullHd("1080p · 20 Mbps", 20_000_000),
    Hd("720p · 8 Mbps", 8_000_000),
    Sd("480p · 3 Mbps", 3_000_000),
}

/**
 * This device's settings that shape the profile (Settings · Playback and Subtitles, "This phone" / "This TV"):
 * [maxAudioChannels] 0 = what the output takes; [maxWidth] null = no limit when converting; [burnIn] "auto", "picture",
 * "styled" or "all".
 */
data class ProfileOptions(
    val maxAudioChannels: Int = 0,
    val maxWidth: Int? = null,
    val pictureSubtitles: Boolean = true,
    val burnIn: String = "auto",
    val hevc: Boolean = true,
    val av1: Boolean = true,
    val hdr: Boolean = true,
    val dts: Boolean = true,
    val truehd: Boolean = true,
    val mkv: Boolean = true,
    val preferFmp4: Boolean = false,
) {
    /** Leaves out what the settings turned off (a codec, HDR, DTS…) from what this device says it plays. */
    fun apply(caps: Capabilities): Capabilities = caps.copy(
        videoLevels = caps.videoLevels.filterKeys { (hevc || it != "hevc") && (av1 || it != "av1") },
        videoRanges = if (hdr) caps.videoRanges else caps.videoRanges.filter { it == "SDR" || it == "DOVIWithSDR" }.toSet(),
        audioCodecs = caps.audioCodecs.filter { (dts || (it != "dts" && it != "dca")) && (truehd || it != "truehd") }.toSet(),
    )
}

/**
 * The device profile sent with PlaybackInfo: what this device plays as it is (ExoPlayer reads MP4, Matroska, WebM
 * and MPEG-TS, switches audio tracks inside a file and shows picture subtitles), and what the server should convert
 * to when it can't (HLS with H.264, plus HEVC and AV1 where they decode).
 */
object Profiles {
    private fun cond(type: ProfileConditionType, prop: ProfileConditionValue, value: String) =
        ProfileCondition(condition = type, property = prop, value = value, isRequired = false)

    fun build(detected: Capabilities, maxBitrate: Int, opts: ProfileOptions = ProfileOptions(), source: MediaStream? = null): DeviceProfile {
        val caps = opts.apply(detected)
        val video = caps.videoCodecs
        val audio = caps.audioCodecs
        val videoCodecs = listOf("h264", "hevc", "av1", "vp9", "vp8", "mpeg2video", "mpeg4", "vc1").filter { it in video }.joinToString(",")
        val audioCodecs = listOf("aac", "mp3", "mp2", "ac3", "eac3", "dts", "dca", "truehd", "flac", "alac", "opus", "vorbis", "pcm_s16le", "pcm_s24le", "pcm_f32le")
            .filter { it in audio }.joinToString(",")

        val direct = listOfNotNull(
            DirectPlayProfile(container = "mp4,m4v,mov", type = DlnaProfileType.VIDEO, videoCodec = videoCodecs, audioCodec = audioCodecs),
            DirectPlayProfile(container = "mkv", type = DlnaProfileType.VIDEO, videoCodec = videoCodecs, audioCodec = audioCodecs).takeIf { opts.mkv },
            DirectPlayProfile(container = "webm", type = DlnaProfileType.VIDEO, videoCodec = listOf("vp8", "vp9", "av1").filter { it in video }.joinToString(","), audioCodec = "opus,vorbis"),
            DirectPlayProfile(container = "ts,mpegts,m2ts", type = DlnaProfileType.VIDEO, videoCodec = videoCodecs, audioCodec = audioCodecs),
            DirectPlayProfile(container = "mp3", type = DlnaProfileType.AUDIO, audioCodec = "mp3"),
            DirectPlayProfile(container = "aac", type = DlnaProfileType.AUDIO, audioCodec = "aac"),
            DirectPlayProfile(container = "m4a,m4b,mp4", type = DlnaProfileType.AUDIO, audioCodec = listOf("aac", "alac", "flac", "opus").filter { it in audio }.joinToString(",")),
            DirectPlayProfile(container = "flac", type = DlnaProfileType.AUDIO, audioCodec = "flac"),
            DirectPlayProfile(container = "ogg,oga,opus,webm", type = DlnaProfileType.AUDIO, audioCodec = "opus,vorbis"),
            DirectPlayProfile(container = "wav", type = DlnaProfileType.AUDIO, audioCodec = "pcm_s16le,pcm_s24le"),
        )

        // MPEG-TS segments: Media3 can't read Jellyfin's fMP4 once it converts the sound (the first segment starts just
        // before zero). fMP4 only when asked, or for a picture TS can't carry as it is (AV1, Dolby Vision without a base
        // layer) of the [source] file.
        val range = source?.videoRangeType?.serialName
        val fmp4 = opts.preferFmp4 || (source?.codec == "av1" && "av1" in video) || (range == "DOVI" && range in caps.videoRanges)
        val hlsVideo = listOfNotNull("h264", "hevc".takeIf { it in video }, "av1".takeIf { fmp4 && it in video }).joinToString(",")
        val hlsAudio = listOfNotNull("aac", "mp3", "ac3".takeIf { it in audio }, "eac3".takeIf { it in audio }).joinToString(",")
        val channels = if (opts.maxAudioChannels > 0) opts.maxAudioChannels else if (caps.outputChannels >= 6 || "ac3" in audio) 6 else 2
        // Largest picture when converting.
        val widthLimit = opts.maxWidth?.let { listOf(cond(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.WIDTH, it.toString())) }.orEmpty()
        val transcoding = listOf(
            TranscodingProfile(
                container = if (fmp4) "mp4" else "ts", type = DlnaProfileType.VIDEO, videoCodec = hlsVideo, audioCodec = hlsAudio,
                protocol = MediaStreamProtocol.HLS, context = EncodingContext.STREAMING, maxAudioChannels = channels.toString(),
                minSegments = 1, breakOnNonKeyFrames = true, conditions = widthLimit,
            ),
            TranscodingProfile(
                container = "mp3", type = DlnaProfileType.AUDIO, videoCodec = "", audioCodec = "mp3", protocol = MediaStreamProtocol.HTTP,
                context = EncodingContext.STREAMING, maxAudioChannels = minOf(2, channels).toString(), conditions = emptyList(),
            ),
        )

        val ranges = caps.videoRanges.joinToString("|")
        val notAnamorphic = cond(ProfileConditionType.NOT_EQUALS, ProfileConditionValue.IS_ANAMORPHIC, "true")
        val codecs = buildList {
            // Most audio channels: more than that is converted.
            if (opts.maxAudioChannels > 0) {
                val fewer = listOf(cond(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.AUDIO_CHANNELS, opts.maxAudioChannels.toString()))
                add(CodecProfile(type = CodecType.VIDEO_AUDIO, applyConditions = emptyList(), conditions = fewer))
                add(CodecProfile(type = CodecType.AUDIO, applyConditions = emptyList(), conditions = fewer))
            }
            caps.videoLevels["h264"]?.let { level ->
                add(
                    CodecProfile(
                        type = CodecType.VIDEO, codec = "h264", applyConditions = emptyList(),
                        conditions = listOf(
                            notAnamorphic,
                            cond(ProfileConditionType.EQUALS_ANY, ProfileConditionValue.VIDEO_PROFILE, "high|main|baseline|constrained baseline"),
                            cond(ProfileConditionType.EQUALS_ANY, ProfileConditionValue.VIDEO_RANGE_TYPE, "SDR"),
                            cond(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.VIDEO_LEVEL, (if (level > 0) level else 52).toString()),
                            cond(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.VIDEO_BIT_DEPTH, "8"),
                        ) + sizeConditions(caps, "h264"),
                    ),
                )
            }
            caps.videoLevels["hevc"]?.let { level ->
                add(
                    CodecProfile(
                        type = CodecType.VIDEO, codec = "hevc", applyConditions = emptyList(),
                        conditions = listOf(
                            notAnamorphic,
                            cond(ProfileConditionType.EQUALS_ANY, ProfileConditionValue.VIDEO_PROFILE, if (caps.hevc10) "main|main 10" else "main"),
                            cond(ProfileConditionType.EQUALS_ANY, ProfileConditionValue.VIDEO_RANGE_TYPE, ranges),
                            cond(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.VIDEO_LEVEL, (if (level > 0) level else 153).toString()),
                        ) + sizeConditions(caps, "hevc"),
                    ),
                )
            }
            caps.videoLevels["av1"]?.let { level ->
                add(
                    CodecProfile(
                        type = CodecType.VIDEO, codec = "av1", applyConditions = emptyList(),
                        conditions = listOf(
                            cond(ProfileConditionType.EQUALS_ANY, ProfileConditionValue.VIDEO_PROFILE, "main"),
                            cond(ProfileConditionType.EQUALS_ANY, ProfileConditionValue.VIDEO_RANGE_TYPE, ranges),
                            cond(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.VIDEO_LEVEL, (if (level > 0) level else 15).toString()),
                        ) + sizeConditions(caps, "av1"),
                    ),
                )
            }
            if ("vp9" in video) {
                add(
                    CodecProfile(
                        type = CodecType.VIDEO, codec = "vp9", applyConditions = emptyList(),
                        conditions = listOf(cond(ProfileConditionType.EQUALS_ANY, ProfileConditionValue.VIDEO_RANGE_TYPE, ranges)) + sizeConditions(caps, "vp9"),
                    ),
                )
            }
        }

        // Text subtitles always come from the server as their own file (WebVTT), so Seedy draws them itself and can
        // move them earlier or later; picture subtitles play from the file when it plays as it is, and are burnt into
        // the video when the server converts. "Burn subtitles in" and "Show picture subtitles here" take some of that
        // away, and the server burns in whatever no other method covers.
        val subtitles = buildList {
            for (f in TextFormats) {
                val styled = f == "ass" || f == "ssa"
                val burn = opts.burnIn == "all" || (styled && opts.burnIn == "styled")
                add(SubtitleProfile(format = f, method = if (burn) SubtitleDeliveryMethod.ENCODE else SubtitleDeliveryMethod.EXTERNAL))
            }
            if (opts.pictureSubtitles && opts.burnIn == "auto") {
                for (f in listOf("pgssub", "pgs", "dvdsub", "vobsub", "dvbsub", "dvb_subtitle")) {
                    add(SubtitleProfile(format = f, method = SubtitleDeliveryMethod.EMBED))
                }
            }
            for (f in PictureFormats) {
                add(SubtitleProfile(format = f, method = SubtitleDeliveryMethod.ENCODE))
            }
        }

        return DeviceProfile(
            name = "Seedy for Android",
            maxStreamingBitrate = maxBitrate,
            maxStaticBitrate = 200_000_000,
            musicStreamingTranscodingBitrate = 384_000,
            directPlayProfiles = direct,
            transcodingProfiles = transcoding,
            containerProfiles = emptyList(),
            codecProfiles = codecs,
            subtitleProfiles = subtitles,
        )
    }

    /**
     * Live channels convert to HLS in MPEG-TS segments: Jellyfin offers fMP4 only for tuners that allow it and
     * otherwise falls back to a progressive stream, so the live profile asks for TS with H.264 and the audio TS carries.
     */
    fun buildLive(caps: Capabilities, maxBitrate: Int, opts: ProfileOptions = ProfileOptions()): DeviceProfile {
        val base = build(caps, maxBitrate, opts)
        val audio = listOf("aac", "mp3", "ac3", "eac3").filter { it == "aac" || it == "mp3" || it in caps.audioCodecs }.joinToString(",")
        return base.copy(
            transcodingProfiles = base.transcodingProfiles.map {
                if (it.type == DlnaProfileType.VIDEO && it.protocol == MediaStreamProtocol.HLS) it.copy(container = "ts", videoCodec = "h264", audioCodec = audio) else it
            },
        )
    }

    /**
     * [profile] asking the server to convert to what every Android device plays, in MPEG-TS segments: H.264 pictures
     * when [video], stereo AAC sound when [audio] (after this device failed on the file's own).
     */
    fun converting(profile: DeviceProfile, video: Boolean, audio: Boolean): DeviceProfile {
        if (!video && !audio) return profile
        return profile.copy(
            transcodingProfiles = profile.transcodingProfiles.map {
                if (it.type != DlnaProfileType.VIDEO) it
                else it.copy(
                    container = "ts",
                    videoCodec = if (video) "h264" else it.videoCodec,
                    audioCodec = if (audio) "aac" else it.audioCodec,
                    maxAudioChannels = if (audio) "2" else it.maxAudioChannels,
                )
            },
        )
    }

    private val TextFormats = listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt", "ttml", "mov_text", "tx3g", "smi", "sami", "microdvd")
    private val PictureFormats = listOf("pgssub", "pgs", "dvdsub", "vobsub", "dvbsub", "dvb_subtitle", "sub", "idx")

    private fun sizeConditions(caps: Capabilities, codec: String): List<ProfileCondition> {
        val (w, h) = caps.maxSize[codec] ?: return emptyList()
        return listOf(
            cond(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.WIDTH, w.toString()),
            cond(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.HEIGHT, h.toString()),
        )
    }
}
