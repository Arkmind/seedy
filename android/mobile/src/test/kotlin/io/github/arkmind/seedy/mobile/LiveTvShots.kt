package io.github.arkmind.seedy.mobile

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.arkmind.seedy.mobile.livetv.LiveTvScreen
import io.github.arkmind.seedy.mobile.livetv.LiveView
import io.github.arkmind.seedy.ui.theme.SeedyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-mdpi", sdk = [35])
class LiveTvShots {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, view: LiveView) {
        compose.setContent {
            SeedyTheme {
                LiveTvScreen(
                    state = LiveSamples.state, view = view, selected = if (view == LiveView.Guide) LiveSamples.kestrel to LiveSamples.euroCup else null,
                    onView = {}, onBack = {}, onChannelNumber = {}, onFavoritesOnly = {}, onDay = {}, onSelect = { _, _ -> }, onRecord = {},
                    onFavorite = {}, onWatch = {}, onPlayRecording = {}, onTab = {},
                )
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun onNow() = shot("AndroidLiveTV", LiveView.OnNow)

    @Test fun guide() = shot("AndroidLiveGuide", LiveView.Guide)
}
