package io.github.arkmind.seedy.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.arkmind.seedy.tv.livetv.TvGuide
import io.github.arkmind.seedy.tv.shell.TvPlace
import io.github.arkmind.seedy.tv.shell.TvRail
import io.github.arkmind.seedy.ui.theme.SeedyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi", sdk = [35])
class TvLiveShots {
    @get:Rule val compose = createComposeRule()

    @Test
    fun guide() {
        compose.setContent {
            val inputMode = LocalInputModeManager.current
            SideEffect { inputMode.requestInputMode(InputMode.Keyboard) }
            SeedyTheme {
                Box(Modifier.fillMaxSize()) {
                    TvGuide(
                        state = LiveSamples.state, focused = LiveSamples.kestrel to LiveSamples.euroCup, previewPlayer = {}, previewPlaying = false,
                        onFocus = { _, _ -> }, onWatch = {}, onRecord = {}, onFavoritesOnly = {}, onDay = {},
                    )
                    TvRail(TvPlace.entries, TvPlace.LiveTv, onPlace = {}, onProfile = {}, onSettings = {})
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/TVGuide.png")
    }
}
