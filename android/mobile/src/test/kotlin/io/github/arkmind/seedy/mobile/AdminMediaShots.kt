package io.github.arkmind.seedy.mobile

import androidx.compose.ui.test.junit4.createComposeRule
import io.github.arkmind.seedy.core.admin.AdminUser
import io.github.arkmind.seedy.core.admin.SystemInfo
import io.github.arkmind.seedy.core.admin.TaskInfo
import io.github.arkmind.seedy.core.admin.TaskResult
import io.github.arkmind.seedy.core.admin.VirtualFolder
import io.github.arkmind.seedy.core.admin.media.AvailableOptions
import io.github.arkmind.seedy.core.admin.media.Country
import io.github.arkmind.seedy.core.admin.media.Culture
import io.github.arkmind.seedy.core.admin.media.GpuScan
import io.github.arkmind.seedy.core.admin.media.LibrariesActions
import io.github.arkmind.seedy.core.admin.media.LibrariesState
import io.github.arkmind.seedy.core.admin.media.LibraryActions
import io.github.arkmind.seedy.core.admin.media.LibraryDefaultsActions
import io.github.arkmind.seedy.core.admin.media.LibraryDefaultsState
import io.github.arkmind.seedy.core.admin.media.LibraryDefaultsViewModel
import io.github.arkmind.seedy.core.admin.media.LibraryPages
import io.github.arkmind.seedy.core.admin.media.LibraryState
import io.github.arkmind.seedy.core.admin.media.LibraryTypeOptionsInfo
import io.github.arkmind.seedy.core.admin.media.LocalizationOption
import io.github.arkmind.seedy.core.admin.media.OptionInfo
import io.github.arkmind.seedy.core.admin.media.PlaybackActions
import io.github.arkmind.seedy.core.admin.media.PlaybackDraft
import io.github.arkmind.seedy.core.admin.media.PlaybackPages
import io.github.arkmind.seedy.core.admin.media.PlaybackState
import io.github.arkmind.seedy.core.admin.media.ServerSettingsActions
import io.github.arkmind.seedy.core.admin.media.ServerSettingsState
import io.github.arkmind.seedy.core.admin.media.libraryDefaultsGroups
import io.github.arkmind.seedy.core.admin.media.libraryState
import io.github.arkmind.seedy.core.admin.media.librariesView
import io.github.arkmind.seedy.core.admin.media.serverSettingsState
import io.github.arkmind.seedy.mobile.admin.media.LibrariesScreen
import io.github.arkmind.seedy.mobile.admin.media.LibraryDefaultsScreen
import io.github.arkmind.seedy.mobile.admin.media.LibraryScreen
import io.github.arkmind.seedy.mobile.admin.media.PlaybackScreen
import io.github.arkmind.seedy.mobile.admin.media.ServerSettingsScreen
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Boards "Android · Server · Libraries", "Playback" and "Settings", and the pages they open. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AdminMediaShots {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w390dp-h850dp-mdpi")
    fun libraries() = compose.adminShot("AndroidAdminLibraries") {
        LibrariesScreen(MediaSamples.libraries, object : LibrariesActions {}, onBack = {}, open = {})
    }

    @Test
    @Config(qualifiers = "w390dp-h2400dp-mdpi")
    fun library() = compose.adminShot("AndroidAdminLibrary") {
        LibraryScreen(MediaSamples.library, object : LibraryActions {}, onBack = {}, open = {})
    }

    @Test
    @Config(qualifiers = "w390dp-h1000dp-mdpi")
    fun libraryDisplay() = compose.adminShot("AndroidAdminLibraryDisplay") {
        LibraryDefaultsScreen(MediaSamples.defaults(LibraryPages.Display), object : LibraryDefaultsActions {}, onBack = {})
    }

    @Test
    @Config(qualifiers = "w390dp-h1100dp-mdpi")
    fun libraryMetadata() = compose.adminShot("AndroidAdminLibraryMetadata") {
        LibraryDefaultsScreen(MediaSamples.defaults(LibraryPages.Metadata), object : LibraryDefaultsActions {}, onBack = {})
    }

    @Test
    @Config(qualifiers = "w390dp-h900dp-mdpi")
    fun libraryNfo() = compose.adminShot("AndroidAdminLibraryNfo") {
        LibraryDefaultsScreen(MediaSamples.defaults(LibraryPages.Nfo), object : LibraryDefaultsActions {}, onBack = {})
    }

    @Test
    @Config(qualifiers = "w390dp-h2400dp-mdpi")
    fun playback() = compose.adminShot("AndroidAdminPlayback") {
        PlaybackScreen(MediaSamples.playback, object : PlaybackActions {}, page = null, onBack = {}, openPage = {})
    }

    @Test
    @Config(qualifiers = "w390dp-h1000dp-mdpi")
    fun playbackResume() = compose.adminShot("AndroidAdminPlaybackResume") {
        PlaybackScreen(MediaSamples.playback, object : PlaybackActions {}, page = PlaybackPages.Resume, onBack = {}, openPage = {})
    }

    @Test
    @Config(qualifiers = "w390dp-h1800dp-mdpi")
    fun playbackStreaming() = compose.adminShot("AndroidAdminPlaybackStreaming") {
        PlaybackScreen(MediaSamples.playback, object : PlaybackActions {}, page = PlaybackPages.Streaming, onBack = {}, openPage = {})
    }

    @Test
    @Config(qualifiers = "w390dp-h1500dp-mdpi")
    fun playbackPreviews() = compose.adminShot("AndroidAdminPlaybackPreviews") {
        PlaybackScreen(MediaSamples.playback, object : PlaybackActions {}, page = PlaybackPages.Previews, onBack = {}, openPage = {})
    }

    @Test
    @Config(qualifiers = "w390dp-h1750dp-mdpi")
    fun settings() = compose.adminShot("AndroidAdminSettings") {
        ServerSettingsScreen(MediaSamples.settings, object : ServerSettingsActions {}, expert = false, onBack = {}, openExpert = {}, pickSplash = {})
    }

    @Test
    @Config(qualifiers = "w390dp-h1300dp-mdpi")
    fun expert() = compose.adminShot("AndroidAdminExpert") {
        ServerSettingsScreen(MediaSamples.settings, object : ServerSettingsActions {}, expert = true, onBack = {}, openExpert = {}, pickSplash = {})
    }
}

/** The boards' sample data, run through the pages' own presenters. */
object MediaSamples {
    /** 21 Oct 2026, 20:00 (UTC). */
    private val clock = Clock.fixed(Instant.parse("2026-10-21T20:00:00Z"), ZoneOffset.UTC)

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to el(v) })
    private fun el(v: Any?): JsonElement = when (v) {
        null -> kotlinx.serialization.json.JsonNull
        is JsonElement -> v
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map { el(it) })
        else -> error("$v")
    }

    private val scanTask = TaskInfo(
        name = "Scan Media Library", state = "Idle", id = "scan", key = "RefreshLibrary",
        lastExecutionResult = TaskResult(startTimeUtc = "2026-10-21T18:10:00Z", endTimeUtc = "2026-10-21T18:30:00Z"),
    )

    val libraries: LibrariesState by lazy {
        val folders = listOf(
            VirtualFolder("Movies", listOf("/media/movies", "/media/movies-4k"), "movies", "m"),
            VirtualFolder("Shows", listOf("/media/shows"), "tvshows", "s", refreshProgress = 42.0),
            VirtualFolder("Kids", listOf("/media/kids"), "mixed", "k"),
            VirtualFolder("Home videos", emptyList(), "homevideos", "h"),
        )
        val (lede, cards) = librariesView(folders, listOf(scanTask), mapOf("m" to 248, "s" to 61, "k" to 112, "h" to 340), clock) { null }
        LibrariesState(
            lede = lede,
            cards = cards.map {
                when (it.id) {
                    "s" -> it.copy(canStop = true)
                    "h" -> it.copy(state = "Scanned yesterday")
                    else -> it
                }
            },
        )
    }

    private val cultures = listOf(
        Culture("English", "English", "en", "eng", listOf("eng")),
        Culture("French", "French", "fr", "fre", listOf("fre", "fra")),
        Culture("German", "German", "de", "ger", listOf("ger", "deu")),
    )
    private val countries = listOf(Country("FR", "France", "FR"), Country("GB", "United Kingdom", "GB"))

    val library: LibraryState by lazy {
        val opts = obj(
            "PreferredMetadataLanguage" to "en", "MetadataCountryCode" to "FR", "AutomaticRefreshIntervalDays" to 0,
            "SubtitleDownloadLanguages" to listOf("eng", "fre"),
            "SkipSubtitlesIfEmbeddedSubtitlesPresent" to true, "SkipSubtitlesIfAudioTrackMatches" to true, "RequirePerfectSubtitleMatch" to true, "SaveSubtitlesWithMedia" to true,
            "EnableRealtimeMonitor" to true, "EnableTrickplayImageExtraction" to true, "EnableChapterImageExtraction" to false, "SaveTrickplayWithMedia" to false,
            "AutomaticallyAddToCollection" to true, "SaveLocalMetadata" to false,
            "DisabledMediaSegmentProviders" to emptyList<String>(), "MediaSegmentProviderOrder" to emptyList<String>(), "TypeOptions" to emptyList<String>(),
        )
        val avail = AvailableOptions(
            mediaSegmentProviders = listOf(OptionInfo("Chapter Segments Provider", true)),
            typeOptions = listOf(
                LibraryTypeOptionsInfo(
                    "Movie",
                    metadataFetchers = listOf(OptionInfo("TheMovieDb", true), OptionInfo("The Open Movie Database", true)),
                    imageFetchers = listOf(OptionInfo("TheMovieDb", true), OptionInfo("Embedded Image Extractor", true), OptionInfo("Screen Grabber", true)),
                    similarItemProviders = listOf(OptionInfo("Local Genre/Tag", true), OptionInfo("TheMovieDb", false)),
                ),
            ),
        )
        val folder = VirtualFolder("Movies", listOf("/media/movies", "/media/movies-4k"), "movies", "m", libraryOptions = opts)
        libraryState(LibraryState(), folder, opts, avail, false, cultures, countries, 248, scanTask)
    }

    fun defaults(page: String): LibraryDefaultsState {
        val configs = mapOf(
            "" to obj(
                "EnableFolderView" to false, "DisplaySpecialsWithinSeasons" to true, "EnableGroupingMoviesIntoCollections" to true,
                "EnableGroupingShowsIntoCollections" to false, "EnableExternalContentInSuggestions" to true,
                "PreferredMetadataLanguage" to "en", "MetadataCountryCode" to "FR", "ChapterImageResolution" to "MatchSource",
                "DummyChapterDuration" to 0, "ImageSavingConvention" to "Legacy", "SaveMetadataHidden" to false,
            ),
            "metadata" to obj("UseFileCreationTimeForDateAdded" to true),
            "xbmcmetadata" to obj("UserId" to "a1", "ReleaseDateFormat" to "yyyy-MM-dd", "SaveImagePathsInNfo" to true, "EnablePathSubstitution" to true, "EnableExtraThumbsDuplication" to false),
        )
        val users = listOf(AdminUser(id = "a1", name = "Arky"), AdminUser(id = "b2", name = "Sam"))
        return LibraryDefaultsState(LibraryDefaultsViewModel.title(page), LibraryDefaultsViewModel.lede(page), libraryDefaultsGroups(page, configs, cultures, countries, users))
    }

    val playback: PlaybackState by lazy {
        val enc = obj(
            "HardwareAccelerationType" to "qsv", "QsvDevice" to "/dev/dri/renderD128", "VaapiDevice" to "/dev/dri/renderD128",
            "EncoderAppPathDisplay" to "/usr/lib/jellyfin-ffmpeg/ffmpeg",
            "HardwareDecodingCodecs" to listOf("h264", "hevc", "mpeg2video", "vc1", "vp9"),
            "EnableDecodingColorDepth10Hevc" to true, "EnableDecodingColorDepth10Vp9" to true,
            "EnableDecodingColorDepth10HevcRext" to true, "EnableDecodingColorDepth12HevcRext" to false,
            "EnableHardwareEncoding" to true, "AllowHevcEncoding" to true, "AllowAv1Encoding" to false,
            "EnableIntelLowPowerH264HwEncoder" to true, "EnableIntelLowPowerHevcHwEncoder" to true,
            "EncoderPreset" to "auto", "H264Crf" to 23, "H265Crf" to 28,
            "EnableTonemapping" to true, "TonemappingAlgorithm" to "bt2390", "EnableVppTonemapping" to false,
            "EncodingThreadCount" to -1, "EnableThrottling" to true, "ThrottleDelaySeconds" to 180, "EnableSegmentDeletion" to true, "SegmentKeepSeconds" to 720,
            "EnableSubtitleExtraction" to true, "DownMixAudioBoost" to 2, "DownMixStereoAlgorithm" to "None", "EnableAudioVbr" to false,
            "DeinterlaceMethod" to "yadif", "DeinterlaceDoubleRate" to false, "MaxMuxingQueueSize" to 2048, "EnableFallbackFont" to false,
            "TranscodingTempPath" to "/cache/transcodes",
        )
        val srv = obj(
            "MinResumePct" to 5, "MaxResumePct" to 90, "MinResumeDurationSeconds" to 300, "MinAudiobookResume" to 5, "MaxAudiobookResume" to 5,
            "RemoteClientBitrateLimit" to 8_000_000,
            "TrickplayOptions" to obj(
                "EnableHwAcceleration" to true, "EnableHwEncoding" to false, "EnableKeyFrameOnlyExtraction" to false, "ScanBehavior" to "NonBlocking",
                "ProcessPriority" to "BelowNormal", "Interval" to 10000, "WidthResolutions" to listOf(320), "TileWidth" to 10, "TileHeight" to 10,
                "Qscale" to 4, "JpegQuality" to 90, "ProcessThreads" to 1,
            ),
        )
        val d = PlaybackDraft(enc, srv)
        d.setSwitch("enc:AllowAv1Encoding", true)
        d.setCodec("codec:av1", true)
        d.present(PlaybackState(loaded = true), "8.1", 1, GpuScan(renderNodes = listOf("/dev/dri/renderD128"), intel = true))
    }

    val settings: ServerSettingsState by lazy {
        val cfg = obj(
            "ServerName" to "seedy-server", "UICulture" to "en-US", "CachePath" to "/cache", "MetadataPath" to "/config/metadata",
            "QuickConnectAvailable" to true, "LibraryScanFanoutConcurrency" to 0, "ParallelImageEncodingLimit" to 0,
            "ActivityLogRetentionDays" to 30, "LogFileRetentionDays" to 3, "InactiveSessionThreshold" to 0, "AllowClientLogUpload" to true,
            "EnableSlowResponseWarning" to false, "SlowResponseThresholdMs" to 500, "EnableMetrics" to false,
            "CorsHosts" to listOf("*"), "PathSubstitutions" to emptyList<String>(), "LibraryMonitorDelay" to 60, "SortRemoveWords" to listOf("the", "a", "an"),
            "LibraryMetadataRefreshConcurrency" to 0,
            "CastReceiverApplications" to listOf(obj("Id" to "F007D354", "Name" to "Stable"), obj("Id" to "6F511C87", "Name" to "Unstable")),
            "EnableLegacyAuthorization" to false,
        )
        val branding = obj("SplashscreenEnabled" to true, "LoginDisclaimer" to "Private server. Ask Arky for an account.")
        val info = SystemInfo(serverName = "seedy-server", cachePath = "/cache", internalMetadataPath = "/config/metadata")
        serverSettingsState(ServerSettingsState(loaded = true), cfg, branding, info, listOf(LocalizationOption("English", "en-US"), LocalizationOption("Français", "fr")), emptyMap(), null, fields = true)
    }
}
