package io.github.arkmind.seedy.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.arkmind.seedy.core.feature.HistoryActions
import io.github.arkmind.seedy.core.feature.HistoryState
import io.github.arkmind.seedy.core.history.HistoryEntry
import io.github.arkmind.seedy.mobile.history.HistoryNav
import io.github.arkmind.seedy.mobile.history.HistoryScreen
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.SeedyTheme
import io.github.arkmind.seedy.ui.theme.Viewer
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.util.TimeZone
import java.util.UUID

/** Viewing history (no phone board: the web History board's plays in the phone's settings kit). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HistoryShots {
    @get:Rule val compose = createComposeRule()

    @Before fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    private fun shot(name: String, state: HistoryState) {
        compose.setContent {
            SeedyTheme {
                CompositionLocalProvider(LocalViewer provides Viewer(name = "Arky", requests = true)) {
                    Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
                        HistoryScreen(state, object : HistoryActions {}, HistorySamples.today, HistoryNav())
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    @Config(qualifiers = "w390dp-h1500dp-mdpi")
    fun history() = shot("AndroidHistory", HistoryState(items = HistorySamples.plays, total = 40, status = "ready"))

    @Test
    @Config(qualifiers = "w390dp-h844dp-mdpi")
    fun empty() = shot("AndroidHistoryEmpty", HistoryState(status = "ready"))
}

/** The History board's plays: today, yesterday and last week, as Arky watched them. */
object HistorySamples {
    val today: LocalDate = LocalDate.of(2026, 10, 19)
    private const val MIN = 60L * 10_000_000L

    private fun id(name: String): UUID = UUID.nameUUIDFromBytes(name.toByteArray())

    private fun episode(show: String, s: Int, e: Int, name: String, minutes: Long) = BaseItemDto(
        id = id("$show $s $e"), type = BaseItemKind.EPISODE, name = name, seriesName = show, seriesId = id(show),
        parentIndexNumber = s, indexNumber = e, runTimeTicks = minutes * MIN,
    )

    private fun movie(name: String, year: Int, minutes: Long) = BaseItemDto(id = id(name), type = BaseItemKind.MOVIE, name = name, productionYear = year, runTimeTicks = minutes * MIN)

    private fun play(item: BaseItemDto, at: String, device: String, stoppedSeconds: Long? = null, hidden: Boolean = false) = HistoryEntry(
        id = id("play $at").toString(), itemId = item.id.toString(), titleId = (item.seriesId ?: item.id).toString(), item = item,
        startedAt = at, positionTicks = (stoppedSeconds ?: 0) * 10_000_000L, runtimeTicks = item.runTimeTicks ?: 0,
        finished = stoppedSeconds == null, deviceName = device, hidden = hidden,
    )

    val plays = listOf(
        play(episode("Northbound", 3, 2, "The Pass", 52), "2026-10-19T21:14:00Z", "Living room TV", 18 * 60 + 15),
        play(episode("Glass Harbor", 1, 6, "Undertow", 52), "2026-10-19T20:02:00Z", "iPhone", 39 * 60 + 30),
        play(movie("Paper Lanterns", 2025, 118), "2026-10-18T22:40:00Z", "Living room TV"),
        play(episode("Northbound", 3, 1, "Thaw", 50), "2026-10-18T21:30:00Z", "Living room TV"),
        play(episode("Low Tide Club", 2, 1, "Reunion", 44), "2026-10-18T18:05:00Z", "Laptop", 22 * 60 + 50),
        play(movie("Sixteen Winters", 2023, 104), "2026-10-17T15:20:00Z", "iPad"),
        play(movie("Ember & Ash", 2024, 97), "2026-10-16T22:05:00Z", "Living room TV"),
        play(movie("Long Way Down", 2025, 107), "2026-10-15T23:10:00Z", "Laptop", 35 * 60 + 2),
    )
}
