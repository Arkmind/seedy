package io.github.arkmind.seedy.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.arkmind.seedy.core.books.Bookmark
import io.github.arkmind.seedy.core.books.ComicSettings
import io.github.arkmind.seedy.core.books.EpubSettings
import io.github.arkmind.seedy.core.books.TocEntry
import io.github.arkmind.seedy.mobile.books.reader.ComicSettingsSheet
import io.github.arkmind.seedy.mobile.books.reader.ContentsSheet
import io.github.arkmind.seedy.mobile.books.reader.EpubSettingsSheet
import io.github.arkmind.seedy.mobile.books.reader.ReaderActions
import io.github.arkmind.seedy.mobile.books.reader.ReaderChrome
import io.github.arkmind.seedy.mobile.books.reader.ReaderScreen
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The reader's frame around the board's sample page (the book's own page is a WebView, which Robolectric can't draw). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-mdpi", sdk = [35])
class ReaderShots {
    @get:Rule val compose = createComposeRule()

    private val chrome = ReaderChrome(
        title = "The Glass Orchard",
        sub = "Chapter 12 · Frost",
        page = 190,
        total = 412,
        left = "Page 190 of 412 · 46%",
        right = "6 pages left in chapter",
        settings = true,
        bookmark = false,
        contents = true,
    )

    private fun shot(name: String, sheet: @Composable () -> Unit = {}) {
        compose.setContent {
            SeedyTheme {
                ReaderScreen(chrome, shown = true, actions = ReaderActions(onBack = {})) { BoardPage(it) }
                sheet()
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun reader() = shot("AndroidReader")

    @Test fun settings() = shot("AndroidReaderSettings") { EpubSettingsSheet(EpubSettings(), {}, {}) }

    @Test fun comicSettings() = shot("AndroidReaderComicSettings") { ComicSettingsSheet(ComicSettings(), {}, {}) }

    @Test fun contents() = shot("AndroidReaderContents") {
        val titles = listOf(
            "The Count", "Rows", "The Ledger", "Ilse", "Sleet", "The New Tree", "Market Day", "Lanterns", "The Glazier", "Thin Ice",
            "The Well", "Frost", "Glass", "The Buyer", "Thaw",
        )
        val pages = listOf(3, 19, 36, 52, 68, 85, 101, 117, 134, 150, 167, 184, 196, 212, 229)
        ContentsSheet(
            toc = titles.zip(pages) { t, p -> TocEntry(t, "", p) },
            current = 11,
            counted = true,
            bookmarks = listOf(Bookmark("", 142, "The Glazier", 0), Bookmark("", 190, "Frost", 0)),
            onPick = {},
            onClose = {},
        )
    }
}

/** The board's page: "Chapter 12", "Frost" and its first paragraphs, 26 px from the edges. */
@Composable
private fun BoardPage(padding: PaddingValues) {
    val serif = TextStyle(
        fontFamily = FontFamily.Serif,
        color = Color(0xFFD8D4CC),
        fontSize = 17.sp,
        // 17 × 1.65: Compose rounds a line's height up (28.05 → 29), the browser doesn't.
        lineHeight = 28.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        textMotion = TextMotion.Animated,
    )
    // Robolectric's Noto Serif (Android's static build) sits its glyphs lower in the line than the board's web font does;
    // the sample is nudged up to match (the real page is epub.js, styled with the board's numbers).
    Column(
        Modifier.fillMaxSize().padding(padding).clipToBounds().padding(horizontal = 26.dp).offset(y = (-3).dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            SText("Chapter 12", Modifier.offset(y = 3.dp).padding(bottom = 8.dp), size = 11.sp, weight = 800, tracking = 0.14f, uppercase = true, color = Color(0xFF8C8C8C))
            BasicText("Frost", Modifier.offset(y = 2.dp).padding(bottom = 4.dp), style = serif.copy(fontSize = 28.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF0ECE4)))
        }
        BasicText(
            "The frost came early that year, the way it always did in the stories, quietly and all at once. Ilse woke to find the orchard silvered, every branch drawn in white, and the glass fruit ringing faintly whenever the wind moved through the rows.",
            style = serif,
        )
        BasicText(
            "She dressed in the dark and went out with the lantern, counting trees the way her father had taught her: by touch first, then by sound, and only last by sight. Forty-one, forty-two.",
            style = serif.copy(textIndent = TextIndent(firstLine = 1.4.em)),
        )
        BasicText(
            "At the end of the eleventh row she stopped. There should have been a gap there, a stump where lightning had taken the old pear two summers ago. Instead there was a tree, young and straight, its bark still green.",
            style = serif.copy(textIndent = TextIndent(firstLine = 1.4.em)),
        )
        BasicText("“You weren’t here,” she said, and felt foolish, and said it again anyway.", style = serif.copy(textIndent = TextIndent(firstLine = 1.4.em)))
    }
}
