package io.github.arkmind.seedy.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.arkmind.seedy.core.feature.HomeViewModel
import io.github.arkmind.seedy.core.feature.ProfilesViewModel
import io.github.arkmind.seedy.core.seedy.HomeRow
import io.github.arkmind.seedy.tv.home.TvHomeScreen
import io.github.arkmind.seedy.tv.shell.TvPlace
import io.github.arkmind.seedy.tv.shell.TvRail
import io.github.arkmind.seedy.tv.signin.TvProfilesScreen
import io.github.arkmind.seedy.tv.signin.TvAddServerScreen
import io.github.arkmind.seedy.tv.signin.TvUnreachableScreen
import io.github.arkmind.seedy.tv.update.TvUpdateScreen
import io.github.arkmind.seedy.core.update.ReleaseNotes
import io.github.arkmind.seedy.core.update.UpdateOffer
import io.github.arkmind.seedy.tv.player.TvScreensaver
import io.github.arkmind.seedy.core.data.SavedServer
import io.github.arkmind.seedy.core.feature.AddServerViewModel
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SeedyTheme
import io.github.arkmind.seedy.ui.theme.Viewer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
/** The TV screens with the boards' data at 1920×1080 px (960×540 dp at 2×), for comparing with the boards. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi", sdk = [35])
class TvShots {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            // A television is in key (remote) input mode, where clickable things can take focus.
            val inputMode = LocalInputModeManager.current
            SideEffect { inputMode.requestInputMode(InputMode.Keyboard) }
            SeedyTheme { CompositionLocalProvider(LocalViewer provides Viewer(name = "Arky"), content = content) }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private fun profile(id: String, name: String, colour: Int, session: Boolean) =
        ProfilesViewModel.Profile(id, name, imageUrl = null, hasSession = session, account = null, colour = colour)

    @Test
    fun profiles() = shot("TVProfiles") {
        TvProfilesScreen(
            brand = "Seedy",
            state = ProfilesViewModel.State(
                loading = false,
                profiles = listOf(profile("a", "Arky", 0, false), profile("s", "Sam", 1, true), profile("m", "Mia", 2, true), profile("k", "Kids", 3, true)),
                quickConnect = true,
            ),
            onProfile = {}, onAddAccount = {}, onQuickConnect = {}, onChangeServer = {},
        )
    }

    private val arky = SavedServer(id = "s", name = "Arky’s server", address = "http://192.168.1.20:8096")

    @Test
    fun serverFind() = shot("TVServerFind") {
        TvAddServerScreen(
            AddServerViewModel.State(found = listOf(AddServerViewModel.Found("Arky’s server", "http://192.168.1.20:8096", "12.0.1", seedy = true))),
            onAdd = {}, onSearch = {}, onQuickConnect = {},
        )
    }

    @Test
    fun serverUnreachable() = shot("TVServerUnreachable") {
        TvUnreachableScreen(arky, retryIn = 12, onRetry = {}, onChooseServer = {})
    }

    private val release = """
        # What's Changed
        - <!-- icon:play_button --> Your own “Still watching?” rules
          2–4 episodes, 90 min–3 h, or 1–3 movies
        - <!-- icon:mobile --> See where you’re signed in
          Sign out a phone or TV you no longer use
        - <!-- icon:sparkles --> Medu moved in
          A little jellyfish for the quiet moments
    """.trimIndent()

    @Test
    fun updateReady() = shot("TVUpdate") {
        val offer = UpdateOffer("1.7", 1_070_099, ReleaseNotes.parse(release), bytes = 18_400_000, fromPlay = true)
        TvUpdateScreen(offer, installed = "1.6", brand = "Seedy", onUpdate = {}, onLater = {})
    }

    @Test
    fun screensaver() = shot("TVScreensaver") {
        TvScreensaver(
            Samples.thePass.copy(runTimeTicks = 52 * 60 * 10_000_000L), positionMs = 18 * 60_000L + 12_000L, durationMs = 52 * 60_000L,
            now = java.time.LocalDateTime.of(2026, 11, 14, 21, 47),
        )
    }

    @Test
    fun home() = shot("TVHome") {
        val state = HomeViewModel.State(
            loading = false,
            hasHero = false,
            rows = listOf(
                HomeViewModel.Row(HomeRow("continue", "continue"), "Continue watching", listOf(Samples.thePass, Samples.paperLanterns, Samples.glassHarbor)),
                HomeViewModel.Row(HomeRow("because", "because"), "Because you watched Northbound", listOf(Samples.northbound, Samples.paperLanterns, Samples.glassHarbor)),
            ),
        )
        Box(Modifier.fillMaxSize()) {
            TvHomeScreen(state, onOpen = {})
            TvRail(TvPlace.entries, TvPlace.Home, onPlace = {}, onProfile = {}, onSettings = {})
        }
    }
}
