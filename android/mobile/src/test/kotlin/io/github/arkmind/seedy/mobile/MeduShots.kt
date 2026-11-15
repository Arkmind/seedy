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
@Config(qualifiers = "w1000dp-h300dp-mdpi", sdk = [35])
class MeduShots {
    @get:Rule val compose = createComposeRule()

    @Test
    fun moods() {
        compose.setContent {
            SeedyTheme {
                Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
                    val moods = listOf(MeduMood.Idle, MeduMood.Wave, MeduMood.Search, MeduMood.Sleepy, MeduMood.Yay, MeduMood.Think, MeduMood.Oops)
                    moods.forEachIndexed { i, m -> Medu(m, 130.dp, Modifier.offset((i * 140 + 5).dp, 40.dp), height = 172.dp) }
                    Medu(MeduMood.Idle, 60.dp, Modifier.offset(5.dp, 220.dp), height = 79.dp, small = true)
                    Medu(MeduMood.Search, 60.dp, Modifier.offset(85.dp, 220.dp), height = 79.dp, torch = false)
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/MeduMoods.png")
    }
}
