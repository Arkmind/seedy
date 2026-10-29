package io.github.arkmind.seedy.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.arkmind.seedy.mobile.audio.LocalMiniBar
import io.github.arkmind.seedy.mobile.audio.MiniBar
import io.github.arkmind.seedy.mobile.audio.MiniBarActions
import io.github.arkmind.seedy.mobile.audio.MiniBarSpace
import io.github.arkmind.seedy.mobile.books.AudiobookActions
import io.github.arkmind.seedy.mobile.books.AudiobookScreen
import io.github.arkmind.seedy.mobile.books.BookActions
import io.github.arkmind.seedy.mobile.books.BookScreen
import io.github.arkmind.seedy.mobile.books.BooksActions
import io.github.arkmind.seedy.mobile.books.BooksScreen
import io.github.arkmind.seedy.mobile.parts.Tab
import io.github.arkmind.seedy.mobile.parts.TabBar
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

/** The books screens with the boards' data, at the boards' size (390×844 at 1×), under the tab bar as the app shows them. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-mdpi", sdk = [35])
class BooksShots {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, tab: Tab? = null, mini: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            SeedyTheme {
                CompositionLocalProvider(LocalViewer provides Viewer(name = "Arky", requests = true), LocalMiniBar provides mini) {
                    Column(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
                        Box(Modifier.weight(1f)) {
                            content()
                            if (mini) {
                                MiniBar(BooksSamples.playing, BooksSamples.playingTime, MiniBarActions(), Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
                            }
                        }
                        if (tab != null) TabBar(Tab.entries, tab, {})
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun books() = shot("AndroidBooks", Tab.Libraries, mini = true) {
        BooksScreen(BooksSamples.books, BooksActions(), bottomPadding = MiniBarSpace, now = BooksSamples.now)
    }

    @Test
    fun bookDetails() = shot("AndroidBookDetails", Tab.Libraries) {
        BookScreen(BooksSamples.details, BookActions(onDownload = {}), bottomPadding = 0.dp, now = BooksSamples.now)
    }

    @Test
    fun audiobook() = shot("AndroidAudiobook") {
        AudiobookScreen(BooksSamples.playing, BooksSamples.playingTime, AudiobookActions(), output = "WH-1000XM5", volume = 0.7f, now = BooksSamples.now)
    }

    /** No board: the audiobook player's Chapters sheet. */
    @Test
    fun audiobookChapters() = shot("AndroidAudiobookChapters") {
        AudiobookScreen(BooksSamples.playing, BooksSamples.playingTime, AudiobookActions(), output = "WH-1000XM5", volume = 0.7f, now = BooksSamples.now)
        io.github.arkmind.seedy.mobile.books.ChaptersSheet(BooksSamples.fogBook, BooksSamples.fogPosition, onPick = {}, onClose = {})
    }

    /** No board: the audiobook player's Bookmarks sheet. */
    @Test
    fun audiobookBookmarks() = shot("AndroidAudiobookBookmarks") {
        AudiobookScreen(BooksSamples.playing, BooksSamples.playingTime, AudiobookActions(), output = "WH-1000XM5", volume = 0.7f, now = BooksSamples.now, bookmarked = true)
        io.github.arkmind.seedy.mobile.books.BookmarksSheet(BooksSamples.fogBook, BooksSamples.fogPosition, BooksSamples.bookmarks, onAdd = {}, onRemove = {}, onPick = {}, onClose = {})
    }

    /** No board: an audiobook's own page (Listen first, Read the EPUB second). */
    @Test
    fun audiobookDetails() = shot("AndroidAudiobookDetails", Tab.Libraries) {
        BookScreen(BooksSamples.audioDetails, BookActions(onDownload = {}), bottomPadding = 0.dp, now = BooksSamples.now)
    }

    /** No board: the books library's Series view. */
    @Test
    fun booksSeries() = shot("AndroidBooksSeries", Tab.Libraries, mini = true) {
        BooksScreen(BooksSamples.books.copy(view = io.github.arkmind.seedy.core.books.BooksView.Series), BooksActions(), bottomPadding = MiniBarSpace, now = BooksSamples.now)
    }

    /** No board: one series opened. */
    @Test
    fun booksOneSeries() = shot("AndroidBooksOneSeries", Tab.Libraries) {
        BooksScreen(
            BooksSamples.books.copy(view = io.github.arkmind.seedy.core.books.BooksView.Series, focus = "The Halden Cycle"), BooksActions(),
            bottomPadding = 0.dp, now = BooksSamples.now,
        )
    }
}
