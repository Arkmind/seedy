package io.github.arkmind.seedy.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.arkmind.seedy.core.feature.HomeViewModel
import io.github.arkmind.seedy.core.seedy.HomeRow
import io.github.arkmind.seedy.core.wrapped.Wrapped
import io.github.arkmind.seedy.core.wrapped.WrappedBinge
import io.github.arkmind.seedy.core.wrapped.WrappedClock
import io.github.arkmind.seedy.core.wrapped.WrappedGenre
import io.github.arkmind.seedy.core.wrapped.WrappedMovie
import io.github.arkmind.seedy.core.wrapped.WrappedPersonality
import io.github.arkmind.seedy.core.wrapped.WrappedStat
import io.github.arkmind.seedy.core.wrapped.WrappedStill
import io.github.arkmind.seedy.core.wrapped.WrappedTeaser
import io.github.arkmind.seedy.core.wrapped.WrappedTitle
import io.github.arkmind.seedy.tv.home.TvHomeScreen
import io.github.arkmind.seedy.tv.shell.TvPlace
import io.github.arkmind.seedy.tv.shell.TvRail
import io.github.arkmind.seedy.tv.wrapped.TvWrappedScreen
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SeedyTheme
import io.github.arkmind.seedy.ui.theme.Viewer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Seedy Wrapped on the TV at 1920×1080 px: board "Seedy Wrapped · TV" (top shows), the other stories laid out alike. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi", sdk = [35])
class TvWrappedShots {
    @get:Rule val compose = createComposeRule()

    private val h = 3_600_000L
    private val year = Wrapped(
        year = 2026,
        stories = listOf("cover", "hours", "genres", "shows", "movie", "binge", "clock", "personality", "summary"),
        watchMs = 4_620_960_000L, plays = 2_310, prevWatchMs = 3_916_067_797L, rank = 2, viewers = 6,
        genres = listOf(
            WrappedGenre("Thriller", "thriller", 34), WrappedGenre("Drama", "drama", 22), WrappedGenre("Sci-Fi & Fantasy", "scifi", 15),
            WrappedGenre("Comedy", "comedy", 12), WrappedGenre("Documentary", "documentary", 8),
        ),
        otherPct = 9,
        shows = listOf(
            WrappedTitle("1", "Northbound", 62, 51 * h), WrappedTitle("2", "Glass Harbor", 40, 33 * h), WrappedTitle("3", "Coldwater Road", 28, 24 * h),
            WrappedTitle("4", "Static", 22, 17 * h), WrappedTitle("5", "Moon Harbour", 18, 14 * h),
        ),
        seasonsFinished = 11, showsStarted = 7,
        movie = WrappedMovie("6", "Paper Lanterns", 4, 448 * 60_000L, listOf("2026-03-07", "2026-07-19", "2026-10-11", "2026-11-02")),
        movies = 41,
        binge = WrappedBinge("1", "Northbound", 9, season = 2, lastSeason = 2, lastEpisode = 7, start = "2026-03-14T14:02", end = "2026-03-14T22:47", minutes = listOf(52, 49, 55, 51, 58, 50, 53, 56, 61)),
        still = WrappedStill(23, 23),
        clock = WrappedClock(
            hours = listOf(415, 213, 82, 20, 0, 0, 0, 10, 31, 41, 50, 70, 121, 142, 122, 112, 153, 203, 323, 464, 617, 788, 929, 1000).map { it * 10_000L },
            peak = "23:40", kind = "night", pct = 61, day = 6,
        ),
        personality = WrappedPersonality("sprinter", listOf(WrappedStat("seasonDays", 4.2), WrappedStat("introsSkipped", 312.0, 7.0 * h), WrappedStat("gapSeconds", 9.0))),
        musicMs = 312 * h, books = 6,
    )

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            val inputMode = LocalInputModeManager.current
            SideEffect { inputMode.requestInputMode(InputMode.Keyboard) }
            SeedyTheme { CompositionLocalProvider(LocalViewer provides Viewer(name = "Arky"), content = content) }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private fun story(index: Int, name: String) = shot(name) { TvWrappedScreen(year, onClose = {}, start = index, fixedProgress = .4f) }

    @Test fun shows() = story(3, "WrappedTV")
    @Test fun cover() = story(0, "WrappedTVCover")
    @Test fun hours() = story(1, "WrappedTVHours")
    @Test fun genres() = story(2, "WrappedTVGenres")
    @Test fun movie() = story(4, "WrappedTVMovie")
    @Test fun binge() = story(5, "WrappedTVBinge")
    @Test fun clock() = story(6, "WrappedTVNight")
    @Test fun personality() = story(7, "WrappedTVPersonality")
    @Test fun summary() = story(8, "WrappedTVSummary")

    /** No board: December's card over the home rows, the phone banner at TV size. */
    @Test
    fun homeCard() = shot("TVHomeWrapped") {
        val state = HomeViewModel.State(
            loading = false,
            hasHero = false,
            rows = listOf(HomeViewModel.Row(HomeRow("continue", "continue"), "Continue watching", listOf(Samples.thePass, Samples.paperLanterns, Samples.glassHarbor))),
            wrapped = WrappedTeaser(2026, 4_620_960_000L, 9),
        )
        Box(Modifier.fillMaxSize()) {
            TvHomeScreen(state, onOpen = {})
            TvRail(TvPlace.entries, TvPlace.Home, onPlace = {}, onProfile = {}, onSettings = {})
        }
    }
}
