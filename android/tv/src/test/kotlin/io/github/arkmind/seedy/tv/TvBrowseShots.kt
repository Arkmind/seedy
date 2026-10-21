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
import io.github.arkmind.seedy.tv.details.TvDetailsActions
import io.github.arkmind.seedy.tv.details.TvDetailsScreen
import io.github.arkmind.seedy.tv.favorites.TvFavoritesScreen
import io.github.arkmind.seedy.tv.library.TvLibraryActions
import io.github.arkmind.seedy.tv.library.TvLibraryScreen
import io.github.arkmind.seedy.tv.requests.TvRequestsActions
import io.github.arkmind.seedy.tv.requests.TvRequestsScreen
import io.github.arkmind.seedy.tv.search.TvSearchActions
import io.github.arkmind.seedy.tv.search.TvSearchScreen
import io.github.arkmind.seedy.tv.seasons.TvAdvancedActions
import io.github.arkmind.seedy.tv.seasons.TvRequestSeasonsContent
import io.github.arkmind.seedy.tv.shell.TvPlace
import io.github.arkmind.seedy.tv.shell.TvRail
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
/** The TV browse screens with the boards' data at 1920×1080 px (960×540 dp at 2×). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi", sdk = [35])
class TvBrowseShots {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, place: TvPlace? = null, content: @Composable () -> Unit) {
        compose.setContent {
            // A television is in key (remote) input mode, where clickable things can take focus.
            val inputMode = LocalInputModeManager.current
            SideEffect { inputMode.requestInputMode(InputMode.Keyboard) }
            SeedyTheme {
                CompositionLocalProvider(LocalViewer provides Viewer(name = "Arky", requests = true)) {
                    Box(Modifier.fillMaxSize()) {
                        content()
                        if (place != null) TvRail(TvPlace.entries, place, onPlace = {}, onProfile = {}, onSettings = {})
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun details() = shot("TVDetails") {
        TvDetailsScreen(TvBrowseSamples.details, TvDetailsActions(), canRequest = true)
    }

    /** No board: the series page without seasons, "More like this" in their place. */
    @Test
    fun movieDetails() = shot("TVMovieDetails") {
        TvDetailsScreen(TvBrowseSamples.movieDetails, TvDetailsActions(), canRequest = true)
    }

    /** No board: the library's Sort choices. */
    @Test
    fun sort() = shot("TVSort", TvPlace.Movies) {
        TvLibraryScreen(TvBrowseSamples.moviesLibrary, TvLibraryActions())
        io.github.arkmind.seedy.tv.library.TvSortPanel(io.github.arkmind.seedy.core.browse.Sort.DateAdded, true, onPick = { _, _ -> }, onDismiss = {})
    }

    @Test
    fun requestSeasons() = shot("TVRequestSeasons") {
        TvDetailsScreen(TvBrowseSamples.details, TvDetailsActions(), canRequest = true)
        TvRequestSeasonsContent(
            TvBrowseSamples.harrowgate, onToggle = {}, onToggleAll = {}, on4k = {}, advanced = TvAdvancedActions({}, {}, {}, {}),
            onRetry = {}, onSubmit = {}, onDismiss = {}, focusSeason = 3,
        )
    }

    @Test
    fun library() = shot("TVLibrary", TvPlace.Movies) {
        TvLibraryScreen(TvBrowseSamples.moviesLibrary, TvLibraryActions(), initialFocus = 1)
    }

    @Test
    fun search() = shot("TVSearch", TvPlace.Search) {
        TvSearchScreen(TvBrowseSamples.search, TvSearchActions(), focusKey = 'h')
    }

    @Test
    fun requests() = shot("TVRequests", TvPlace.Requests) {
        TvRequestsScreen(TvRequestsSamples.requests, TvRequestsActions(), canRequest = true, today = TvRequestsSamples.today)
    }

    /** No board: the details hero for a Seerr title. */
    @Test
    fun seerrTitle() = shot("TVSeerrTitle") {
        io.github.arkmind.seedy.tv.requests.TvSeerrTitleScreen(TvRequestsSamples.title, canRequest = true, allow4k = false, actions = io.github.arkmind.seedy.tv.requests.TvSeerrTitleActions())
    }

    @Test
    fun favorites() = shot("TVFavorites", TvPlace.Favorites) {
        TvFavoritesScreen(TvBrowseSamples.favorites, onShow = {}, onOpen = {})
    }
}
