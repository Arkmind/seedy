package io.github.arkmind.seedy.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.unit.dp
import io.github.arkmind.seedy.mobile.details.DetailsActions
import io.github.arkmind.seedy.mobile.details.DetailsScreen
import io.github.arkmind.seedy.mobile.favorites.FavoritesScreen
import io.github.arkmind.seedy.mobile.library.LibrariesScreen
import io.github.arkmind.seedy.mobile.library.LibraryActions
import io.github.arkmind.seedy.mobile.library.LibraryScreen
import io.github.arkmind.seedy.mobile.search.SearchActions
import io.github.arkmind.seedy.mobile.search.SearchScreen
import io.github.arkmind.seedy.mobile.parts.Tab
import io.github.arkmind.seedy.mobile.parts.TabBar
import io.github.arkmind.seedy.mobile.parts.tabBarHeight
import io.github.arkmind.seedy.mobile.seasons.AdvancedActions
import io.github.arkmind.seedy.mobile.seasons.RequestSeasonsContent
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.SeedyTheme
import io.github.arkmind.seedy.ui.theme.Viewer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The browse screens with the boards' data, at the boards' size (390×844 at 1×). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-mdpi", sdk = [35])
class BrowseShots {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, tab: Tab? = null, content: @Composable () -> Unit) {
        compose.setContent {
            SeedyTheme {
                CompositionLocalProvider(LocalViewer provides Viewer(name = "Arky", requests = true)) {
                    Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
                        content()
                        if (tab != null) TabBar(Tab.entries, tab, {}, Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun details() = shot("AndroidDetails", Tab.Home) {
        DetailsScreen(BrowseSamples.details, DetailsActions(), canRequest = true, bottomPadding = tabBarHeight())
    }

    @Test
    fun requestSeasons() = shot("AndroidRequest") {
        SearchScreen(BrowseSamples.searchNothing, SearchActions(), bottomPadding = 0.dp, autofocus = false)
        RequestSeasonsContent(
            BrowseSamples.harrowgate, onToggle = {}, onToggleAll = {}, on4k = {}, advanced = AdvancedActions({}, {}, {}, {}),
            onRetry = {}, onSubmit = {}, onDismiss = {},
        )
    }

    @Test
    fun libraries() = shot("AndroidLibraries", Tab.Libraries) {
        LibrariesScreen(BrowseSamples.libraries, onOpen = {}, onSearch = {}, onHistory = {}, onOrder = {}, bottomPadding = tabBarHeight())
    }

    @Test
    fun library() = shot("AndroidLibrary", Tab.Libraries) {
        LibraryScreen(BrowseSamples.moviesLibrary, LibraryActions(), bottomPadding = tabBarHeight())
    }

    @Test
    fun search() = shot("AndroidSearch") {
        SearchScreen(BrowseSamples.search, SearchActions(), bottomPadding = 0.dp)
    }

    @Test
    fun favorites() = shot("AndroidFavorites", Tab.Favorites) {
        FavoritesScreen(BrowseSamples.favorites, onShow = {}, onOpen = {}, onSearch = {}, bottomPadding = tabBarHeight())
    }
}
