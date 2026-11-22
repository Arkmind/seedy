package io.github.arkmind.seedy.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.arkmind.seedy.core.feature.HomeViewModel
import io.github.arkmind.seedy.core.seedy.FeaturedEntry
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
import io.github.arkmind.seedy.mobile.home.AccountActions
import io.github.arkmind.seedy.mobile.home.HomeScreen
import io.github.arkmind.seedy.mobile.parts.Tab
import io.github.arkmind.seedy.mobile.parts.TabBar
import io.github.arkmind.seedy.mobile.parts.tabBarHeight
import io.github.arkmind.seedy.mobile.wrapped.WrappedScreen
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SeedyTheme
import io.github.arkmind.seedy.ui.theme.Viewer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The boards' year, as the server would send it. */
object WrappedSamples {
    private const val H = 3_600_000L
    val year = Wrapped(
        year = 2026,
        stories = listOf("cover", "hours", "lover", "genres", "shows", "movie", "binge", "clock", "requests", "oddest", "personality", "summary"),
        watchMs = 4_620_960_000L, // 1,284 hours, 53 days
        plays = 2_310,
        prevWatchMs = 3_916_067_797L,
        rank = 2,
        viewers = 6,
        genres = listOf(
            WrappedGenre("Thriller", "thriller", 34), WrappedGenre("Drama", "drama", 22), WrappedGenre("Sci-Fi & Fantasy", "scifi", 15),
            WrappedGenre("Comedy", "comedy", 12), WrappedGenre("Documentary", "documentary", 8),
        ),
        otherPct = 9,
        shows = listOf(
            WrappedTitle("1", "Northbound", 62, 51 * H), WrappedTitle("2", "Glass Harbor", 40, 33 * H), WrappedTitle("3", "Coldwater Road", 28, 24 * H),
            WrappedTitle("4", "Static", 22, 17 * H), WrappedTitle("5", "Moon Harbour", 18, 14 * H),
        ),
        seasonsFinished = 11,
        showsStarted = 7,
        movie = WrappedMovie("6", "Paper Lanterns", 4, 448 * 60_000L, listOf("2026-03-07", "2026-07-19", "2026-10-11", "2026-11-02"), genres = listOf("romance", "drama")),
        movies = 41,
        moviesPlayed = 141,
        moviesRewatched = 23,
        movieMs = 264 * H,
        showsWatched = 38,
        showsFinished = 16,
        episodes = 612,
        showMs = 1_020 * H,
        binge = WrappedBinge("1", "Northbound", 9, season = 2, lastSeason = 2, lastEpisode = 7, start = "2026-03-14T14:02", end = "2026-03-14T22:47", minutes = listOf(52, 49, 55, 51, 58, 50, 53, 56, 61)),
        still = WrappedStill(23, 23),
        clock = WrappedClock(
            hours = listOf(415, 213, 82, 20, 0, 0, 0, 10, 31, 41, 50, 70, 121, 142, 122, 112, 153, 203, 323, 464, 617, 788, 929, 1000).map { it * 10_000L },
            peak = "23:40", kind = "night", pct = 61, day = 6,
        ),
        personality = WrappedPersonality(
            "sprinter",
            listOf(WrappedStat("seasonDays", 4.2), WrappedStat("introsSkipped", 312.0, 7.0 * H), WrappedStat("gapSeconds", 9.0)),
        ),
        musicMs = 312 * H,
        books = 6,
        requests = WrappedRequests(27, 21, 4, 2, WrappedArrival("Glass Harbor", season = 2, ms = 14 * 60_000L), WrappedGenre("Sci-Fi & Fantasy", "scifi", 40)),
        oddest = WrappedOddest(18_123, "movie", "Mole Men of Venus", 1958, 3.9, 27, 1, 92),
    )

    /** The movie lover board's numbers: 142 movies, 311 h, 23 rewatched; 9 shows, 120 episodes, 98 h. */
    val movieLover = year.copy(moviesPlayed = 142, movieMs = 311 * H, moviesRewatched = 23, showsWatched = 9, episodes = 120, showMs = 98 * H)
}

/** Boards "Seedy Wrapped · 1–12" (390 × 844) and the Home banner, with the boards' year and drawn pictures. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-mdpi", sdk = [35])
class WrappedShots {
    @get:Rule val compose = createComposeRule()

    private fun story(index: Int, name: String, progress: Float = .3f, year: Wrapped = WrappedSamples.year) {
        compose.setContent { SeedyTheme { WrappedScreen(year, onClose = {}, onShare = {}, onSave = {}, start = index, fixedProgress = progress) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun cover() = story(0, "WrappedIntro")
    @Test fun hours() = story(1, "WrappedHours")
    @Test fun lover() = story(2, "WrappedLover")
    @Test fun movieLover() = story(2, "WrappedLoverMovies", year = WrappedSamples.movieLover)
    @Test fun genres() = story(3, "WrappedGenres")
    @Test fun shows() = story(4, "WrappedShows")
    @Test fun movie() = story(5, "WrappedMovie")
    @Test fun binge() = story(6, "WrappedBinge")
    @Test fun clock() = story(7, "WrappedNight")
    @Test fun requests() = story(8, "WrappedRequests")
    @Test fun oddest() = story(9, "WrappedOddest")
    @Test fun personality() = story(10, "WrappedPersonality")

    /** No board: the completionist's longer name a size down, so "Completionist" stays on one line. */
    @Test
    fun completionist() = story(
        10, "WrappedPersonalityCompletionist",
        year = WrappedSamples.year.copy(personality = WrappedPersonality("completionist", listOf(WrappedStat("showsFinished", 16.0), WrappedStat("seasonsFinished", 11.0), WrappedStat("streak", 23.0)))),
    )
    @Test fun summary() = story(11, "WrappedSummary", progress = .6f)

    /** Board "Android · Home · Wrapped": December's banner under the hero. */
    @Test
    fun homeBanner() {
        val state = HomeViewModel.State(
            loading = false,
            featured = List(5) { FeaturedEntry(Samples.northbound, reason = "New episode Friday", play = Samples.thePass) },
            rows = listOf(HomeViewModel.Row(HomeRow("continue", "continue"), "Continue watching", listOf(Samples.thePass, Samples.paperLanterns, Samples.glassHarbor))),
            wrapped = WrappedTeaser(2026, 4_620_960_000L, 12),
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
        compose.onRoot().captureRoboImage("src/test/screenshots/AndroidHomeWrapped.png")
    }
}
