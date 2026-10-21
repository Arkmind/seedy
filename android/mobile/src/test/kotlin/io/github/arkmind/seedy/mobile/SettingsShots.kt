package io.github.arkmind.seedy.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.arkmind.seedy.core.data.DeviceSettings
import io.github.arkmind.seedy.core.data.SubtitleStyle
import io.github.arkmind.seedy.core.seedy.HomeRowPref
import io.github.arkmind.seedy.core.seedy.HomeRowSetting
import io.github.arkmind.seedy.core.seedy.PlaybackPrefs
import io.github.arkmind.seedy.core.seedy.RequestsFeatures
import io.github.arkmind.seedy.core.seedy.SeerrAccountRef
import io.github.arkmind.seedy.core.seedy.UserPrefs
import io.github.arkmind.seedy.core.settings.Choice
import io.github.arkmind.seedy.core.settings.CodeState
import io.github.arkmind.seedy.core.settings.LibraryInfo
import io.github.arkmind.seedy.core.settings.SettingsActions
import io.github.arkmind.seedy.core.settings.SettingsState
import io.github.arkmind.seedy.mobile.parts.Tab
import io.github.arkmind.seedy.mobile.parts.TabBar
import io.github.arkmind.seedy.mobile.settings.HomeScreenPage
import io.github.arkmind.seedy.mobile.settings.PlaybackPage
import io.github.arkmind.seedy.mobile.settings.ProfilePage
import io.github.arkmind.seedy.mobile.settings.SettingsHomeScreen
import io.github.arkmind.seedy.mobile.settings.SettingsNav
import io.github.arkmind.seedy.mobile.settings.SubtitlesPage
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.SeedyTheme
import io.github.arkmind.seedy.ui.theme.Viewer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.SubtitlePlaybackMode
import org.jellyfin.sdk.model.api.UserConfiguration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

/** The settings boards with their data, each at its board's full height (390 px wide at 1×). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SettingsShots {
    @get:Rule val compose = createComposeRule()

    private val actions = object : SettingsActions {}
    private val nav = SettingsNav()

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            SeedyTheme {
                CompositionLocalProvider(LocalViewer provides Viewer(name = "Arky", requests = true)) {
                    Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) { content() }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    @Config(qualifiers = "w390dp-h1390dp-mdpi")
    fun settings() = shot("AndroidSettings") {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { SettingsHomeScreen(SettingsSamples.state, "1.0", actions, nav, requests = true) }
            TabBar(Tab.entries, Tab.Home, {})
        }
    }

    @Test
    @Config(qualifiers = "w390dp-h1570dp-mdpi")
    fun profile() = shot("AndroidSettingsProfile") { ProfilePage(SettingsSamples.state, actions, nav) }

    @Test
    @Config(qualifiers = "w390dp-h1840dp-mdpi")
    fun home() = shot("AndroidSettingsHome") { HomeScreenPage(SettingsSamples.state, actions, nav, dragging = "top10") }

    @Test
    @Config(qualifiers = "w390dp-h2120dp-mdpi")
    fun playback() = shot("AndroidSettingsPlayback") { PlaybackPage(SettingsSamples.state, actions, nav) }

    @Test
    @Config(qualifiers = "w390dp-h1980dp-mdpi")
    fun subtitles() = shot("AndroidSettingsSubtitles") { SubtitlesPage(SettingsSamples.state, actions, nav) }
}

/** The settings boards' profile: Arky, admin of Arky's server, on a Pixel 9. */
object SettingsSamples {
    private val movies = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val shows = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val collections = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val homeVideos = UUID.fromString("00000000-0000-0000-0000-000000000004")

    private val kinds = listOf("nextup", "because", "top10", "genres", "latest", "mymedia", "upcomingShows", "discover", "upcomingMovies", "livetv", "collections")

    val config = UserConfiguration(
        audioLanguagePreference = null, playDefaultAudioTrack = false, subtitleLanguagePreference = "eng", displayMissingEpisodes = false,
        groupedFolders = emptyList(), subtitleMode = SubtitlePlaybackMode.SMART, displayCollectionsView = false, enableLocalPassword = false,
        orderedViews = emptyList(), latestItemsExcludes = listOf(collections, homeVideos), myMediaExcludes = listOf(collections, homeVideos),
        hidePlayedInLatest = true, rememberAudioSelections = true, rememberSubtitleSelections = true, enableNextEpisodeAutoPlay = true, castReceiverId = null,
    )

    val state = SettingsState(
        seedy = true,
        prefs = UserPrefs(
            playback = PlaybackPrefs(skipIntro = "button", skipRecap = "auto", skipCredits = "button"),
            rowOrder = "mine",
            rows = kinds.map { k ->
                HomeRowPref(k, enabled = k != "livetv" && k != "collections", count = mapOf("because" to 6, "genres" to 2)[k], pinned = k == "nextup")
            },
            custom = mapOf(
                "display.language" to JsonPrimitive("en"),
                "home.opensOn" to buildJsonObject { put(shows.toString().replace("-", ""), "suggestions") },
            ),
        ),
        adminRows = kinds.map { HomeRowSetting(it) },
        requests = RequestsFeatures(connected = true, canRequest = true, account = SeerrAccountRef("Arky")),
        historyEnabled = true,
        pluginVersion = "1.0",
        config = config,
        device = DeviceSettings(subtitleStyle = SubtitleStyle(size = "large", weight = "bold")),
        name = "Arky",
        isAdmin = true,
        serverName = "Arky's server",
        serverAddress = "http://192.168.1.20:8096",
        serverVersion = "12.2.0",
        deviceName = "Pixel 9",
        languages = listOf(Choice("eng", "English"), Choice("fre", "French")),
        libraries = listOf(
            LibraryInfo(movies, "Movies", CollectionType.MOVIES, 248),
            LibraryInfo(shows, "Shows", CollectionType.TVSHOWS, 61),
            LibraryInfo(collections, "Collections", CollectionType.BOXSETS, 18),
            LibraryInfo(homeVideos, "Home videos", CollectionType.HOMEVIDEOS, 1204),
        ),
        lastPlayed = "The Pass, today",
        passwordChanged = "Changed 3 months ago",
        devices = 4,
        openRequests = 3,
        code = CodeState("4F7Q2K"),
    )
}
