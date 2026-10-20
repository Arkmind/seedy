package io.github.arkmind.seedy.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.arkmind.seedy.core.feature.HomeViewModel
import io.github.arkmind.seedy.core.seedy.FeaturedEntry
import io.github.arkmind.seedy.core.seedy.HomeRow
import io.github.arkmind.seedy.mobile.home.AccountActions
import io.github.arkmind.seedy.mobile.home.HomeScreen
import io.github.arkmind.seedy.mobile.parts.Tab
import io.github.arkmind.seedy.mobile.parts.TabBar
import io.github.arkmind.seedy.mobile.parts.tabBarHeight
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SeedyTheme
import io.github.arkmind.seedy.ui.theme.Viewer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-mdpi", sdk = [35])
class HomeShots {
    @get:Rule val compose = createComposeRule()

    @Test
    fun home() {
        val state = HomeViewModel.State(
            loading = false,
            featured = List(5) { FeaturedEntry(Samples.northbound, reason = "New episode Friday", play = Samples.thePass) },
            rows = listOf(
                HomeViewModel.Row(HomeRow("continue", "continue"), "Continue watching", listOf(Samples.thePass, Samples.paperLanterns, Samples.glassHarbor)),
            ),
        )
        compose.setContent {
            SeedyTheme {
                CompositionLocalProvider(LocalViewer provides Viewer(name = "Arky")) {
                Box(Modifier.fillMaxSize()) {
                    HomeScreen(state, AccountActions({}, {}), onOpen = {}, onPlay = {}, onSearch = {}, onSeeAll = {}, bottomPadding = tabBarHeight())
                    TabBar(Tab.entries, Tab.Home, {}, Modifier.align(Alignment.BottomCenter))
                }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/AndroidHome.png")
    }
}
