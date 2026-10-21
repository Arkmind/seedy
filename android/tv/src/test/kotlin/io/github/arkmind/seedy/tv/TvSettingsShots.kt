package io.github.arkmind.seedy.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.arkmind.seedy.core.data.DeviceSettings
import io.github.arkmind.seedy.core.data.SubtitleStyle
import io.github.arkmind.seedy.core.seedy.HomeRowPref
import io.github.arkmind.seedy.core.seedy.HomeRowSetting
import io.github.arkmind.seedy.core.seedy.UserPrefs
import io.github.arkmind.seedy.core.settings.Choice
import io.github.arkmind.seedy.core.settings.SettingsActions
import io.github.arkmind.seedy.core.settings.SettingsState
import io.github.arkmind.seedy.tv.settings.TvSection
import io.github.arkmind.seedy.tv.settings.TvSettingsScreen
import io.github.arkmind.seedy.tv.shell.TvPlace
import io.github.arkmind.seedy.tv.shell.TvRail
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SeedyTheme
import io.github.arkmind.seedy.ui.theme.Viewer
import org.jellyfin.sdk.model.api.SubtitlePlaybackMode
import org.jellyfin.sdk.model.api.UserConfiguration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
/** The TV settings boards with their data at 1920×1080 px, the remote on the row each board shows. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi", sdk = [35])
class TvSettingsShots {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            val inputMode = LocalInputModeManager.current
            SideEffect { inputMode.requestInputMode(InputMode.Keyboard) }
            SeedyTheme {
                CompositionLocalProvider(LocalViewer provides Viewer(name = "Arky", requests = true)) {
                    Box(Modifier.fillMaxSize()) {
                        content()
                        TvRail(TvPlace.entries, current = null, onPlace = {}, onProfile = {}, onSettings = {}, settingsCurrent = true)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private val actions = object : SettingsActions {}

    @Test
    fun home() = shot("TVSettingsHome") {
        TvSettingsScreen(TvSettingsSamples.state, actions, "1.0", onLeave = {}, start = TvSection.Home, focusRow = "top10", moving = "top10")
    }

    @Test
    fun playback() = shot("TVSettingsPlayback") {
        TvSettingsScreen(TvSettingsSamples.state, actions, "1.0", onLeave = {}, start = TvSection.Playback, focusRow = "quality")
    }

    @Test
    fun subtitles() = shot("TVSettingsSubtitles") {
        TvSettingsScreen(TvSettingsSamples.state, actions, "1.0", onLeave = {}, start = TvSection.Subtitles, focusRow = "size")
    }
}

/** The TV settings boards' profile: Arky, admin of Arky's server. */
object TvSettingsSamples {
    private val kinds = listOf("nextup", "because", "genres", "top10", "latest", "mymedia", "upcomingShows", "discover", "livetv", "collections")

    val state = SettingsState(
        seedy = true,
        prefs = UserPrefs(
            rowOrder = "mine",
            rows = kinds.map { k ->
                HomeRowPref(k, enabled = k != "livetv" && k != "collections", count = mapOf("because" to 6, "genres" to 2)[k], pinned = k == "nextup")
            },
        ),
        adminRows = kinds.map { HomeRowSetting(it) },
        config = UserConfiguration(
            audioLanguagePreference = null, playDefaultAudioTrack = false, subtitleLanguagePreference = "eng", displayMissingEpisodes = false,
            groupedFolders = emptyList(), subtitleMode = SubtitlePlaybackMode.SMART, displayCollectionsView = false, enableLocalPassword = false,
            orderedViews = emptyList(), latestItemsExcludes = emptyList(), myMediaExcludes = emptyList(),
            hidePlayedInLatest = true, rememberAudioSelections = true, rememberSubtitleSelections = true, enableNextEpisodeAutoPlay = true, castReceiverId = null,
        ),
        device = DeviceSettings(maxResolution = 3840, subtitleStyle = SubtitleStyle(size = "large", weight = "bold")),
        name = "Arky",
        isAdmin = true,
        serverName = "Arky's server",
        serverVersion = "12.2.0",
        languages = listOf(Choice("eng", "English"), Choice("fre", "French")),
    )
}
