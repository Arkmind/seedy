package io.github.arkmind.seedy.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.arkmind.seedy.core.admin.people.DeviceKind
import io.github.arkmind.seedy.core.feature.HomeViewModel
import io.github.arkmind.seedy.core.remote.ClosedScreen
import io.github.arkmind.seedy.core.remote.NowOn
import io.github.arkmind.seedy.core.remote.RemoteScreen
import io.github.arkmind.seedy.core.remote.RemoteState
import io.github.arkmind.seedy.core.seedy.FeaturedEntry
import io.github.arkmind.seedy.core.seedy.HomeRow
import io.github.arkmind.seedy.mobile.home.AccountActions
import io.github.arkmind.seedy.mobile.home.HomeScreen
import io.github.arkmind.seedy.mobile.parts.Tab
import io.github.arkmind.seedy.mobile.parts.TabBar
import io.github.arkmind.seedy.mobile.parts.tabBarHeight
import io.github.arkmind.seedy.mobile.remote.PadMode
import io.github.arkmind.seedy.mobile.remote.PickerSheet
import io.github.arkmind.seedy.mobile.remote.RemoteActions
import io.github.arkmind.seedy.mobile.remote.RemotePage
import io.github.arkmind.seedy.mobile.remote.TypingUi
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SeedyTheme
import io.github.arkmind.seedy.ui.theme.Viewer
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

/** The phone as a remote (boards "Android · Control a screen", "Remote for a TV", "Remote · typing on the TV", "Remote for a computer"). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-mdpi", sdk = [35])
class RemoteShots {
    @get:Rule val compose = createComposeRule()

    private val tvNow = NowOn(
        item = Samples.thePass, title = "The Pass", heading = "Northbound · S3 E2", kicker = "Northbound · S3 E2",
        positionMs = (19 * 60 + 44) * 1000L, durationMs = 52 * 60_000L, paused = false,
    )
    private val macNow = NowOn(
        item = Samples.paperLanterns, title = "Paper Lanterns", heading = "Paper Lanterns", kicker = "Movie · 2025",
        positionMs = 3730_000L, durationMs = 6640_000L, paused = true, volume = 70,
    )
    private val tv = RemoteScreen("s1", "tv", "Living room TV", DeviceKind.Tv, "Android TV", "Android TV", now = tvNow)
    private val mac = RemoteScreen("s2", "mac", "MacBook", DeviceKind.Computer, "Seedy web in Chrome", "Seedy web · Chrome", now = macNow, fullscreen = true)
    private val bedroom = ClosedScreen("bed", "Bedroom TV", DeviceKind.Tv, "Apple TV")

    private fun state(active: String) = RemoteState(screens = listOf(tv, mac), closed = listOf(bedroom), controlled = listOf("tv", "mac"), active = active)

    @Test
    fun picker() {
        val home = HomeViewModel.State(
            loading = false,
            featured = List(5) { FeaturedEntry(Samples.northbound, reason = "New episode Friday", play = Samples.thePass) },
            rows = listOf(HomeViewModel.Row(HomeRow("continue", "continue"), "Continue watching", listOf(Samples.thePass, Samples.paperLanterns, Samples.glassHarbor))),
        )
        compose.setContent {
            SeedyTheme {
                CompositionLocalProvider(LocalViewer provides Viewer(name = "Arky")) {
                    Box(Modifier.fillMaxSize()) {
                        HomeScreen(home, AccountActions({}, {}), onOpen = {}, onPlay = {}, onSearch = {}, onSeeAll = {}, bottomPadding = tabBarHeight(), onCast = {})
                        TabBar(Tab.entries, Tab.Home, {}, Modifier.align(Alignment.BottomCenter))
                        PickerSheet(
                            screens = listOf(tv, mac), closed = listOf(bedroom), picked = setOf("tv", "mac"), sync = false,
                            onToggle = {}, onSync = {}, onControl = {}, onDismiss = {}, now = 0, still = true,
                        )
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/AndroidRemotePicker.png")
    }

    @Test
    fun tvRemote() {
        compose.setContent { SeedyTheme { RemotePage(state("tv"), now = 0, mode = PadMode.Touchpad, typing = null, actions = RemoteActions(), still = true) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/AndroidRemoteTV.png")
    }

    /** No board: the pad as buttons. */
    @Test
    fun tvButtons() {
        compose.setContent { SeedyTheme { RemotePage(state("tv"), now = 0, mode = PadMode.Buttons, typing = null, actions = RemoteActions(), still = true) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/AndroidRemoteButtons.png")
    }

    @Test
    fun typing() {
        val harbour = BaseItemDto(id = UUID.nameUUIDFromBytes("Moon Harbour".toByteArray()), type = BaseItemKind.SERIES, name = "Moon Harbour", childCount = 2)
        val paperMoon = BaseItemDto(
            id = UUID.nameUUIDFromBytes("Under the Paper Moon".toByteArray()), type = BaseItemKind.MOVIE, name = "Under the Paper Moon",
            productionYear = 2024, runTimeTicks = 112 * 60 * 10_000_000L,
        )
        val st = state("tv").copy(screens = listOf(tv.copy(asking = "search", text = "moon", focusedId = harbour.id), mac))
        compose.setContent {
            SeedyTheme { RemotePage(st, now = 0, mode = PadMode.Touchpad, typing = TypingUi("moon", listOf(harbour, paperMoon), harbour.id), actions = RemoteActions(), still = true) }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/AndroidRemoteKeyboard.png")
    }

    @Test
    fun computer() {
        compose.setContent { SeedyTheme { RemotePage(state("mac"), now = 0, mode = PadMode.Touchpad, typing = null, actions = RemoteActions(), still = true) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/AndroidRemoteDesktop.png")
    }

    /** No board: nothing plays on the computer yet. */
    @Test
    fun computerIdle() {
        val st = state("mac").copy(screens = listOf(tv, mac.copy(now = null, fullscreen = false)))
        compose.setContent { SeedyTheme { RemotePage(st, now = 0, mode = PadMode.Touchpad, typing = null, actions = RemoteActions(), still = true) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/AndroidRemoteDesktopIdle.png")
    }
}
