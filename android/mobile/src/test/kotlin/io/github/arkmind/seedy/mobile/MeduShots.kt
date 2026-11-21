package io.github.arkmind.seedy.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.arkmind.seedy.ui.components.Medu
import io.github.arkmind.seedy.ui.components.MeduBridgeFrame
import io.github.arkmind.seedy.ui.components.MeduMood
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.SeedyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Medu's moods in their rest pose, side by side (board "Brand · Mascot"), for comparing with the boards' SVG. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1560dp-h300dp-mdpi", sdk = [35])
class MeduShots {
    @get:Rule val compose = createComposeRule()

    @Test
    fun moods() {
        compose.setContent {
            SeedyTheme {
                Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
                    MeduMood.entries.forEachIndexed { i, m -> Medu(m, 130.dp, Modifier.offset((i * 140 + 5).dp, 40.dp), height = 172.dp) }
                    Medu(MeduMood.Idle, 60.dp, Modifier.offset(5.dp, 220.dp), height = 79.dp, small = true)
                    Medu(MeduMood.Search, 60.dp, Modifier.offset(85.dp, 220.dp), height = 79.dp, torch = false)
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/MeduMoods.png")
    }

    /**
     * Board "Moving between moods": the two bridges the app plays (Oops → Hello when the server answers again,
     * Sleepy → Popcorn on Keep watching), each held at nine points from start to end, as the web's filmstrips.
     */
    @Test
    @Config(qualifiers = "w1270dp-h420dp-mdpi")
    fun bridges() {
        compose.setContent {
            SeedyTheme {
                Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
                    listOf(MeduMood.Oops to MeduMood.Wave, MeduMood.Sleepy to MeduMood.Popcorn).forEachIndexed { row, (from, to) ->
                        for (i in 0..8) {
                            MeduBridgeFrame(from, to, i / 8f, 130.dp, Modifier.offset((i * 140 + 5).dp, (row * 210 + 25).dp), height = 172.dp)
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/MeduBridges.png")
    }
}
