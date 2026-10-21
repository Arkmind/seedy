package io.github.arkmind.seedy.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.arkmind.seedy.core.admin.FolderStorage
import io.github.arkmind.seedy.core.admin.HubAlert
import io.github.arkmind.seedy.core.admin.LibraryStorage
import io.github.arkmind.seedy.core.admin.SystemStorage
import io.github.arkmind.seedy.core.admin.diskNotice
import io.github.arkmind.seedy.core.admin.disks
import io.github.arkmind.seedy.core.admin.HubCount
import io.github.arkmind.seedy.core.admin.HubGroup
import io.github.arkmind.seedy.core.admin.HubLink
import io.github.arkmind.seedy.core.admin.NowPlaying
import io.github.arkmind.seedy.core.admin.PlayKind
import io.github.arkmind.seedy.core.admin.RunningTask
import io.github.arkmind.seedy.core.admin.ServerHubActions
import io.github.arkmind.seedy.core.admin.ServerHubState
import io.github.arkmind.seedy.core.admin.Tone
import io.github.arkmind.seedy.mobile.admin.ServerHubScreen
import io.github.arkmind.seedy.ui.components.LocalOverlayHost
import io.github.arkmind.seedy.ui.components.OverlayHost
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

/**
 * Draws a server board's screen at its full height (390 px wide at 1×) and saves it as
 * src/test/screenshots/[name].png, next to the board's render in .design/out/[name].png.
 */
internal fun ComposeContentTestRule.adminShot(name: String, content: @Composable () -> Unit) {
    setContent {
        SeedyTheme {
            CompositionLocalProvider(LocalViewer provides Viewer(name = "Arky", requests = true)) {
                val overlay = remember { OverlayHost() }
                CompositionLocalProvider(LocalOverlayHost provides overlay) {
                    Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
                        content()
                        overlay.content?.invoke()
                    }
                }
            }
        }
    }
    waitForIdle()
    onRoot().captureRoboImage("src/test/screenshots/$name.png")
}

/** Board "Android · Server". */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AdminShots {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w390dp-h3100dp-mdpi")
    fun server() = compose.adminShot("AndroidAdmin") {
        ServerHubScreen(AdminSamples.hub, object : ServerHubActions {}, onBack = {}, open = {})
    }
}

object AdminSamples {
    private const val TiB = 1_099_511_627_776L
    private const val GiB = 1_073_741_824L

    private fun folder(path: String, mount: String, used: Long, total: Long, type: String = "Fixed") = FolderStorage(path, total - used, used, type, mount)

    /** The board's four disks: server data, the libraries, a RAM disk for transcodes and the web client. */
    val storage: SystemStorage = run {
        val config = { path: String -> folder(path, "/config", (10.9 * TiB).toLong(), (12.5 * TiB).toLong()) }
        val media = { path: String -> folder(path, "/media", (25.6 * TiB).toLong(), 40 * TiB) }
        SystemStorage(
            programDataFolder = config("/config/data"),
            internalMetadataFolder = config("/config/data/metadata"),
            cacheFolder = config("/config/cache"),
            imageCacheFolder = config("/config/cache/images"),
            logFolder = config("/config/log"),
            transcodingTempFolder = folder("/config/cache/transcodes", "/config/cache/transcodes", (2.6 * GiB).toLong() + 1, 8 * GiB, "Ram"),
            webFolder = folder("/usr/share/jellyfin/web", "/", 242 * GiB, (1.9 * TiB).toLong()),
            libraries = listOf("Movies", "Shows", "Music", "Books").map { LibraryStorage(it.lowercase(), it, listOf(media("/media/" + it.lowercase()))) },
        )
    }

    private fun link(page: String, name: String, sub: String, tone: Tone = Tone.Normal, badge: Int = 0) = HubLink(page, name, sub, tone, badge)

    val hub = ServerHubState(
        lede = "seedy-server · Jellyfin 12.0 · Linux x64 · up 6 days",
        alerts = listOf(
            HubAlert("plugin", "File Transformation won’t load", "Built for Jellyfin 10.11; 12.0 needs a rebuilt version.", "Open plugins", danger = true, open = "plugins"),
            disks(storage).first().let { d -> HubAlert("disk", "Server data is 87% full", diskNotice(d), "Storage", danger = false, open = "storage", tone = d.level) },
            HubAlert("updates", "2 plugin updates are ready", "Open Subtitles and Intro Skipper", "Review", danger = false, open = "plugins"),
        ),
        playing = listOf(
            NowPlaying("1", "Northbound · S3 E2", "Arky", 0, null, "Living room TV", PlayKind.Direct, "4K HEVC HDR10 · EAC3 5.1 · 21 Mbps", 0.35f, null),
            NowPlaying("2", "Glass Harbor · S1 E6", "Sam", 1, null, "iPhone", PlayKind.Transcode, "HEVC to H.264 on Intel QSV, 8 Mbps. The device can’t play HEVC.", 0.76f, null),
            NowPlaying("3", "Low Tide Club · S2 E2", "Léa", 2, null, "Firefox", PlayKind.Stream, "Audio TrueHD to AAC stereo · video copied", 0.12f, null),
        ),
        tasks = listOf(RunningTask("a", "Scan media library", "42%", 0.42f), RunningTask("b", "Generate trickplay images", "Queued", 0f)),
        counts = listOf(HubCount("248", "Movies"), HubCount("61", "Shows"), HubCount("2,914", "Episodes")),
        disks = disks(storage),
        groups = listOf(
            HubGroup("Server", listOf(
                link("settings", "Settings", "Name, paths, sign-in and logging"),
                link("users", "Users", "5 users · 3 signed in today"),
                link("libraries", "Libraries", "4 libraries · scanning Shows"),
                link("playback", "Playback", "Intel Quick Sync · 1 transcode"),
                link("networking", "Networking", "Remote access on · HTTPS"),
                link("livetv", "Live TV", "2 tuners · 48 channels"),
            )),
            HubGroup("Seedy", listOf(
                link("branding", "Branding & playback", "Accent colour, logo and player defaults"),
                link("homescreen", "Home screen", "Default rows for new users"),
                link("requests", "Requests", "Seerr link · 1 waiting for approval", Tone.Pending),
                link("playlog", "Play log", "Who watched what"),
            )),
            HubGroup("Activity", listOf(
                link("devices", "Devices", "9 devices"),
                link("activity", "Activity log", "1 failed sign-in today", Tone.Danger),
            )),
            HubGroup("Advanced", listOf(
                link("plugins", "Plugins", "1 won’t load", Tone.Danger, badge = 2),
                link("tasks", "Scheduled tasks", "1 running"),
                link("backups", "Backups", "Last: today at 03:00"),
                link("logs", "Logs", "Server and FFmpeg logs"),
                link("apikeys", "API keys", "3 keys"),
            )),
        ),
        signedInAs = "Signed in as Arky, administrator",
    )
}
