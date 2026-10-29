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
import io.github.arkmind.seedy.core.mediarequests.RequestService
import io.github.arkmind.seedy.mobile.parts.Tab
import io.github.arkmind.seedy.mobile.parts.TabBar
import io.github.arkmind.seedy.mobile.parts.tabBarHeight
import io.github.arkmind.seedy.mobile.requests.ArtistSheetActions
import io.github.arkmind.seedy.mobile.requests.BookSheetActions
import io.github.arkmind.seedy.mobile.requests.MediaActions
import io.github.arkmind.seedy.mobile.requests.MediaRequestsScreen
import io.github.arkmind.seedy.mobile.requests.RequestArtistContent
import io.github.arkmind.seedy.mobile.requests.RequestBookContent
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

/** The Requests tab's Music and Books with the boards' data, at the boards' size (390×844 at 1×). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-mdpi", sdk = [35])
class MediaRequestsShots {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, tab: Tab? = Tab.Requests, content: @Composable () -> Unit) {
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

    private val actions = MediaActions(onOpenApp = {})

    @Test
    fun music() = shot("AndroidRequestsMusic") {
        MediaRequestsScreen(
            MediaRequestsSamples.music, music = true, MediaRequestsSamples.services(RequestService.Music), actions,
            bottomPadding = tabBarHeight(), today = MediaRequestsSamples.today,
        )
    }

    @Test
    fun books() = shot("AndroidRequestsBooks") {
        MediaRequestsScreen(
            MediaRequestsSamples.books, music = false, MediaRequestsSamples.services(RequestService.Books), actions,
            bottomPadding = tabBarHeight(), today = MediaRequestsSamples.today,
        )
    }

    /** No board: Music · My requests. */
    @Test
    fun musicMine() = shot("AndroidRequestsMusicMine") {
        MediaRequestsScreen(
            MediaRequestsSamples.musicMineTab, music = true, MediaRequestsSamples.services(RequestService.Music), actions,
            bottomPadding = tabBarHeight(), today = MediaRequestsSamples.today,
        )
    }

    /** No board: a music row's See all. */
    @Test
    fun seeAll() = shot("AndroidRequestsSeeAll") {
        io.github.arkmind.seedy.mobile.requests.MediaRowScreen(
            music = true, row = "missing",
            state = io.github.arkmind.seedy.core.mediarequests.MediaRowViewModel.State(albums = MediaRequestsSamples.music.musicRows.flatMap { it.items.orEmpty() }),
            onBack = {}, onAlbum = {}, onBook = {}, onRetry = {}, today = MediaRequestsSamples.today,
        )
    }

    /** No board: the books search. */
    @Test
    fun search() = shot("AndroidRequestsSearch") {
        io.github.arkmind.seedy.mobile.requests.MediaSearchScreen(
            music = false,
            state = io.github.arkmind.seedy.core.mediarequests.MediaSearchViewModel.State(
                text = "halden", query = "halden", books = MediaRequestsSamples.books.bookRows.flatMap { it.items.orEmpty() },
            ),
            onText = {}, onBack = {}, onRetry = {}, onArtist = {}, onAlbum = {}, onBook = {}, bottomPadding = tabBarHeight(), autofocus = false, today = MediaRequestsSamples.today,
        )
    }

    /** No board: the artist window. */
    @Test
    fun artist() = shot("AndroidRequestArtist", tab = null) {
        MediaRequestsScreen(MediaRequestsSamples.music, music = true, MediaRequestsSamples.services(RequestService.Music), actions, bottomPadding = tabBarHeight(), today = MediaRequestsSamples.today)
        RequestArtistContent(MediaRequestsSamples.artist, ArtistSheetActions({}, {}, {}, {}, {}, {}, {}), onDismiss = {})
    }

    /** No board: the book window. */
    @Test
    fun book() = shot("AndroidRequestBook", tab = null) {
        MediaRequestsScreen(MediaRequestsSamples.books, music = false, MediaRequestsSamples.services(RequestService.Books), actions, bottomPadding = tabBarHeight(), today = MediaRequestsSamples.today)
        RequestBookContent(MediaRequestsSamples.book, BookSheetActions({}, {}, {}, {}), cover = { it.coverUrl }, onDismiss = {})
    }
}
