package io.github.arkmind.seedy.mobile

import androidx.compose.ui.test.junit4.createComposeRule
import io.github.arkmind.seedy.core.admin.Tone
import io.github.arkmind.seedy.core.admin.seedy.AdminConfig
import io.github.arkmind.seedy.core.admin.seedy.AdminRequestsActions
import io.github.arkmind.seedy.core.admin.seedy.AdminRequestsState
import io.github.arkmind.seedy.core.admin.seedy.BecauseConfig
import io.github.arkmind.seedy.core.admin.seedy.BrandingActions
import io.github.arkmind.seedy.core.admin.seedy.BrandingConfig
import io.github.arkmind.seedy.core.admin.seedy.BrandingState
import io.github.arkmind.seedy.core.admin.seedy.FeaturedConfig
import io.github.arkmind.seedy.core.admin.seedy.GenresConfig
import io.github.arkmind.seedy.core.admin.seedy.HomeAdminActions
import io.github.arkmind.seedy.core.admin.seedy.HomeAdminState
import io.github.arkmind.seedy.core.admin.seedy.HomeConfig
import io.github.arkmind.seedy.core.admin.seedy.ListingsProvider
import io.github.arkmind.seedy.core.admin.seedy.LiveTvAdminActions
import io.github.arkmind.seedy.core.admin.seedy.LiveTvAdminState
import io.github.arkmind.seedy.core.admin.seedy.NetworkConfig
import io.github.arkmind.seedy.core.admin.seedy.NetworkingActions
import io.github.arkmind.seedy.core.admin.seedy.NetworkingState
import io.github.arkmind.seedy.core.admin.seedy.PickItem
import io.github.arkmind.seedy.core.admin.seedy.PlayLogAdminActions
import io.github.arkmind.seedy.core.admin.seedy.PlayLogAdminState
import io.github.arkmind.seedy.core.admin.seedy.PlayLogProfile
import io.github.arkmind.seedy.core.admin.seedy.RecSettings
import io.github.arkmind.seedy.core.admin.seedy.RequestsConfig
import io.github.arkmind.seedy.core.admin.seedy.SeerrAccount
import io.github.arkmind.seedy.core.admin.seedy.SeerrTestResult
import io.github.arkmind.seedy.core.admin.seedy.SourceRow
import io.github.arkmind.seedy.core.admin.seedy.Top10Config
import io.github.arkmind.seedy.core.admin.seedy.TunerHost
import io.github.arkmind.seedy.core.admin.seedy.TunerRow
import io.github.arkmind.seedy.core.admin.seedy.normaliseRows
import io.github.arkmind.seedy.mobile.admin.seedy.AdminRequestsScreen
import io.github.arkmind.seedy.mobile.admin.seedy.BrandingScreen
import io.github.arkmind.seedy.mobile.admin.seedy.DefaultHomeScreen
import io.github.arkmind.seedy.mobile.admin.seedy.LiveTvAdminScreen
import io.github.arkmind.seedy.mobile.admin.seedy.NetworkingScreen
import io.github.arkmind.seedy.mobile.admin.seedy.PlayLogScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The server's Networking and Live TV pages and Seedy's admin pages. They have no phone boards: the samples are the
 * web boards' (ServerNetworking, ServerLiveTV, AdminGeneral, AdminHome, AdminRequests, AdminPlayLog).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AdminSeedyShots {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w390dp-h1830dp-mdpi")
    fun networking() = compose.adminShot("AndroidAdminNetworking") {
        NetworkingScreen(SeedySamples.networking, object : NetworkingActions {}, onBack = {})
    }

    @Test
    @Config(qualifiers = "w390dp-h1490dp-mdpi")
    fun liveTv() = compose.adminShot("AndroidAdminLiveTV") {
        LiveTvAdminScreen(SeedySamples.liveTv, object : LiveTvAdminActions {}, onBack = {})
    }

    @Test
    @Config(qualifiers = "w390dp-h1920dp-mdpi")
    fun branding() = compose.adminShot("AndroidAdminBranding") {
        BrandingScreen(SeedySamples.branding, object : BrandingActions {}, onBack = {}, onPickLogo = {})
    }

    @Test
    @Config(qualifiers = "w390dp-h2700dp-mdpi")
    fun homeScreen() = compose.adminShot("AndroidAdminHomeScreen") {
        DefaultHomeScreen(SeedySamples.home, object : HomeAdminActions {}, onBack = {})
    }

    @Test
    @Config(qualifiers = "w390dp-h1670dp-mdpi")
    fun requests() = compose.adminShot("AndroidAdminRequests") {
        AdminRequestsScreen(SeedySamples.requests, object : AdminRequestsActions {}, onBack = {}, openUrl = {})
    }

    @Test
    @Config(qualifiers = "w390dp-h1310dp-mdpi")
    fun playLog() = compose.adminShot("AndroidAdminPlayLog") {
        PlayLogScreen(SeedySamples.playLog, object : PlayLogAdminActions {}, onBack = {}, onExport = {})
    }
}

object SeedySamples {
    private val netSaved = NetworkConfig(
        enableRemoteAccess = false,
        publishedServerUriBySubnet = listOf("external=https://watch.example.com"),
        localNetworkSubnets = listOf("192.168.1.0/24"),
        knownProxies = listOf("172.18.0.2"),
    )

    val networking = NetworkingState(saved = netSaved, draft = netSaved.copy(enableRemoteAccess = true), watching = 3)

    private val recSaved = RecSettings(
        guideDays = 0, recordingPath = "/media/recordings", seriesRecordingPath = "/media/tv-recordings",
        prePaddingSeconds = 120, postPaddingSeconds = 300, recordingPostProcessorArguments = "\"{path}\"",
        saveRecordingNfo = true, saveRecordingImages = false,
    )

    val liveTv = LiveTvAdminState(
        loaded = true,
        tuners = listOf(TunerHost(id = "hd", type = "hdhomerun", friendlyName = "Living room HDHomeRun", url = "http://192.168.1.40", tunerCount = 4), TunerHost(id = "m3u", url = "http://iptv.local/playlist.m3u", tunerCount = 2)),
        tunerRows = listOf(
            TunerRow("hd", "Living room HDHomeRun", m3u = false, sub = "HDHomeRun · 192.168.1.40 · 4 streams · 87 channels", online = true),
            TunerRow("m3u", "IPTV playlist", m3u = true, sub = "M3U tuner · http://iptv.local/playlist.m3u · 2 streams · 214 channels", online = null),
        ),
        providers = listOf(ListingsProvider(id = "x", path = "/config/xmltv/tv-guide.xml"), ListingsProvider(id = "sd", type = "schedulesdirect")),
        sourceRows = listOf(
            SourceRow("x", "XMLTV · tv-guide.xml", "XMLTV", "Movies: Movie|Film · Kids: Children · News: News · Sports: Sports · All tuners", "Updated 2 hours ago · 7 days of listings", Tone.Ok),
            SourceRow("sd", "Schedules Direct", "Lineup", "alex.sd · France · 75001 · Living room HDHomeRun only", "12 channels not mapped", Tone.Pending),
        ),
        recSaved = recSaved,
        rec = recSaved.copy(saveRecordingImages = true),
    )

    val branding = BrandingState(
        config = AdminConfig(opensAt = "alongside", branding = BrandingConfig(hasLogo = true, logoFileName = "logo_full.svg")),
        alongsidePath = "/seedy",
    )

    private val homeSaved = HomeConfig(
        rows = normaliseRows(emptyList()),
        because = BecauseConfig(minTitles = 3),
        top10 = Top10Config(minProfiles = 2),
        genres = GenresConfig(starting = listOf("Thriller", "Comedy", "Drama", "Documentary")),
        featured = FeaturedConfig(mode = "both", picks = listOf("a", "b")),
    )

    val home = HomeAdminState(
        saved = homeSaved,
        draft = homeSaved.copy(because = homeSaved.because.copy(minTitles = 4), top10 = homeSaved.top10.copy(minProfiles = 3)),
        picks = mapOf("a" to PickItem("a", "Northbound", "2024 · Show", null), "b" to PickItem("b", "Saltwater", "2023 · Movie", null)),
    )

    val requests = AdminRequestsState(
        config = AdminConfig(requests = RequestsConfig(url = "http://seerr.local:5055", apiKeyHint = "4f2a")),
        test = SeerrTestResult(ok = true, message = "Connected. Seerr can see Radarr and Sonarr.", radarr = true, sonarr = true),
        accounts = listOf(
            SeerrAccount("1", "Alex", isAdmin = true, seerrUserId = 1, seerrName = "alex", matchedBy = "jellyfin", canRequest = true),
            SeerrAccount("2", "Sam", seerrUserId = 2, seerrName = "sam", matchedBy = "jellyfin", canRequest = true),
            SeerrAccount("3", "Léa"),
            SeerrAccount("4", "Kids", maxParentalRating = "TV-PG"),
        ),
    )

    val playLog = PlayLogAdminState(
        config = AdminConfig(),
        profiles = listOf(
            PlayLogProfile("1", "Alex", plays = 412),
            PlayLogProfile("2", "Sam", plays = 288),
            PlayLogProfile("3", "Léa", plays = 157, top10 = false),
            PlayLogProfile("4", "Kids", plays = 96, top10 = false, history = false),
        ),
    )
}
