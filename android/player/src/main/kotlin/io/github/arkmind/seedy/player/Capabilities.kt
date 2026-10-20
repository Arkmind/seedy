package io.github.arkmind.seedy.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel as PL
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities

/**
 * What this device plays by itself, read from its decoders, its display and its audio output (HDMI passthrough on TVs).
 * Jellyfin's names are used throughout: "h264", "hevc", "ac3"…
 */
data class Capabilities(
    /** Video codec → highest level in Jellyfin's numbering (h264 52 = 5.2, hevc 153 = 5.1, av1 19 = 6.3). */
    val videoLevels: Map<String, Int>,
    /** HEVC Main 10 (10-bit) decoding. */
    val hevc10: Boolean,
    val av1Main10: Boolean,
    /** The largest picture decoded, per codec (width × height). */
    val maxSize: Map<String, Pair<Int, Int>>,
    /** Jellyfin's VideoRangeType names the screen shows properly ("SDR", "HDR10", "HLG", "DOVIWithHDR10"…). */
    val videoRanges: Set<String>,
    /** Audio codecs decoded here or passed through to a receiver. */
    val audioCodecs: Set<String>,
    /** Channels the output carries (2 on most phones, 6 or 8 over HDMI). */
    val outputChannels: Int,
) {
    val videoCodecs: Set<String> get() = videoLevels.keys

    companion object {
        @OptIn(UnstableApi::class)
        fun detect(context: Context): Capabilities {
            val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
            fun infos(mime: String) = decoders.filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
            fun caps(mime: String) = infos(mime).mapNotNull { runCatching { it.getCapabilitiesForType(mime) }.getOrNull() }

            val levels = mutableMapOf<String, Int>()
            val sizes = mutableMapOf<String, Pair<Int, Int>>()
            fun video(name: String, mime: String, level: (MediaCodecInfo.CodecCapabilities) -> Int?) {
                val all = caps(mime)
                if (all.isEmpty()) return
                levels[name] = all.mapNotNull(level).maxOrNull() ?: 0
                val v = all.mapNotNull { it.videoCapabilities }
                val w = v.maxOfOrNull { it.supportedWidths.upper } ?: 1920
                val h = v.maxOfOrNull { it.supportedHeights.upper } ?: 1080
                sizes[name] = w to h
            }
            video("h264", "video/avc") { c -> c.profileLevels.maxOfOrNull { avcLevel(it.level) } }
            video("hevc", "video/hevc") { c -> c.profileLevels.maxOfOrNull { hevcLevel(it.level) } }
            video("av1", "video/av01") { c -> c.profileLevels.maxOfOrNull { av1Level(it.level) } }
            video("vp9", "video/x-vnd.on2.vp9") { 0 }
            video("vp8", "video/x-vnd.on2.vp8") { 0 }
            video("mpeg2video", "video/mpeg2") { 0 }
            video("mpeg4", "video/mp4v-es") { 0 }
            video("vc1", "video/wvc1") { 0 }

            val hevc10 = caps("video/hevc").any { c -> c.profileLevels.any { it.profile == PL.HEVCProfileMain10 || it.profile == PL.HEVCProfileMain10HDR10 } }
            val av1Main10 = caps("video/av01").any { c -> c.profileLevels.any { it.profile == PL.AV1ProfileMain10 || it.profile == PL.AV1ProfileMain10HDR10 } }

            // HDR only where the screen shows it: elsewhere the server converts to SDR rather than the picture looking washed out.
            val hdrTypes = displayHdrTypes(context)
            val dvDecoder = infos("video/dolby-vision").isNotEmpty()
            val ranges = buildSet {
                add("SDR")
                if (hevc10 || av1Main10) {
                    if (Display.HdrCapabilities.HDR_TYPE_HDR10 in hdrTypes) addAll(listOf("HDR10", "DOVIWithHDR10"))
                    if (Display.HdrCapabilities.HDR_TYPE_HLG in hdrTypes) addAll(listOf("HLG", "DOVIWithHLG"))
                    if (Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in hdrTypes) addAll(listOf("HDR10Plus", "DOVIWithHDR10Plus"))
                    if (Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in hdrTypes && dvDecoder) add("DOVI")
                }
                // Dolby Vision with an SDR base layer plays as SDR on any HEVC decoder.
                if ("hevc" in levels) add("DOVIWithSDR")
            }

            val audio = AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null)
            val audioCodecs = buildSet {
                addAll(listOf("aac", "mp3", "mp2", "flac", "opus", "vorbis", "pcm_s16le", "pcm_s24le", "pcm_f32le"))
                fun has(mime: String) = infos(mime).isNotEmpty()
                if (has("audio/alac")) add("alac")
                if (has("audio/ac3") || audio.supportsEncoding(C.ENCODING_AC3)) add("ac3")
                if (has("audio/eac3") || audio.supportsEncoding(C.ENCODING_E_AC3) || audio.supportsEncoding(C.ENCODING_E_AC3_JOC)) add("eac3")
                if (has("audio/vnd.dts") || audio.supportsEncoding(C.ENCODING_DTS)) addAll(listOf("dts", "dca"))
                if (audio.supportsEncoding(C.ENCODING_DTS_HD)) add("dts")
                if (has("audio/true-hd") || audio.supportsEncoding(C.ENCODING_DOLBY_TRUEHD)) add("truehd")
            }
            return Capabilities(levels, hevc10, av1Main10, sizes, ranges, audioCodecs, audio.maxChannelCount.coerceAtLeast(2))
        }

        @Suppress("DEPRECATION")
        private fun displayHdrTypes(context: Context): Set<Int> {
            val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return emptySet()
            val types = if (Build.VERSION.SDK_INT >= 34) display.mode.supportedHdrTypes else display.hdrCapabilities?.supportedHdrTypes
            return types?.toSet().orEmpty()
        }

        /** Android's AVC level constants → Jellyfin's level × 10 (5.2 → 52). */
        private fun avcLevel(l: Int): Int = when (l) {
            PL.AVCLevel1, PL.AVCLevel1b -> 10
            PL.AVCLevel11 -> 11
            PL.AVCLevel12 -> 12
            PL.AVCLevel13 -> 13
            PL.AVCLevel2 -> 20
            PL.AVCLevel21 -> 21
            PL.AVCLevel22 -> 22
            PL.AVCLevel3 -> 30
            PL.AVCLevel31 -> 31
            PL.AVCLevel32 -> 32
            PL.AVCLevel4 -> 40
            PL.AVCLevel41 -> 41
            PL.AVCLevel42 -> 42
            PL.AVCLevel5 -> 50
            PL.AVCLevel51 -> 51
            PL.AVCLevel52 -> 52
            PL.AVCLevel6 -> 60
            PL.AVCLevel61 -> 61
            PL.AVCLevel62 -> 62
            else -> 0
        }

        /** Android's HEVC level constants → general_level_idc (level × 30: 5.1 → 153). */
        private fun hevcLevel(l: Int): Int = when (l) {
            PL.HEVCMainTierLevel1, PL.HEVCHighTierLevel1 -> 30
            PL.HEVCMainTierLevel2, PL.HEVCHighTierLevel2 -> 60
            PL.HEVCMainTierLevel21, PL.HEVCHighTierLevel21 -> 63
            PL.HEVCMainTierLevel3, PL.HEVCHighTierLevel3 -> 90
            PL.HEVCMainTierLevel31, PL.HEVCHighTierLevel31 -> 93
            PL.HEVCMainTierLevel4, PL.HEVCHighTierLevel4 -> 120
            PL.HEVCMainTierLevel41, PL.HEVCHighTierLevel41 -> 123
            PL.HEVCMainTierLevel5, PL.HEVCHighTierLevel5 -> 150
            PL.HEVCMainTierLevel51, PL.HEVCHighTierLevel51 -> 153
            PL.HEVCMainTierLevel52, PL.HEVCHighTierLevel52 -> 156
            PL.HEVCMainTierLevel6, PL.HEVCHighTierLevel6 -> 180
            PL.HEVCMainTierLevel61, PL.HEVCHighTierLevel61 -> 183
            PL.HEVCMainTierLevel62, PL.HEVCHighTierLevel62 -> 186
            else -> 0
        }

        /** Android's AV1 level constants → seq_level_idx (2.0 → 0 … 6.3 → 19). */
        private fun av1Level(l: Int): Int = when (l) {
            PL.AV1Level2 -> 0
            PL.AV1Level21 -> 1
            PL.AV1Level22 -> 2
            PL.AV1Level23 -> 3
            PL.AV1Level3 -> 4
            PL.AV1Level31 -> 5
            PL.AV1Level32 -> 6
            PL.AV1Level33 -> 7
            PL.AV1Level4 -> 8
            PL.AV1Level41 -> 9
            PL.AV1Level42 -> 10
            PL.AV1Level43 -> 11
            PL.AV1Level5 -> 12
            PL.AV1Level51 -> 13
            PL.AV1Level52 -> 14
            PL.AV1Level53 -> 15
            PL.AV1Level6 -> 16
            PL.AV1Level61 -> 17
            PL.AV1Level62 -> 18
            PL.AV1Level63 -> 19
            PL.AV1Level7 -> 20
            PL.AV1Level71 -> 21
            PL.AV1Level72 -> 22
            PL.AV1Level73 -> 23
            else -> 0
        }
    }
}
