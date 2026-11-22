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
import io.github.arkmind.seedy.core.wrapped.WrappedArrival
import io.github.arkmind.seedy.core.wrapped.WrappedBinge
import io.github.arkmind.seedy.core.wrapped.WrappedClock
import io.github.arkmind.seedy.core.wrapped.WrappedGenre
import io.github.arkmind.seedy.core.wrapped.WrappedMovie
import io.github.arkmind.seedy.core.wrapped.WrappedOddest
import io.github.arkmind.seedy.core.wrapped.WrappedPersonality
import io.github.arkmind.seedy.core.wrapped.WrappedRequests
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
import org.junit.After
import org.junit.Rule
import io.github.arkmind.seedy.core.i18n.I18n
import io.github.arkmind.seedy.core.i18n.Lang
import org.robolectric.RuntimeEnvironment
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
        stories = listOf("cover", "hours", "lover", "genres", "shows", "movie", "binge", "clock", "requests", "oddest", "personality", "summary"),
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
        movie = WrappedMovie("6", "Paper Lanterns", 4, 448 * 60_000L, listOf("2026-03-07", "2026-07-19", "2026-10-11", "2026-11-02"), genres = listOf("romance", "drama")),
        movies = 41,
        moviesPlayed = 141,
        moviesRewatched = 23,
        movieMs = 264 * 3_600_000L,
        showsWatched = 38,
        showsFinished = 16,
        episodes = 612,
        showMs = 1_020 * 3_600_000L,
        binge = WrappedBinge("1", "Northbound", 9, season = 2, lastSeason = 2, lastEpisode = 7, start = "2026-03-14T14:02", end = "2026-03-14T22:47", minutes = listOf(52, 49, 55, 51, 58, 50, 53, 56, 61)),
        still = WrappedStill(23, 23),
        clock = WrappedClock(
            hours = listOf(415, 213, 82, 20, 0, 0, 0, 10, 31, 41, 50, 70, 121, 142, 122, 112, 153, 203, 323, 464, 617, 788, 929, 1000).map { it * 10_000L },
            peak = "23:40", kind = "night", pct = 61, day = 6,
        ),
        personality = WrappedPersonality("sprinter", listOf(WrappedStat("seasonDays", 4.2), WrappedStat("introsSkipped", 312.0, 7.0 * h), WrappedStat("gapSeconds", 9.0))),
        musicMs = 312 * h, books = 6,
        requests = WrappedRequests(27, 21, 4, 2, WrappedArrival("Glass Harbor", season = 2, ms = 14 * 60_000L), WrappedGenre("Sci-Fi & Fantasy", "scifi", 40)),
        oddest = WrappedOddest(18_123, "movie", "Mole Men of Venus", 1958, 3.9, 27, 1, 92),
    )

    /** Long names and big numbers, to check every text fits its space. */
    private val stressYear: Wrapped = year.copy(
        watchMs = 12_345 * h,
        prevWatchMs = 1_234 * h,
        rank = 12,
        genres = listOf(
            WrappedGenre("Science Fiction & Fantasy", null, 34), WrappedGenre("Telenovela Romántica Histórica", null, 22),
            WrappedGenre("Documentary", "documentary", 15), WrappedGenre("Comedy", "comedy", 12), WrappedGenre("Animation", "animation", 8),
        ),
        shows = listOf(
            WrappedTitle("1", "The Lord of the Rings: The Rings of Power", 1_234, 1_234 * h),
            WrappedTitle("2", "Enquêtes criminelles : le magazine des faits divers", 412, 345 * h),
            WrappedTitle("3", "Star Wars: The Clone Wars", 133, 99 * h),
            WrappedTitle("4", "Avatar: The Last Airbender", 61, 42 * h),
            WrappedTitle("5", "Les Brigades du Tigre et autres histoires de la Belle Époque", 22, 17 * h),
        ),
        seasonsFinished = 123,
        showsStarted = 45,
        movie = WrappedMovie(
            "6", "Dr. Strangelove or: How I Learned to Stop Worrying and Love the Bomb", 12, 1_234 * 60_000L,
            listOf("2026-01-07", "2026-03-19", "2026-05-11", "2026-07-02", "2026-09-02", "2026-11-02"), genres = listOf("comedy"),
        ),
        moviesPlayed = 1_234,
        moviesRewatched = 234,
        movieMs = 2_345 * h,
        showsWatched = 345,
        showsFinished = 123,
        episodes = 12_345,
        showMs = 9_999 * h,
        binge = WrappedBinge(
            "2", "Enquêtes criminelles : le magazine des faits divers", 24, season = 12, seasonTo = 14, lastSeason = 14, lastEpisode = 22,
            start = "2026-03-14T08:02", end = "2026-03-15T03:47", minutes = List(24) { 40 + it % 7 },
        ),
        still = WrappedStill(1_234, 1_000),
        personality = WrappedPersonality("regular", listOf(WrappedStat("streak", 123.0), WrappedStat("activeDays", 334.0), WrappedStat("introsSkipped", 12_345.0, 1_234.0 * h))),
        musicMs = 1_234 * h,
        books = 123,
        requests = WrappedRequests(
            1_234, 1_000, 200, 34, WrappedArrival("The Lord of the Rings: The Rings of Power", 1, 2, 3 * 86_400_000L),
            WrappedGenre("Telenovela Romántica Histórica", null, 40),
        ),
        oddest = WrappedOddest(1, "movie", "The Incredibly Strange Creatures Who Stopped Living and Became Mixed-Up Zombies", 1964, 3.2, 12_345, 23, 100),
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

    @After fun english() = I18n.load(RuntimeEnvironment.getApplication(), Lang.En)

    /** The stress year's story [key] in French. */
    private fun stress(key: String, name: String) {
        I18n.load(RuntimeEnvironment.getApplication(), Lang.Fr)
        shot(name) { TvWrappedScreen(stressYear, onClose = {}, start = stressYear.stories.indexOf(key), fixedProgress = .6f) }
    }

    @Test fun stressGenres() = stress("genres", "WrappedTVStressGenres")
    @Test fun stressShows() = stress("shows", "WrappedTVStressShows")
    @Test fun stressRequests() = stress("requests", "WrappedTVStressRequests")
    @Test fun stressOddest() = stress("oddest", "WrappedTVStressOddest")
    @Test fun stressPersonality() = stress("personality", "WrappedTVStressPersonality")

    @Test fun shows() = story(4, "WrappedTV")
    @Test fun cover() = story(0, "WrappedTVCover")
    @Test fun hours() = story(1, "WrappedTVHours")
    @Test fun lover() = story(2, "WrappedTVLover")
    @Test fun genres() = story(3, "WrappedTVGenres")
    @Test fun movie() = story(5, "WrappedTVMovie")
    @Test fun binge() = story(6, "WrappedTVBinge")
    @Test fun clock() = story(7, "WrappedTVNight")
    @Test fun requests() = story(8, "WrappedTVRequests")
    @Test fun oddest() = story(9, "WrappedTVOddest")
    @Test fun personality() = story(10, "WrappedTVPersonality")
    @Test fun summary() = story(11, "WrappedTVSummary")

    /** No board: December's card over the home rows, the phone banner at TV size. */
    @Test
    fun homeCard() = shot("TVHomeWrapped") {
        val state = HomeViewModel.State(
            loading = false,
            hasHero = false,
            rows = listOf(HomeViewModel.Row(HomeRow("continue", "continue"), "Continue watching", listOf(Samples.thePass, Samples.paperLanterns, Samples.glassHarbor))),
            wrapped = WrappedTeaser(2026, 4_620_960_000L, 12),
        )
        Box(Modifier.fillMaxSize()) {
            TvHomeScreen(state, onOpen = {})
            TvRail(TvPlace.entries, TvPlace.Home, onPlace = {}, onProfile = {}, onSettings = {})
        }
    }
}
