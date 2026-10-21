package io.github.arkmind.seedy.core.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.format.Images
import io.github.arkmind.seedy.core.format.episodeTag
import io.github.arkmind.seedy.core.i18n.formatNumber
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.SessionInfoDto
import java.time.Clock
import java.time.Instant

/*
 * Server, the admin pages' hub (board "Android · Server", web/src/screens/server/Overview.tsx plus the admin nav):
 * what the server is doing now (playing, running tasks, library counts, notices) and a row for every server and
 * Seedy page, each saying where things stand. Sessions and tasks refresh every few seconds while it's open.
 */

enum class Tone { Normal, Ok, Pending, Danger, Accent }

/** A notice over the page: a plugin that won't load, a restart that's needed, plugin updates. */
data class HubAlert(val key: String, val title: String, val help: String, val action: String, val danger: Boolean, val open: String?)

/** Someone watching: what, who on which device, how it plays and how far in. */
data class NowPlaying(
    val sessionId: String,
    val title: String,
    val user: String,
    val userIndex: Int,
    val userImage: String?,
    val device: String,
    val kind: PlayKind,
    val detail: String,
    val progress: Float,
    val art: String?,
    val canMessage: Boolean = true,
    val canStop: Boolean = true,
)

data class RunningTask(val id: String, val name: String, val state: String, val progress: Float)

data class HubCount(val n: String, val label: String)

/** A row that opens a page ([page] is the admin route's page key). */
data class HubLink(val page: String, val name: String, val sub: String, val tone: Tone = Tone.Normal, val badge: Int = 0)

data class HubGroup(val title: String, val links: List<HubLink>)

data class ServerHubState(
    /** "seedy-server · Jellyfin 12.0 · Linux x64 · up 6 days"; null while loading. */
    val lede: String? = null,
    val offline: Boolean = false,
    val restarting: Boolean = false,
    val scanning: Boolean = false,
    val alerts: List<HubAlert> = emptyList(),
    /** Null while loading. */
    val playing: List<NowPlaying>? = null,
    val tasks: List<RunningTask>? = null,
    /** When nothing runs: when the last library scan finished ("today at 18:30"). */
    val lastScan: String? = null,
    val counts: List<HubCount>? = null,
    val groups: List<HubGroup> = emptyList(),
    val signedInAs: String = "",
    val message: String? = null,
)

interface ServerHubActions {
    fun scanAll() {}
    fun restart() {}
    fun shutdown() {}
    fun stopPlayback(sessionId: String) {}
    fun sendMessage(sessionId: String, text: String) {}
    fun messageShown() {}
}

class ServerHubViewModel(
    private val session: UserSession,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel(), ServerHubActions {
    private val http = AdminHttp(session.api)
    private val images = Images(session.api)
    private val _state = MutableStateFlow(ServerHubState(groups = groups(Summaries()), signedInAs = signedInAs()))
    val state: StateFlow<ServerHubState> = _state.asStateFlow()

    /** What the page has heard so far; each piece arrives on its own. */
    private data class Data(
        val info: SystemInfo? = null,
        val infoFailed: Boolean = false,
        val status: SeedyAdminStatus? = null,
        val sessions: List<SessionInfoDto>? = null,
        val tasks: List<TaskInfo>? = null,
        val counts: ItemCounts? = null,
        val users: List<AdminUser>? = null,
        val plugins: List<PluginInfo>? = null,
        val packages: List<PackageInfo>? = null,
    )

    private val data = MutableStateFlow(Data())
    private var summaries = Summaries()

    private fun <T> fetch(block: suspend () -> T, onFail: () -> Unit = {}, done: (T) -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onSuccess(done).onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; onFail() }
            publish()
        }
    }

    /** Everything once; then [live] keeps sessions and tasks current. */
    fun load() {
        fetch({ http.get<SystemInfo>("/System/Info") }, { data.update { it.copy(infoFailed = true) } }) { v -> data.update { it.copy(info = v, infoFailed = false) } }
        if (session.seedyApi != null) fetch({ http.get<SeedyAdminStatus>("/Seedy/Admin/Status", json = SeedyJson) }) { v -> data.update { it.copy(status = v) } }
        loadLive()
        fetch({ http.get<ItemCounts>("/Items/Counts") }) { v -> data.update { it.copy(counts = v) } }
        fetch({ http.get<List<AdminUser>>("/Users") }) { v -> data.update { it.copy(users = v) } }
        fetch({ http.get<List<PluginInfo>>("/Plugins") }) { v -> data.update { it.copy(plugins = v) } }
        fetch({ http.get<List<PackageInfo>>("/Packages", json = SeedyJson) }, { data.update { it.copy(packages = emptyList()) } }) { v -> data.update { it.copy(packages = v) } }
        loadSummaries()
    }

    private fun loadLive() {
        fetch({ session.api.sessionApi.getSessions(activeWithinSeconds = 960).content }) { v -> data.update { it.copy(sessions = v) } }
        fetch({ http.get<List<TaskInfo>>("/ScheduledTasks", mapOf("isHidden" to false)) }) { v -> data.update { it.copy(tasks = v) } }
    }

    /** Refreshes what's playing and running every 5 s (the server's details every 15 s) until cancelled. */
    suspend fun live() {
        var n = 0
        while (true) {
            delay(5_000)
            loadLive()
            if (++n % 3 == 0) fetch({ http.get<SystemInfo>("/System/Info") }, {}) { v -> data.update { it.copy(info = v, infoFailed = false) } }
        }
    }

    /** The rows' second lines: one light call each, any of them may fail (the row then says what the page is for). */
    private data class Summaries(
        val libraries: List<VirtualFolder>? = null,
        val encoding: JsonObject? = null,
        val network: JsonObject? = null,
        val tuners: Int? = null,
        val channels: Int? = null,
        val pendingRequests: Int? = null,
        val devices: Int? = null,
        val failedToday: Int? = null,
        val lastBackup: Instant? = null,
        val backups: Int? = null,
        val apiKeys: Int? = null,
    )

    private fun summary(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            publish()
        }
    }

    private fun loadSummaries() {
        summary { summaries = summaries.copy(libraries = http.get<List<VirtualFolder>>("/Library/VirtualFolders")) }
        summary { summaries = summaries.copy(encoding = http.getObject("/System/Configuration/encoding")) }
        summary { summaries = summaries.copy(network = http.getObject("/System/Configuration/network")) }
        summary {
            val live = http.getObject("/System/Configuration/livetv")
            summaries = summaries.copy(tuners = (live["TunerHosts"] as? JsonArray)?.size ?: 0)
            if ((summaries.tuners ?: 0) > 0) {
                val channels = http.get<ItemsResult<JsonObject>>("/LiveTv/Channels", mapOf("userId" to session.userId.toString(), "limit" to 0, "enableImages" to false, "enableUserData" to false))
                summaries = summaries.copy(channels = channels.totalRecordCount)
            }
        }
        if (session.seedyApi != null) {
            summary { summaries = summaries.copy(pendingRequests = http.get<RequestCounts>("/Seedy/Requests/Summary", json = SeedyJson).pending) }
        }
        summary { summaries = summaries.copy(devices = http.get<ItemsResult<AdminDevice>>("/Devices").totalRecordCount) }
        summary {
            val start = java.time.LocalDate.now(clock).atStartOfDay(clock.zone).toInstant()
            val entries = http.get<ItemsResult<ActivityEntry>>("/System/ActivityLog/Entries", mapOf("minDate" to start.toString(), "type" to "AuthenticationFailed", "limit" to 0))
            summaries = summaries.copy(failedToday = entries.totalRecordCount)
        }
        summary {
            val list = http.get<List<BackupManifest>>("/Backup")
            summaries = summaries.copy(backups = list.size, lastBackup = list.mapNotNull { realDate(it.dateCreated) }.maxOrNull())
        }
        summary { summaries = summaries.copy(apiKeys = http.get<ItemsResult<JsonObject>>("/Auth/Keys").totalRecordCount) }
    }

    private fun publish() {
        val d = data.value
        val s = summaries
        _state.update { st ->
            st.copy(
                lede = d.info?.let { lede(it, d) } ?: if (d.infoFailed) t("Server details unavailable") else null,
                offline = d.infoFailed,
                scanning = d.tasks?.any { it.key == "RefreshLibrary" && it.state == "Running" } == true,
                alerts = alerts(d),
                playing = d.sessions?.let { list -> list.filter { it.nowPlayingItem != null }.map { nowPlaying(it, d.users) } },
                tasks = d.tasks?.let(::running),
                lastScan = d.tasks?.firstOrNull { it.key == "RefreshLibrary" }?.lastExecutionResult?.endTimeUtc?.let(::realDate)?.let { atLabel(it, clock) },
                counts = d.counts?.let(::counts),
                groups = groups(s, d),
            )
        }
    }

    private fun lede(info: SystemInfo, d: Data): String {
        val since = d.status?.serverStartedAt?.let(::realDate) ?: d.tasks?.let(::serverStartFromTasks)
        return listOf(info.serverName, "Jellyfin ${majorMinor(info.version)}", platformLabel(info, d.status?.os), since?.let { uptime(it, clock) }.orEmpty())
            .filter { it.isNotEmpty() }.joinToString(" · ")
    }

    private fun alerts(d: Data): List<HubAlert> {
        val out = mutableListOf<HubAlert>()
        val version = d.info?.version.orEmpty()
        for (p in d.plugins.orEmpty().filter(::pluginWontLoad)) {
            out += HubAlert("plugin-${p.id}", t("{name} won’t load", "name" to p.name), wontLoadReason(p, d.packages, version), t("Open plugins"), danger = true, open = "plugins")
        }
        if (_state.value.restarting) {
            out += HubAlert("restarting", t("The server is restarting"), t("Seedy reconnects when it’s back."), t("Restarting…"), danger = false, open = null)
        } else if (d.info?.hasPendingRestart == true) {
            out += HubAlert("restart", t("Restart needed"), t("Some changes take effect after the server restarts."), t("Restart"), danger = false, open = "restart")
        }
        val packages = d.packages
        val plugins = d.plugins
        if (packages != null && plugins != null && version.isNotEmpty()) {
            val updates = pluginUpdates(plugins, packages, version)
            if (updates.isNotEmpty()) {
                out += HubAlert(
                    "updates",
                    tn(updates.size, "{n} plugin update is ready", "{n} plugin updates are ready"),
                    listing(updates.map { it.first.name }),
                    t("Review"),
                    danger = false,
                    open = "plugins",
                )
            }
        }
        return out
    }

    private fun nowPlaying(s: SessionInfoDto, users: List<AdminUser>?): NowPlaying {
        val item = s.nowPlayingItem!!
        val index = users?.indexOfFirst { sameId(it.id, s.userId.toString()) }?.coerceAtLeast(0) ?: 0
        val name = s.userName ?: users?.firstOrNull { sameId(it.id, s.userId.toString()) }?.name ?: t("Someone")
        return NowPlaying(
            sessionId = s.id.orEmpty(),
            title = sessionTitle(item),
            user = name,
            userIndex = index,
            userImage = s.userPrimaryImageTag?.let { images.user(s.userId, it) },
            device = s.deviceName.orEmpty(),
            kind = playKind(s),
            detail = (if (s.playState?.isPaused == true) t("Paused") + " · " else "") + playDetail(s),
            progress = playProgress(s),
            art = images.landscape(item, 240),
            canMessage = s.supportedCommands.orEmpty().contains(GeneralCommandType.DISPLAY_MESSAGE),
            canStop = s.supportsMediaControl,
        )
    }

    private fun running(tasks: List<TaskInfo>): List<RunningTask> =
        tasks.filter { it.state != "Idle" }.sortedByDescending { it.currentProgressPercentage ?: 0.0 }.map { task ->
            val p = task.currentProgressPercentage
            val label = when {
                task.state == "Cancelling" -> t("Stopping")
                p == null -> t("Starting")
                else -> percent(p)
            }
            RunningTask(task.id, taskName(task), label, ((p ?: 0.0) / 100).toFloat().coerceIn(0f, 1f))
        }

    private fun counts(c: ItemCounts): List<HubCount> {
        val kinds = listOf(
            c.movieCount to "Movies", c.seriesCount to "Shows", c.episodeCount to "Episodes", c.albumCount to "Albums",
            c.songCount to "Songs", c.musicVideoCount to "Music videos", c.bookCount to "Books",
        )
        val nonZero = kinds.filter { it.first > 0 }
        // The board's order (Movies, Shows, Episodes) whenever those are what the server has.
        val picked = (nonZero + kinds.filter { it !in nonZero }).take(3).sortedBy { kinds.indexOf(it) }
        return picked.map { HubCount(formatNumber(it.first), t(it.second)) }
    }

    private fun groups(s: Summaries, d: Data = Data()): List<HubGroup> {
        val users = d.users
        val signedInToday = users?.count { u -> realDate(u.lastActivityDate)?.let { dayDiff(it, clock) == 0L } == true }
        val transcodes = d.sessions?.count { it.nowPlayingItem != null && playKind(it) == PlayKind.Transcode } ?: 0
        val running = d.tasks?.count { it.state != "Idle" }
        val broken = d.plugins?.count(::pluginWontLoad) ?: 0
        val updates = if (d.plugins != null && d.packages != null && d.info != null) pluginUpdates(d.plugins, d.packages, d.info.version).size else 0
        val server = listOf(
            HubLink("settings", t("Settings"), t("Name, paths, sign-in and logging")),
            HubLink("users", t("Users"), if (users != null) listOf(tn(users.size, "{n} user", "{n} users"), signedInToday?.takeIf { it > 0 }?.let { t("{n} signed in today", "n" to it) }).joinNotNull() else t("Who can sign in and what they can do")),
            HubLink("libraries", t("Libraries"), s.libraries?.let { libs ->
                listOf(tn(libs.size, "{n} library", "{n} libraries"), libs.firstOrNull { it.refreshStatus == "Running" }?.let { t("scanning {name}", "name" to it.name) }).joinNotNull()
            } ?: t("Folders, metadata and scans")),
            HubLink("playback", t("Playback"), s.encoding?.let { e ->
                listOf(accelName(e["HardwareAccelerationType"]?.jsonPrimitive?.contentOrNull), transcodes.takeIf { it > 0 }?.let { tn(it, "{n} transcode", "{n} transcodes") }).joinNotNull()
            } ?: t("Transcoding and hardware acceleration")),
            HubLink("networking", t("Networking"), s.network?.let { n ->
                val remote = n["EnableRemoteAccess"]?.jsonPrimitive?.booleanOrNull != false
                val https = n["EnableHttps"]?.jsonPrimitive?.booleanOrNull == true
                listOf(if (remote) t("Remote access on") else t("Remote access off"), if (https) "HTTPS" else null).joinNotNull()
            } ?: t("Remote access, ports and addresses")),
            HubLink("livetv", t("Live TV"), when (val tuners = s.tuners) {
                null -> t("Tuners and guide data")
                0 -> t("No tuners yet")
                else -> listOf(tn(tuners, "{n} tuner", "{n} tuners"), s.channels?.let { tn(it, "{n} channel", "{n} channels") }).joinNotNull()
            }),
        )
        val seedy = if (session.seedyApi == null) null else listOf(
            HubLink("branding", t("Branding & playback"), t("Accent colour, logo and player defaults")),
            HubLink("homescreen", t("Home screen"), t("Default rows for new users")),
            requestsLink(d.status, s.pendingRequests),
            HubLink("playlog", t("Play log"), t("Who watched what")),
        )
        val activity = listOf(
            HubLink("devices", t("Devices"), s.devices?.let { tn(it, "{n} device", "{n} devices") } ?: t("Apps and browsers that signed in")),
            when (val failed = s.failedToday) {
                null, 0 -> HubLink("activity", t("Activity log"), t("Sign-ins, playback and changes"))
                else -> HubLink("activity", t("Activity log"), tn(failed, "{n} failed sign-in today", "{n} failed sign-ins today"), Tone.Danger)
            },
        )
        val advanced = listOf(
            if (broken > 0) HubLink("plugins", t("Plugins"), tn(broken, "{n} won’t load", "{n} won’t load"), Tone.Danger, badge = updates)
            else HubLink("plugins", t("Plugins"), d.plugins?.let { p -> if (updates > 0) tn(updates, "{n} update ready", "{n} updates ready") else tn(dedupePlugins(p).size, "{n} installed", "{n} installed") } ?: t("Installed plugins and the catalog"), badge = updates),
            HubLink("tasks", t("Scheduled tasks"), running?.let { if (it > 0) t("{n} running", "n" to it) else t("Nothing running") } ?: t("Scans, clean-ups and their schedules")),
            HubLink("backups", t("Backups"), when {
                s.lastBackup != null -> t("Last: {when}", "when" to dayAt(s.lastBackup, clock))
                s.backups == 0 -> t("No backups yet")
                else -> t("Database and metadata archives")
            }),
            HubLink("logs", t("Logs"), t("Server and FFmpeg logs")),
            HubLink("apikeys", t("API keys"), s.apiKeys?.let { tn(it, "{n} key", "{n} keys") } ?: t("Keys for other apps")),
        )
        return listOfNotNull(
            HubGroup(t("Server"), server),
            seedy?.let { HubGroup(t("Seedy"), it) },
            HubGroup(t("Activity"), activity),
            HubGroup(t("Advanced"), advanced),
        )
    }

    private fun requestsLink(status: SeedyAdminStatus?, pending: Int?): HubLink {
        val connected = status?.seerr?.state == "connected"
        return when {
            status == null -> HubLink("requests", t("Requests"), t("Seerr link and request rules"))
            !connected -> HubLink("requests", t("Requests"), t("Seerr isn’t linked"), Tone.Normal)
            pending != null && pending > 0 -> HubLink("requests", t("Requests"), t("Seerr link · {waiting}", "waiting" to tn(pending, "{n} waiting for approval", "{n} waiting for approval")), Tone.Pending)
            else -> HubLink("requests", t("Requests"), t("Seerr link · nothing waiting"))
        }
    }

    private fun signedInAs(): String {
        val name = session.user.name.orEmpty()
        return if (session.seedy?.isAdmin != false && session.user.policy?.isAdministrator != false) t("Signed in as {name}, administrator", "name" to name) else t("Signed in as {name}", "name" to name)
    }

    /* ── Actions ── */

    private fun say(text: String) = _state.update { it.copy(message = text) }

    override fun messageShown() = _state.update { it.copy(message = null) }

    override fun scanAll() {
        viewModelScope.launch {
            runCatching { http.send("/Library/Refresh") }
                .onSuccess { say(t("Scanning all libraries")); delay(800); loadLive() }
                .onFailure { say(t("Couldn’t start the scan. {error}", "error" to errorText(it))) }
        }
    }

    override fun restart() {
        viewModelScope.launch {
            _state.update { it.copy(restarting = true) }
            publish()
            val result = runCatching { http.send("/System/Restart") }
            if (result.isFailure) {
                _state.update { it.copy(restarting = false) }
                say(t("Couldn’t restart. {error}", "error" to errorText(result.exceptionOrNull()!!)))
                publish()
                return@launch
            }
            // Wait for the server to go away and come back (up to two minutes).
            delay(4_000)
            repeat(60) {
                val back = runCatching { http.get<SystemInfo>("/System/Info") }.getOrNull()
                if (back != null && !back.isShuttingDown) {
                    data.update { it.copy(info = back, infoFailed = false) }
                    _state.update { it.copy(restarting = false) }
                    say(t("The server is back"))
                    load()
                    return@launch
                }
                delay(2_000)
            }
            _state.update { it.copy(restarting = false) }
            publish()
        }
    }

    override fun shutdown() {
        viewModelScope.launch {
            runCatching { http.send("/System/Shutdown") }
                .onSuccess { say(t("The server is shutting down")) }
                .onFailure { say(t("Couldn’t shut down. {error}", "error" to errorText(it))) }
        }
    }

    override fun stopPlayback(sessionId: String) {
        viewModelScope.launch {
            val device = data.value.sessions?.firstOrNull { it.id == sessionId }?.deviceName ?: t("the device")
            runCatching { http.send("/Sessions/$sessionId/Playing/Stop") }
                .onSuccess { say(t("Stopped playback on {device}", "device" to device)); delay(800); loadLive() }
                .onFailure { say(t("Couldn’t stop it. {error}", "error" to errorText(it))) }
        }
    }

    override fun sendMessage(sessionId: String, text: String) {
        viewModelScope.launch {
            val device = data.value.sessions?.firstOrNull { it.id == sessionId }?.deviceName ?: ""
            val body = kotlinx.serialization.json.buildJsonObject {
                put("Header", kotlinx.serialization.json.JsonPrimitive(t("Message from {name}", "name" to session.user.name.orEmpty())))
                put("Text", kotlinx.serialization.json.JsonPrimitive(text.trim()))
                put("TimeoutMs", kotlinx.serialization.json.JsonPrimitive(15000))
            }
            runCatching { http.send("/Sessions/$sessionId/Message", body) }
                .onSuccess { say(t("Message sent to {name}", "name" to device)) }
                .onFailure { say(t("Couldn’t send it. {error}", "error" to errorText(it))) }
        }
    }
}

@kotlinx.serialization.Serializable
data class RequestCounts(val canViewAll: Boolean = false, val canManage: Boolean = false, val open: Int = 0, val pending: Int = 0)

/** GET /Backup (Jellyfin 12). */
@kotlinx.serialization.Serializable
data class BackupManifest(val serverVersion: String? = null, val backupEngineVersion: String? = null, val dateCreated: String? = null, val path: String = "", val options: JsonObject? = null)

/** "Northbound · S3 E2", or the title. */
fun sessionTitle(item: BaseItemDto): String = when (item.type) {
    BaseItemKind.EPISODE -> listOfNotNull(item.seriesName, item.episodeTag.takeIf { it.isNotEmpty() }).joinToString(" · ").ifEmpty { item.name.orEmpty() }
    BaseItemKind.AUDIO -> listOfNotNull(item.name, item.albumArtist).joinToString(" · ")
    else -> item.name.orEmpty()
}

/** The hardware acceleration Jellyfin uses, as the boards name it. */
fun accelName(type: String?): String = when (type?.lowercase()) {
    "qsv" -> "Intel Quick Sync"
    "nvenc" -> "NVIDIA NVENC"
    "amf" -> "AMD AMF"
    "vaapi" -> "VA-API"
    "videotoolbox" -> "Apple VideoToolbox"
    "rkmpp" -> "Rockchip RKMPP"
    "v4l2m2m" -> "V4L2"
    else -> t("CPU only")
}

/** "Open Subtitles", "Open Subtitles and Intro Skipper", "A, B and C". */
fun listing(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    else -> t("{list} and {last}", "list" to names.dropLast(1).joinToString(", "), "last" to names.last())
}

/** An error as a sentence for a toast. */
fun errorText(e: Throwable): String = when (e) {
    is AdminException -> when (e.status) {
        401, 403 -> t("The server refused it.")
        404 -> t("The server doesn’t know this.")
        else -> t("The server answered {status}.", "status" to e.status)
    }
    else -> t("The server didn’t answer.")
}

private fun List<String?>.joinNotNull(): String = filterNotNull().filter { it.isNotEmpty() }.joinToString(" · ")

/** Settings › Server's line: the server's name, what's playing and plugin updates ("seedy-server · 3 playing · 2 plugin updates"). */
suspend fun serverSummary(session: UserSession): String = coroutineScope {
    val http = AdminHttp(session.api)
    val info = async { runCatching { http.get<SystemInfo>("/System/Info") }.getOrNull() }
    val sessions = async { runCatching { session.api.sessionApi.getSessions(activeWithinSeconds = 960).content }.getOrNull() }
    val plugins = async { runCatching { http.get<List<PluginInfo>>("/Plugins") }.getOrNull() }
    val packages = async { runCatching { http.get<List<PackageInfo>>("/Packages", json = SeedyJson) }.getOrNull() }
    val version = info.await()?.version.orEmpty()
    val playing = sessions.await()?.count { it.nowPlayingItem != null } ?: 0
    val p = plugins.await()
    val k = packages.await()
    val updates = if (p != null && k != null && version.isNotEmpty()) pluginUpdates(p, k, version).size else 0
    listOfNotNull(
        info.await()?.serverName?.takeIf { it.isNotEmpty() } ?: session.server.name,
        playing.takeIf { it > 0 }?.let { t("{n} playing", "n" to it) },
        updates.takeIf { it > 0 }?.let { tn(it, "{n} plugin update", "{n} plugin updates") },
    ).joinToString(" · ")
}
