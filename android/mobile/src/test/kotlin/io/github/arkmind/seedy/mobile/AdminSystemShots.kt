package io.github.arkmind.seedy.mobile

import androidx.compose.ui.test.junit4.createComposeRule
import io.github.arkmind.seedy.core.admin.system.ActivityAction
import io.github.arkmind.seedy.core.admin.system.ActivityActionKind
import io.github.arkmind.seedy.core.admin.system.ActivityActions
import io.github.arkmind.seedy.core.admin.system.ActivityDay
import io.github.arkmind.seedy.core.admin.system.ActivityLevel
import io.github.arkmind.seedy.core.admin.system.ActivityRow
import io.github.arkmind.seedy.core.admin.system.ActivityState
import io.github.arkmind.seedy.core.admin.system.ActivityTile
import io.github.arkmind.seedy.core.admin.system.BackupPart
import io.github.arkmind.seedy.core.admin.system.BackupRow
import io.github.arkmind.seedy.core.admin.system.BackupsActions
import io.github.arkmind.seedy.core.admin.system.BackupsState
import io.github.arkmind.seedy.core.admin.system.Dot
import io.github.arkmind.seedy.core.admin.system.LastTone
import io.github.arkmind.seedy.core.admin.system.LogFileRow
import io.github.arkmind.seedy.core.admin.system.LogLine
import io.github.arkmind.seedy.core.admin.system.LogViewerState
import io.github.arkmind.seedy.core.admin.system.LogsActions
import io.github.arkmind.seedy.core.admin.system.LogsState
import io.github.arkmind.seedy.core.admin.system.PluginRow
import io.github.arkmind.seedy.core.admin.system.PluginsActions
import io.github.arkmind.seedy.core.admin.system.PluginsState
import io.github.arkmind.seedy.core.admin.system.RepoRow
import io.github.arkmind.seedy.core.admin.system.TaskGroupRows
import io.github.arkmind.seedy.core.admin.system.TaskRow
import io.github.arkmind.seedy.core.admin.system.TasksActions
import io.github.arkmind.seedy.core.admin.system.TasksState
import io.github.arkmind.seedy.mobile.admin.system.ActivityScreen
import io.github.arkmind.seedy.mobile.admin.system.BackupsScreen
import io.github.arkmind.seedy.mobile.admin.system.LogsScreen
import io.github.arkmind.seedy.mobile.admin.system.PluginsScreen
import io.github.arkmind.seedy.mobile.admin.system.TasksScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Boards "Android · Server · Scheduled tasks" and "… · Activity log", and the System pages built from the kit. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AdminSystemShots {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w390dp-h1110dp-mdpi")
    fun tasks() = compose.adminShot("AndroidAdminTasks") {
        TasksScreen(SystemSamples.tasks, object : TasksActions {}, onBack = {}, openLogs = { _, _ -> })
    }

    @Test
    @Config(qualifiers = "w390dp-h1130dp-mdpi")
    fun activity() = compose.adminShot("AndroidAdminActivity") {
        ActivityScreen(SystemSamples.activity, object : ActivityActions {}, onBack = {})
    }

    @Test
    @Config(qualifiers = "w390dp-h1010dp-mdpi")
    fun plugins() = compose.adminShot("AndroidAdminPlugins") {
        PluginsScreen(SystemSamples.plugins, object : PluginsActions {}, onBack = {}, openUrl = {}, openSeedy = {})
    }

    @Test
    @Config(qualifiers = "w390dp-h1290dp-mdpi")
    fun backups() = compose.adminShot("AndroidAdminBackups") {
        BackupsScreen(SystemSamples.backups, object : BackupsActions {}, onBack = {}, download = {}, copyPath = {})
    }

    @Test
    @Config(qualifiers = "w390dp-h640dp-mdpi")
    fun logs() = compose.adminShot("AndroidAdminLogs") {
        LogsScreen(SystemSamples.logs, object : LogsActions {}, onBack = {}, copy = {}, download = {})
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-mdpi")
    fun logViewer() = compose.adminShot("AndroidAdminLogViewer") {
        LogsScreen(SystemSamples.logs.copy(viewer = SystemSamples.viewer), object : LogsActions {}, onBack = {}, copy = {}, download = {})
    }
}

object SystemSamples {
    private fun task(name: String, desc: String, schedule: String, last: String, tone: LastTone = LastTone.Normal, running: Boolean = false, progress: Float? = null) =
        TaskRow(name, name, desc, schedule, last, tone, running = running, progress = progress, pct = progress?.let { "${(it * 100).toInt()}%" } ?: "")

    val tasks = TasksState(
        lede = "1 running · next: Clean transcode folder at 03:00. Tap a task to change its schedule.",
        groups = listOf(
            TaskGroupRows("Library", listOf(
                task("Scan media library", "Finds new, changed and removed files", "Every 12 hours", "Running since 20:41", LastTone.Running, running = true, progress = 0.42f),
                task("Generate trickplay images", "Seek previews for new videos", "Daily at 03:00", "Today, 22 min"),
                task("Media segment scan", "Intro, recap and credits markers", "After each scan", "Today, 1 min"),
                task("Extract chapter images", "Thumbnails for chapter lists", "Daily at 02:00", "Today, 4 min"),
            )),
            TaskGroupRows("Maintenance", listOf(
                task("Clean transcode folder", "Deletes leftover transcoding files", "Daily at 03:00", "Yesterday, 3 s"),
                task("Clean cache folder", "Deletes cache files older than a month", "Every 24 hours", "Today, 12 s"),
                task("Optimize database", "Compacts the library database", "Sundays at 04:00", "Failed on Sunday", LastTone.Failed),
            )),
            TaskGroupRows("Plugins", listOf(
                task("Check for plugin updates", "Looks for new versions in your repositories", "Every 24 hours", "2 updates found"),
                task("Seedy: trim play log", "Deletes plays older than the retention setting", "Daily at 04:30", "Today, 1 s"),
            )),
        ),
    )

    private fun ev(time: String, level: ActivityLevel, title: String, detail: String, tag: String, action: ActivityActionKind? = null) =
        ActivityRow(
            "$time$title", time, title, tag, detail, level,
            action?.let { ActivityAction(it, if (it == ActivityActionKind.Block) "Block address" else "Re-enable", "", "", false) },
        )

    val activity = ActivityState(
        lede = "Sign-ins, playback, changes and server events. Kept for 30 days.",
        tiles = listOf(ActivityTile("38", "Plays today"), ActivityTile("6", "Sign-ins today"), ActivityTile("3", "Failed sign-ins, 1 address", alert = true), ActivityTile("0", "Errors today")),
        personLabel = "Everyone",
        days = listOf(
            ActivityDay("19", "Today · Mon 19 Oct", listOf(
                ev("20:02", ActivityLevel.Info, "Sam started Glass Harbor S1 E6", "iPhone · transcoding", "Playback"),
                ev("19:48", ActivityLevel.Error, "Failed sign-in for “admin”", "From 203.0.113.7 · 3rd try in 10 minutes", "Sign-in", ActivityActionKind.Block),
                ev("19:15", ActivityLevel.Info, "Léa signed in", "Firefox on Windows · 192.168.1.23", "Sign-in"),
                ev("18:30", ActivityLevel.Ok, "Movies scan finished", "3 new items · 4 min 12 s", "System"),
                ev("17:02", ActivityLevel.Warn, "Kids was locked out", "3 wrong passwords on Bedroom Fire TV", "Sign-in", ActivityActionKind.Enable),
            )),
            ActivityDay("18", "Yesterday · Sun 18 Oct", listOf(
                ev("23:10", ActivityLevel.Info, "Open Subtitles updated to 21.0", "Takes effect after the next restart", "System"),
                ev("22:40", ActivityLevel.Info, "Arky finished Paper Lanterns", "Living room TV · direct play", "Playback"),
                ev("03:00", ActivityLevel.Ok, "Automatic backup created", "Database and metadata · 412 MB", "System"),
            )),
        ),
        canLoadMore = true,
    )

    val plugins = PluginsState(
        lede = "6 installed · 2 updates · 1 won't load on Jellyfin 12.0",
        rows = listOf(
            PluginRow("s", "Seedy", "S", true, "0.1.0", "This interface · Seedy repository", "Active", Dot.Ok),
            PluginRow("os", "Open Subtitles", "OS", false, "Update available", "Jellyfin", "Active", Dot.Ok, update = "21.0.0.0"),
            PluginRow("pr", "Playback Reporting", "PR", false, "Update available", "Jellyfin", "Active", Dot.Ok, update = "17.0.0.0"),
            PluginRow("cs", "Chapter Segments Provider", "CS", false, "Installed with 12.0", "Jellyfin", "Active", Dot.Ok),
            PluginRow("fa", "Fanart", "FA", false, "Up to date", "Jellyfin", "Active", Dot.Ok),
            PluginRow(
                "ft", "File Transformation", "FT", false, "Built for 10.11", "Third party", "Won’t load", Dot.Pending,
                note = "Plugins built for 10.11 don’t load on 12.0. Update it once its author ships a 12.0 build, or uninstall it.",
            ),
        ),
        updates = 2,
        restart = 0,
        repos = listOf(
            RepoRow(0, "Jellyfin Stable", "JS", "Jellyfin", "https://repo.jellyfin.org/files/plugin/manifest.json", true),
            RepoRow(1, "Seedy", "S", "Third party", "https://seedy.example/manifest.json", true),
        ),
    )

    val backups = BackupsState(
        parts = listOf(
            BackupPart("Database", "Database", "Users, watch history, settings, library index", true),
            BackupPart("Metadata", "Metadata", "Downloaded artwork and info", true),
            BackupPart("Subtitles", "Subtitles", "Downloaded and extracted subtitles", true),
            BackupPart("Trickplay", "Seek previews", "Trickplay images, often the largest part", false),
        ),
        supported = true,
        scheduleLoaded = true,
        autoOn = true,
        autoHelp = "Sunday at 04:00, with the parts checked above",
        keep = 4,
        rows = listOf(
            BackupRow("a", "a.zip", "19 Oct 2026, 14:02", "Manual", "Database, metadata, subtitles", "1.8 GB · Jellyfin 12.0", "", "", ""),
            BackupRow("b", "b.zip", "18 Oct 2026, 04:00", "Automatic", "Database, metadata, subtitles", "1.8 GB · Jellyfin 12.0", "", "", ""),
            BackupRow("c", "c.zip", "6 Sep 2026, 21:15", "Before upgrade", "Database, metadata, subtitles, seek previews", "9.4 GB · Jellyfin 10.11", "", "", ""),
        ),
    )

    val logs = LogsState(
        lede = "The server's log files, newest first. Kept for 3 days and slow-response warnings are set in Settings.",
        files = listOf(
            LogFileRow("log_20261019.log", "Today", "log_20261019.log · 2.4 MB", writing = true),
            LogFileRow("log_20261018.log", "Yesterday", "log_20261018.log · 3.1 MB", writing = false),
            LogFileRow("log_20261017.log", "Sat 17 Oct", "log_20261017.log · 2.9 MB", writing = false),
            LogFileRow("t", "Glass Harbor transcode", "FFmpeg.Transcode-2026-10-19_20-02.log · 184 KB", writing = true),
            LogFileRow("k", "Trickplay", "FFmpeg.Trickplay-2026-10-19_18-31.log · 48 KB", writing = false),
        ),
    )

    private fun ln(i: Int, time: String, level: String, text: String, hit: Boolean = false) = LogLine(i, time, level, text, hit)

    val viewer = LogViewerState(
        name = "log_20261019.log",
        label = "Today",
        sub = "log_20261019.log · 2.4 MB",
        live = true,
        following = true,
        loading = false,
        term = "transcod",
        count = "4 matches",
        matches = 4,
        lines = listOf(
            ln(0, "20:01:58", "INF", "Session started: Sam on iPhone (Seedy 0.1)"),
            ln(1, "20:02:03", "INF", "MediaEncoder: transcoding HEVC to H.264 with qsv, 8 Mbps", true),
            ln(2, "20:02:03", "INF", "TranscodeManager: started process 48211 for Glass Harbor S1 E6", true),
            ln(3, "20:02:04", "INF", "Playback start reported by Sam: Glass Harbor S1 E6"),
            ln(4, "20:04:10", "WRN", "Slow HTTP response 812 ms: GET /Items/9f3a/Images/Backdrop"),
            ln(5, "20:06:31", "INF", "TranscodeManager: throttling process 48211, 3 min ahead", true),
            ln(6, "20:07:02", "INF", "Scheduled task Scan Media Library: 42% complete"),
            ln(7, "20:09:44", "ERR", "PluginManager: failed to load File Transformation 2.4 (built for 10.11)"),
            ln(8, "20:09:44", "WRN", "PluginManager: File Transformation disabled until updated"),
            ln(9, "20:11:15", "INF", "TranscodeManager: deleting segments older than 720 s", true),
            ln(10, "20:12:00", "INF", "Authentication succeeded: Léa from 192.168.1.23"),
            ln(11, "20:12:31", "INF", "Session started: Léa on Laptop (Jellyfin Web 12.2)"),
        ),
    )
}
