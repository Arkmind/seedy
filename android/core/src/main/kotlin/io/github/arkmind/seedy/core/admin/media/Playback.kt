package io.github.arkmind.seedy.core.admin.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.admin.AdminHttp
import io.github.arkmind.seedy.core.admin.SystemInfo
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.session.UserSession
import io.github.arkmind.seedy.core.settings.Choice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.model.api.PlayMethod
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
 * Server › Playback (board "Android · Server · Playback", web/src/screens/server/Playback.tsx): hardware acceleration,
 * the formats the GPU decodes, encoding, HDR tone mapping and resources, plus Resume, Streaming and Seek previews on
 * pages of their own. Changes are staged and saved together from the bar at the foot ("2 unsaved changes · applies to
 * new streams"); saving writes only the changed keys over the server's current JSON.
 */

/* ── Hardware acceleration ── */

private class Accel(val id: String, val name: String, val sub: String, val short: String)

private val Accels = listOf(
    Accel("none", "None", "CPU only", ""),
    Accel("qsv", "Intel Quick Sync", "Most Intel graphics", "Quick Sync"),
    Accel("nvenc", "NVIDIA NVENC", "GeForce, Quadro, RTX", "NVENC"),
    Accel("amf", "AMD AMF", "Radeon, Windows and Linux", "AMF"),
    Accel("vaapi", "VA-API", "Intel and AMD on Linux", "VA-API"),
    Accel("videotoolbox", "Apple VideoToolbox", "Macs with Apple chips", "VideoToolbox"),
    Accel("rkmpp", "Rockchip RKMPP", "Rockchip boards", "RKMPP"),
)
private val V4l2 = Accel("v4l2m2m", "Video4Linux2", "Raspberry Pi and other ARM boards", "V4L2")

/** What the server can see of its GPUs (only Linux servers answer). */
data class GpuScan(val renderNodes: List<String> = emptyList(), val intel: Boolean = false, val amd: Boolean = false, val nvidia: Boolean = false, val rockchip: Boolean = false, val mac: Boolean = false)

/** Render nodes in /dev/dri, NVIDIA and Rockchip device nodes in /dev, and the kernel drivers bound on the PCI bus. */
suspend fun scanGpus(http: AdminHttp, programDataPath: String?): GpuScan {
    suspend fun list(p: String, files: Boolean = true) = runCatching {
        http.get<List<FsEntry>>("/Environment/DirectoryContents", mapOf("path" to p, "includeDirectories" to true, "includeFiles" to files))
    }.getOrNull().orEmpty()
    val mac = programDataPath?.contains("/Library/Application Support/") == true
    val dev = if (programDataPath?.startsWith("/") == true && !mac) list("/dev") else emptyList()
    val names = dev.map { it.name }.toSet()
    val dri = if ("dri" in names) list("/dev/dri") else emptyList()
    val nodes = dri.filter { Regex("^renderD\\d+$").matches(it.name) }.map { it.path }
    val drivers = if (nodes.isNotEmpty()) list("/sys/bus/pci/drivers", false).map { it.name }.toSet() else emptySet()
    return GpuScan(
        renderNodes = nodes,
        intel = nodes.isNotEmpty() && ("i915" in drivers || "xe" in drivers),
        amd = nodes.isNotEmpty() && ("amdgpu" in drivers || "radeon" in drivers),
        nvidia = "nvidia0" in names || "nvidiactl" in names,
        rockchip = "mpp_service" in names || "rga" in names,
        mac = mac,
    )
}

/** The one option each detected GPU is best served by. */
fun detectedAccels(scan: GpuScan?): Set<String> {
    if (scan == null) return emptySet()
    val s = mutableSetOf<String>()
    if (scan.intel) s += "qsv" else if (scan.amd || (scan.renderNodes.isNotEmpty() && !scan.nvidia && !scan.rockchip)) s += "vaapi"
    if (scan.nvidia) s += "nvenc"
    if (scan.rockchip) s += "rkmpp"
    if (scan.mac) s += "videotoolbox"
    return s
}

/**
 * FFmpeg's version: every FFmpeg log starts with its banner ("ffmpeg version 7.1.1-Jellyfin"), and the server log
 * says "Found ffmpeg version 7.1.1" at start-up. Reads the newest of those (server logs only when they're small).
 */
suspend fun ffmpegVersion(http: AdminHttp): String? {
    val logs = http.get<List<LogFile>>("/System/Logs").sortedByDescending { it.dateModified }
    for (f in logs.filter { it.name.startsWith("FFmpeg.", ignoreCase = true) }.take(2)) {
        val text = runCatching { http.raw(HttpMethod.GET, "/System/Logs/Log", mapOf("name" to f.name)).let { b -> b.decodeToString(0, minOf(b.size, 64_000)) } }.getOrNull() ?: continue
        Regex("ffmpeg version n?(\\d+(?:\\.\\d+)*)", RegexOption.IGNORE_CASE).find(text)?.let { return it.groupValues[1] }
    }
    for (f in logs.filter { it.name.startsWith("log_", ignoreCase = true) && it.size < 4_000_000 }.take(3)) {
        val text = runCatching { http.raw(HttpMethod.GET, "/System/Logs/Log", mapOf("name" to f.name)).decodeToString() }.getOrNull() ?: continue
        Regex("Found ffmpeg version (\\d+(?:\\.\\d+)*)", RegexOption.IGNORE_CASE).find(text)?.let { return it.groupValues[1] }
    }
    return null
}

/* ── Decoding ── */

private val All = listOf("amf", "nvenc", "qsv", "vaapi", "rkmpp", "videotoolbox", "v4l2m2m")
private val TenBit = listOf("amf", "nvenc", "qsv", "vaapi", "rkmpp")
private val Rext = listOf("nvenc", "qsv", "vaapi")

/** A format the GPU can decode: "codec:h264" (in HardwareDecodingCodecs) or "flag:EnableDecodingColorDepth10Hevc". */
private class Codec(val name: String, val key: String, val types: List<String>)

private val Codecs = listOf(
    Codec("H.264", "codec:h264", All),
    Codec("HEVC", "codec:hevc", listOf("amf", "nvenc", "qsv", "vaapi", "rkmpp", "videotoolbox")),
    Codec("MPEG-1", "codec:mpeg1video", listOf("rkmpp")),
    Codec("MPEG-2", "codec:mpeg2video", listOf("amf", "nvenc", "qsv", "vaapi", "rkmpp")),
    Codec("MPEG-4", "codec:mpeg4", listOf("nvenc", "rkmpp")),
    Codec("VC-1", "codec:vc1", listOf("amf", "nvenc", "qsv", "vaapi")),
    Codec("VP8", "codec:vp8", listOf("nvenc", "qsv", "vaapi", "rkmpp", "videotoolbox")),
    Codec("VP9", "codec:vp9", listOf("amf", "nvenc", "qsv", "vaapi", "rkmpp", "videotoolbox")),
    Codec("AV1", "codec:av1", listOf("amf", "nvenc", "qsv", "vaapi", "rkmpp", "videotoolbox")),
    Codec("HEVC 10-bit", "flag:EnableDecodingColorDepth10Hevc", TenBit),
    Codec("VP9 10-bit", "flag:EnableDecodingColorDepth10Vp9", TenBit),
    Codec("HEVC RExt 8/10-bit", "flag:EnableDecodingColorDepth10HevcRext", Rext),
    Codec("HEVC RExt 12-bit", "flag:EnableDecodingColorDepth12HevcRext", Rext),
)

private val Presets = listOf(
    "auto" to "Auto", "veryslow" to "Very slow", "slower" to "Slower", "slow" to "Slow", "medium" to "Medium", "fast" to "Fast",
    "faster" to "Faster", "veryfast" to "Very fast", "superfast" to "Super fast", "ultrafast" to "Ultra fast",
)

/** Only linear, clip and none are words; the rest are names. */
private val Algorithms = listOf(
    "bt2390" to "BT.2390", "hable" to "Hable", "mobius" to "Möbius", "reinhard" to "Reinhard", "gamma" to "Gamma",
    "linear" to "Linear", "clip" to "Clip", "none" to "None",
)
private val WordAlgorithms = setOf("linear", "clip", "none")

private val Downmix = listOf("None" to "Standard", "Dave750" to "Dave750", "NightmodeDialogue" to "Night mode dialogue", "Rfc7845" to "RFC 7845", "Ac4" to "AC-4")
private val WordDownmix = setOf("None", "NightmodeDialogue")
private val Deinterlace = listOf("yadif" to "YADIF", "bwdif" to "BWDIF")
private val Priorities = listOf("Idle" to "Lowest", "BelowNormal" to "Below normal", "Normal" to "Normal", "AboveNormal" to "Above normal", "High" to "High")

data class AccelRow(val id: String, val name: String, val sub: String, val on: Boolean, val detected: Boolean)

data class CodecChip(val key: String, val name: String, val on: Boolean)

/** The Playback pages besides Transcoding. */
object PlaybackPages {
    const val Resume = "resume"
    const val Streaming = "streaming"
    const val Previews = "previews"
}

data class PlaybackState(
    val loaded: Boolean = false,
    val loadError: String? = null,
    /** "FFmpeg 8.1 · 1 transcode running"; null until known. */
    val ffmpeg: String? = null,
    val ffmpegFound: Boolean = true,
    val accels: List<AccelRow> = emptyList(),
    /** Empty: no GPU to decode on. */
    val codecs: List<CodecChip> = emptyList(),
    /** Device, Encoding, HDR to SDR, Resources, and the rows that open the other pages. */
    val groups: List<SettingsGroup> = emptyList(),
    val resume: List<SettingsGroup> = emptyList(),
    val streaming: List<SettingsGroup> = emptyList(),
    val previews: List<SettingsGroup> = emptyList(),
    val changes: Int = 0,
    /** "2 unsaved changes · applies to new streams". */
    val unsaved: String = "",
    val saving: Boolean = false,
    val picker: FolderPicker? = null,
    val message: String? = null,
)

interface PlaybackActions : SettingActions, FolderPickerActions {
    fun retry() {}
    fun setAccel(id: String) {}
    fun setCodec(key: String, on: Boolean) {}
    fun discard() {}
    fun save() {}
    fun messageShown() {}
}

class PlaybackViewModel(session: UserSession) : ViewModel(), PlaybackActions {
    private val api = session.api
    private val http = AdminHttp(session.api)
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private val browser = FolderBrowser(http, viewModelScope)

    /** As the server sent them, and the changes staged over them ("TrickplayOptions.X" for seek previews). */
    private var enc = JsonObject(emptyMap())
    private var srv = JsonObject(emptyMap())
    private val encDraft = linkedMapOf<String, JsonElement>()
    private val srvDraft = linkedMapOf<String, JsonElement>()
    private var version: String? = null
    private var versionKnown = false
    private var running: Int? = null
    private var scan: GpuScan? = null

    init {
        viewModelScope.launch { browser.state.collect { p -> _state.update { it.copy(picker = p) } } }
    }

    fun load() {
        viewModelScope.launch {
            val e = async { runCatching { http.getObject("/System/Configuration/encoding") } }
            val s = async { runCatching { http.getObject("/System/Configuration") } }
            val er = e.await()
            val sr = s.await()
            if (er.isFailure || sr.isFailure) {
                _state.update { it.copy(loadError = t("Couldn’t load the playback settings")) }
                return@launch
            }
            enc = er.getOrThrow()
            srv = sr.getOrThrow()
            _state.update { it.copy(loaded = true, loadError = null) }
            publish()
        }
        viewModelScope.launch {
            version = runCatching { ffmpegVersion(http) }.getOrNull()
            versionKnown = true
            publish()
        }
        viewModelScope.launch {
            val info = runCatching { http.get<SystemInfo>("/System/Info") }.getOrNull()
            scan = runCatching { scanGpus(http, info?.programDataPath) }.getOrNull()
            publish()
        }
    }

    /** Counts transcodes every 15 s until cancelled. */
    suspend fun live() {
        while (true) {
            running = runCatching {
                api.sessionApi.getSessions(activeWithinSeconds = 960).content.count { it.nowPlayingItem != null && it.playState?.playMethod == PlayMethod.TRANSCODE }
            }.getOrNull() ?: running
            publish()
            delay(15_000)
        }
    }

    override fun retry() = load()

    /* ── Reading the staged values ── */

    private fun e(key: String): JsonElement? = encDraft[key] ?: enc[key]
    private fun eBool(key: String) = (e(key) as? JsonPrimitive)?.contentOrNull == "true"
    private fun eNum(key: String) = (e(key) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0
    private fun eStr(key: String) = (e(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun eList(key: String) = (e(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    private fun savedSrv(key: String): JsonElement? =
        if (key.startsWith("TrickplayOptions.")) srv.obj("TrickplayOptions")[key.removePrefix("TrickplayOptions.")] else srv[key]

    private fun s(key: String): JsonElement? = srvDraft[key] ?: savedSrv(key)
    private fun sBool(key: String) = (s(key) as? JsonPrimitive)?.contentOrNull == "true"
    private fun sNum(key: String) = (s(key) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0
    private fun sStr(key: String) = (s(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun changedHelp(changed: Boolean, was: String, help: String?): String? = if (changed) t("Changed · was {value}", "value" to was) else help
    private fun switchHelp(changed: Boolean, wasOn: Boolean, help: String?): String? = if (!changed) help else if (wasOn) t("Changed · was on") else t("Changed · was off")

    private fun encSwitch(key: String, name: String, help: String?) =
        SwitchSetting("enc:$key", name, switchHelp(key in encDraft, enc.bool(key), help), eBool(key))

    private fun srvSwitch(key: String, name: String, help: String?) =
        SwitchSetting("srv:$key", name, switchHelp(key in srvDraft, (savedSrv(key) as? JsonPrimitive)?.contentOrNull == "true", help), sBool(key))

    private fun encChoice(key: String, name: String, help: String?, options: List<Choice<String>>): ChoiceSetting {
        val v = eStr(key)
        val all = withCurrent(options, v)
        val was = (enc[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return ChoiceSetting("enc:$key", name, changedHelp(key in encDraft, labelOf(all, was), help), labelOf(all, v), v, all)
    }

    private fun srvChoice(key: String, name: String, help: String?, options: List<Choice<String>>): ChoiceSetting {
        val v = sStr(key)
        val all = withCurrent(options, v)
        val was = (savedSrv(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
        return ChoiceSetting("srv:$key", name, changedHelp(key in srvDraft, labelOf(all, was), help), labelOf(all, v), v, all)
    }

    /** A number row; [show] writes a value the way the row shows it. */
    private fun number(prefix: String, key: String, name: String, help: String?, min: Double, max: Double, unit: String? = null, scale: Double = 1.0, decimals: Boolean = false, show: ((Double) -> String)? = null): NumberSetting {
        val draft = if (prefix == "enc") encDraft else srvDraft
        val now = (if (prefix == "enc") eNum(key) else sNum(key)) / scale
        val saved = ((if (prefix == "enc") enc[key] else savedSrv(key)) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.div(scale) ?: 0.0
        val fmt = show ?: { v -> withUnit(v, unit) }
        val shown = if (decimals) (now * 10).roundToLong() / 10.0 else now
        return NumberSetting("$prefix:$key", name, changedHelp(key in draft, fmt(saved), help), fmt(shown), shown, min, max, unit, decimals)
    }

    private fun publish() {
        if (!_state.value.loaded) return
        val hw = eStr("HardwareAccelerationType").ifEmpty { "none" }
        val found = detectedAccels(scan)
        val accels = if (hw == "v4l2m2m") Accels + V4l2 else Accels
        val intel = hw == "qsv" || hw == "vaapi"
        val path = eStr("EncoderAppPathDisplay").ifEmpty { eStr("EncoderAppPath") }
        val short = accels.firstOrNull { it.id == hw }?.short.orEmpty()
        val decoding = eList("HardwareDecodingCodecs")
        val codecs = if (hw == "none") emptyList() else Codecs.filter { hw in it.types }.map { c ->
            CodecChip(c.key, c.name, if (c.key.startsWith("codec:")) c.key.removePrefix("codec:") in decoding else eBool(c.key.removePrefix("flag:")))
        }

        val ffmpeg = when {
            path.isEmpty() -> t("FFmpeg wasn’t found. Nothing can be converted until the server finds it.")
            else -> listOfNotNull(
                if (version != null) "FFmpeg $version" else "FFmpeg",
                running?.let { if (it > 0) tn(it, "{n} transcode running", "{n} transcodes running") else t("No transcodes running") },
            ).joinToString(" · ")
        }

        val groups = mutableListOf<SettingsGroup>()
        if (intel) {
            val deviceKey = if (hw == "qsv") "QsvDevice" else "VaapiDevice"
            val value = eStr(deviceKey)
            val nodes = scan?.renderNodes?.takeIf { it.isNotEmpty() } ?: listOf("/dev/dri/renderD128", "/dev/dri/renderD129")
            val options = (if (hw == "qsv") listOf(Choice("", t("Automatic"))) else emptyList()) +
                (listOfNotNull(value.takeIf { it.isNotEmpty() }) + nodes).distinct().map { Choice(it, it) }
            val was = (enc[deviceKey] as? JsonPrimitive)?.contentOrNull.orEmpty()
            groups += SettingsGroup(
                t("Device"),
                listOf(ChoiceSetting("enc:$deviceKey", t("GPU device"), changedHelp(deviceKey in encDraft, labelOf(options, was).ifEmpty { t("Automatic") }, t("The GPU {name} runs on", "name" to short)), labelOf(options, value).ifEmpty { t("Automatic") }, value, options)),
            )
        }
        val lowPowerChanged = "EnableIntelLowPowerH264HwEncoder" in encDraft || "EnableIntelLowPowerHevcHwEncoder" in encDraft
        groups += SettingsGroup(
            t("Encoding"),
            listOfNotNull(
                if (hw != "none") encSwitch("EnableHardwareEncoding", t("Encode on the GPU"), t("Turn off to decode on the GPU but encode on the CPU")) else null,
                encSwitch("AllowHevcEncoding", t("Allow HEVC output"), t("Smaller streams for devices that can play HEVC")),
                encSwitch("AllowAv1Encoding", t("Allow AV1 output"), t("Smallest streams; needs a recent GPU and device")),
                if (intel) SwitchSetting(
                    "lowpower", t("Intel low-power encoders"),
                    switchHelp(lowPowerChanged, enc.bool("EnableIntelLowPowerH264HwEncoder") && enc.bool("EnableIntelLowPowerHevcHwEncoder"), t("Faster on recent Intel GPUs")),
                    eBool("EnableIntelLowPowerH264HwEncoder") && eBool("EnableIntelLowPowerHevcHwEncoder"),
                ) else null,
                encChoice("EncoderPreset", t("Encoder preset"), t("Trades speed for quality"), Presets.map { (v, l) -> Choice(v, t(l)) }),
                number("enc", "H264Crf", t("H.264 quality (CRF)"), t("Lower is better quality and bigger files"), 0.0, 51.0),
                number("enc", "H265Crf", t("HEVC quality (CRF)"), t("Lower is better quality and bigger files"), 0.0, 51.0),
            ),
        )
        groups += SettingsGroup(
            t("HDR to SDR"),
            listOfNotNull(
                encSwitch("EnableTonemapping", t("Tone mapping"), t("Keeps HDR from looking washed out on SDR screens")),
                encChoice("TonemappingAlgorithm", t("Algorithm"), t("How bright highlights are compressed"), Algorithms.map { (v, l) -> Choice(v, if (v in WordAlgorithms) t(l) else l) }),
                if (intel) encSwitch("EnableVppTonemapping", t("Intel VPP tone mapping"), t("Uses Intel’s built-in tone mapping instead")) else null,
                if (hw == "videotoolbox") encSwitch("EnableVideoToolboxTonemapping", t("VideoToolbox tone mapping"), t("Uses Apple’s built-in tone mapping instead")) else null,
            ),
        )
        val threads = listOf(Choice("-1", t("Auto")), Choice("0", t("As many as possible"))) + (1..16).map { Choice(it.toString(), tn(it, "{n} thread", "{n} threads")) }
        val threadValue = eNum("EncodingThreadCount").roundToInt().toString()
        groups += SettingsGroup(
            t("Resources"),
            listOf(
                encChoice("EncodingThreadCount", t("Encoding threads"), t("Leave on Auto unless the server runs other heavy work"), withCurrent(threads, threadValue, threadValue.toIntOrNull()?.let { tn(it, "{n} thread", "{n} threads") } ?: threadValue)),
                encSwitch("EnableThrottling", t("Throttle transcodes"), t("Pauses converting when far enough ahead of the viewer")),
                number("enc", "ThrottleDelaySeconds", t("Start throttling after"), t("Seconds converted ahead of playback"), 60.0, 3600.0, "s"),
                encSwitch("EnableSegmentDeletion", t("Delete old segments"), t("Frees disk space during long streams")),
                number("enc", "SegmentKeepSeconds", t("Keep segments for"), t("Seconds of already-watched video kept"), 120.0, 3600.0, "s"),
            ),
        )
        groups += SettingsGroup(
            t("More playback settings"),
            listOf(
                LinkSetting("page:${PlaybackPages.Resume}", t("Resume"), t("When progress is saved and what counts as watched"), ""),
                LinkSetting("page:${PlaybackPages.Streaming}", t("Streaming"), t("Remote limits, audio, deinterlacing and subtitles"), ""),
                LinkSetting("page:${PlaybackPages.Previews}", t("Seek previews"), t("How the previews over the progress bar are made"), ""),
            ),
        )

        val changes = encDraft.size + srvDraft.size
        _state.update {
            it.copy(
                ffmpeg = ffmpeg,
                ffmpegFound = path.isNotEmpty(),
                accels = accels.map { a -> AccelRow(a.id, if (a.id == "none") t(a.name) else a.name, if (a.id in found) t("Detected on this server") else t(a.sub), hw == a.id, a.id in found) },
                codecs = codecs,
                groups = groups,
                resume = resume(),
                streaming = streaming(),
                previews = previews(),
                changes = changes,
                unsaved = if (changes > 0) tn(changes, "{n} unsaved change", "{n} unsaved changes") + " · " + t("applies to new streams") else "",
            )
        }
    }

    private fun resume(): List<SettingsGroup> {
        val minPct = sNum("MinResumePct")
        val maxPct = sNum("MaxResumePct")
        return listOf(
            SettingsGroup(
                t("Video"),
                listOf(
                    number("srv", "MinResumePct", t("Counts as started after"), t("Stopping earlier keeps no resume point"), 0.0, maxOf(0.0, maxPct - 1), "%", show = { v -> "${numberText(v)}%" }),
                    number("srv", "MaxResumePct", t("Counts as watched after"), t("Stopping later marks the video as watched"), minOf(100.0, minPct + 1), 100.0, "%", show = { v -> "${numberText(v)}%" }),
                    number("srv", "MinResumeDurationSeconds", t("Shortest video with a resume point"), t("Shorter videos always start from the beginning"), 0.0, 86400.0, "s"),
                ),
            ),
            SettingsGroup(
                t("Audiobooks"),
                listOf(
                    number("srv", "MinAudiobookResume", t("Counts as started after"), t("Stopping in the first minutes starts the book over"), 0.0, 600.0, "min"),
                    number("srv", "MaxAudiobookResume", t("Counts as finished"), t("Minutes before the end that count as finishing it"), 0.0, 600.0, "min"),
                ),
            ),
        )
    }

    private fun streaming(): List<SettingsGroup> {
        val downmix = Downmix.map { (v, l) -> Choice(v, if (v in WordDownmix) t(l) else l) }
        val fallback = eBool("EnableFallbackFont")
        val fontPath = eStr("FallbackFontPath")
        val tempPath = eStr("TranscodingTempPath")
        return listOf(
            SettingsGroup(
                t("Remote streaming"),
                listOf(
                    number("srv", "RemoteClientBitrateLimit", t("Bitrate limit outside the home network"), t("0 means no limit. People can also have their own limit in Users"), 0.0, 1000.0, "Mbps", scale = 1_000_000.0, decimals = true, show = { v -> if (v <= 0) t("No limit") else withUnit((v * 10).roundToLong() / 10.0, "Mbps") }),
                ),
            ),
            SettingsGroup(
                t("Audio"),
                listOf(
                    number("enc", "DownMixAudioBoost", t("Downmix boost"), t("Makes stereo made from surround sound louder"), 0.5, 3.0, "×", decimals = true, show = { v -> "${numberText(v)}×" }),
                    encChoice("DownMixStereoAlgorithm", t("Stereo downmix"), t("How surround channels are mixed down to two"), downmix),
                    encSwitch("EnableAudioVbr", t("Variable bitrate audio"), t("Better sound for the same size; a few old devices can’t play it")),
                ),
            ),
            SettingsGroup(
                t("Video"),
                listOf(
                    encChoice("DeinterlaceMethod", t("Deinterlacing"), t("For TV recordings and old DVDs"), Deinterlace.map { (v, l) -> Choice(v, l) }),
                    encSwitch("DeinterlaceDoubleRate", t("Double the frame rate"), t("Smoother motion when deinterlacing, at twice the work")),
                    number("enc", "MaxMuxingQueueSize", t("Muxing queue"), t("Raise it only if conversions fail with “Too many packets buffered”"), 128.0, 65536.0, t("packets")),
                ),
            ),
            SettingsGroup(
                t("Subtitles"),
                listOfNotNull(
                    encSwitch("EnableFallbackFont", t("Fallback fonts"), t("Fonts used for burned-in subtitles when a video doesn’t bring its own")),
                    if (fallback) LinkSetting("folder:FallbackFontPath", t("Fallback fonts folder"), t("A folder of .ttf, .otf or .woff fonts on the server"), fontPath.ifEmpty { t("Choose a folder") }) else null,
                    encSwitch("EnableSubtitleExtraction", t("Extract subtitles on the fly"), t("For subtitles burned in or converted while streaming")),
                ),
            ),
            SettingsGroup(
                t("Files"),
                listOf(LinkSetting("folder:TranscodingTempPath", t("Transcode folder"), t("Where streams are written while they play"), tempPath.ifEmpty { t("Server cache folder") })),
            ),
        )
    }

    private fun previews(): List<SettingsGroup> {
        val behaviour = listOf(Choice("NonBlocking", t("Make them in the background")), Choice("Blocking", t("Wait for them")))
        val priority = Priorities.map { (v, l) -> Choice(v, t(l)) }
        val widths = (s("TrickplayOptions.WidthResolutions") as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        return listOf(
            SettingsGroup(
                t("Making previews"),
                listOf(
                    srvSwitch("TrickplayOptions.EnableHwAcceleration", t("Decode on the GPU"), t("Uses the hardware acceleration set up under Transcoding")),
                    srvSwitch("TrickplayOptions.EnableHwEncoding", t("Encode on the GPU"), t("Faster still, but not every GPU can do it")),
                    srvSwitch("TrickplayOptions.EnableKeyFrameOnlyExtraction", t("Key frames only"), t("Much faster, but previews may be a few seconds off")),
                    srvChoice("TrickplayOptions.ScanBehavior", t("During library scans"), t("Scans can wait for new previews or carry on without them"), behaviour),
                    srvChoice("TrickplayOptions.ProcessPriority", t("Priority"), t("Lower keeps playback smooth while previews are made"), priority),
                    number("srv", "TrickplayOptions.ProcessThreads", t("Threads"), t("0 lets FFmpeg decide"), 0.0, 64.0),
                ),
            ),
            SettingsGroup(
                t("Images"),
                listOf(
                    number("srv", "TrickplayOptions.Interval", t("One preview every"), t("Shorter gaps look smoother and take more space"), 1.0, 600.0, "s", scale = 1000.0, decimals = true),
                    TextSetting("srv:TrickplayOptions.WidthResolutions", t("Widths"), if ("TrickplayOptions.WidthResolutions" in srvDraft) t("Changed · was {value}", "value" to (savedSrv("TrickplayOptions.WidthResolutions") as? JsonArray)?.joinToString(", ") { it.jsonPrimitive.content }.orEmpty()) else t("One set of previews per width, in pixels"), widths.joinToString(", "), "320"),
                    number("srv", "TrickplayOptions.TileWidth", t("Previews across"), t("Previews are stored in grids of this size"), 1.0, 100.0),
                    number("srv", "TrickplayOptions.TileHeight", t("Previews down"), null, 1.0, 100.0),
                    number("srv", "TrickplayOptions.JpegQuality", t("JPEG quality"), t("Higher looks better and makes bigger files"), 1.0, 100.0, "%", show = { v -> "${numberText(v)}%" }),
                    number("srv", "TrickplayOptions.Qscale", t("Compression"), t("From 2 to 31. Lower is better quality and bigger files"), 2.0, 31.0),
                ),
            ),
        )
    }

    /* ── Staging ── */

    private fun stageEnc(key: String, value: JsonElement) {
        if (enc[key] == value) encDraft.remove(key) else encDraft[key] = value
    }

    private fun stageSrv(key: String, value: JsonElement) {
        if (savedSrv(key) == value) srvDraft.remove(key) else srvDraft[key] = value
    }

    private fun stage(key: String, value: JsonElement) {
        val field = key.substringAfter(':')
        if (key.startsWith("enc:")) stageEnc(field, value) else stageSrv(field, value)
        publish()
    }

    override fun setAccel(id: String) {
        stageEnc("HardwareAccelerationType", json(id))
        publish()
    }

    override fun setCodec(key: String, on: Boolean) {
        if (key.startsWith("codec:")) {
            val codec = key.removePrefix("codec:")
            val rest = eList("HardwareDecodingCodecs") - codec
            // The server's order where the list goes back to it.
            val next = if (on) rest + codec else rest
            val saved = enc.strings("HardwareDecodingCodecs")
            stageEnc("HardwareDecodingCodecs", json(if (next.toSet() == saved.toSet()) saved else next))
        } else stageEnc(key.removePrefix("flag:"), json(on))
        publish()
    }

    override fun setSwitch(key: String, on: Boolean) {
        if (key == "lowpower") {
            stageEnc("EnableIntelLowPowerH264HwEncoder", json(on))
            stageEnc("EnableIntelLowPowerHevcHwEncoder", json(on))
            publish()
        } else stage(key, json(on))
    }

    override fun setChoice(key: String, value: String) {
        val field = key.substringAfter(':')
        val v: JsonElement = when (field) {
            "EncodingThreadCount" -> json(value.toIntOrNull() ?: -1)
            else -> json(value)
        }
        stage(key, v)
    }

    override fun setNumber(key: String, value: Double) {
        val v: JsonElement = when (key.substringAfter(':')) {
            "RemoteClientBitrateLimit" -> json((value * 1_000_000).roundToLong())
            "TrickplayOptions.Interval" -> json((value * 1000).roundToLong())
            "DownMixAudioBoost" -> json(value)
            else -> json(value.roundToLong())
        }
        stage(key, v)
    }

    override fun setText(key: String, value: String) {
        if (key == "srv:TrickplayOptions.WidthResolutions") {
            val widths = value.split(',', ' ').mapNotNull { it.trim().toIntOrNull() }.filter { it in 64..3840 }.distinct().sorted()
            if (widths.isNotEmpty()) stage(key, JsonArray(widths.map { JsonPrimitive(it) }))
        }
    }

    override fun open(key: String) {
        if (!key.startsWith("folder:")) return
        val field = key.removePrefix("folder:")
        val title = if (field == "FallbackFontPath") t("Fallback fonts folder") else t("Transcode folder")
        browser.open(title, t("Use this folder"), eStr(field)) { path -> stageEnc(field, json(path)); publish() }
    }

    override fun browse(path: String) = browser.go(path)
    override fun pickFolder(path: String) = browser.pick(path)
    override fun closePicker() = browser.close()

    override fun discard() {
        encDraft.clear()
        srvDraft.clear()
        publish()
    }

    private fun say(text: String) = _state.update { it.copy(message = text) }
    override fun messageShown() = _state.update { it.copy(message = null) }

    override fun save() {
        if (_state.value.saving || (encDraft.isEmpty() && srvDraft.isEmpty())) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = runCatching {
                if (encDraft.isNotEmpty()) {
                    // Jellyfin replaces the whole encoding configuration: the changes go over what the server has now.
                    enc = http.patchObject("/System/Configuration/encoding", encDraft.toMap())
                    encDraft.clear()
                }
                if (srvDraft.isNotEmpty()) {
                    val fresh = http.getObject("/System/Configuration")
                    val top = srvDraft.filterKeys { !it.startsWith("TrickplayOptions.") }
                    val trick = srvDraft.filterKeys { it.startsWith("TrickplayOptions.") }.mapKeys { it.key.removePrefix("TrickplayOptions.") }
                    var next = JsonObject(fresh + top)
                    // Seek previews are one nested object: merged field by field too.
                    if (trick.isNotEmpty()) next = next.with("TrickplayOptions", JsonObject(fresh.obj("TrickplayOptions") + trick))
                    http.send("/System/Configuration", next)
                    srv = next
                    srvDraft.clear()
                }
            }
            _state.update { it.copy(saving = false) }
            publish()
            result.onSuccess { say(t("Playback settings saved")) }
                .onFailure { if (it is CancellationException) throw it; say(failText(it, t("Couldn’t save the playback settings."))) }
        }
    }
}
