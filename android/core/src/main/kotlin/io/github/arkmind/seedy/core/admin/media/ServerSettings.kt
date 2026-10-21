package io.github.arkmind.seedy.core.admin.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.admin.AdminHttp
import io.github.arkmind.seedy.core.admin.SystemInfo
import io.github.arkmind.seedy.core.admin.errorText
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.session.UserSession
import io.github.arkmind.seedy.core.settings.Choice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.FileInfo
import org.jellyfin.sdk.model.api.ImageFormat
import kotlin.math.roundToInt

/*
 * Server › Settings (board "Android · Server · Settings", web/src/screens/server/Settings.tsx): Jellyfin's General and
 * Branding pages in one place, and the expert settings on a page of their own. Unlike the web page, changes save as
 * they're made, one at a time, each on top of what the server has now; the paths need a restart and ask first.
 */

/** A name and a value the expert page edits as a list (path substitutions, cast receiver apps). */
data class Pair2(val a: String, val b: String)

/** The splash screen's sheet. */
data class SplashState(val enabled: Boolean = false, val custom: Boolean = false, val imageUrl: String? = null, val uploading: Boolean = false)

/** A folder picked for a path that needs a restart, waiting for an answer. */
data class PathChange(val key: String, val path: String)

data class ServerSettingsState(
    val loaded: Boolean = false,
    val loadError: String? = null,
    val serverName: String = "",
    /** The name apps see when none is set. */
    val serverNamePlaceholder: String = "",
    val signInMessage: String = "",
    /** Server language, Paths, Sign-in, Performance, Logging and sessions, Expert settings. */
    val groups: List<SettingsGroup> = emptyList(),
    val expert: List<SettingsGroup> = emptyList(),
    val pathSubs: List<Pair2> = emptyList(),
    val castApps: List<Pair2> = emptyList(),
    val splash: SplashState = SplashState(),
    val pathChange: PathChange? = null,
    val restarting: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val picker: FolderPicker? = null,
    val message: String? = null,
)

interface ServerSettingsActions : SettingActions, FolderPickerActions {
    fun retry() {}
    fun setServerName(name: String) {}
    fun setSignInMessage(text: String) {}
    fun savePath(restart: Boolean) {}
    fun cancelPath() {}
    fun setPairs(key: String, rows: List<Pair2>) {}
    fun setSplash(on: Boolean) {}
    fun uploadSplash(bytes: ByteArray, mime: String) {}
    fun resetSplash() {}
    fun messageShown() {}
}

private val ScanFanout = listOf(0, 1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64)
private val ActivityDays = listOf(7, 14, 30, 60, 90, 180, 365, 730, 1825, 3650)
private val LogDays = listOf(1, 2, 3, 5, 7, 14, 30, 60, 90, 180, 365)
private val IdleMinutes = listOf(0, 5, 10, 15, 30, 45, 60, 120, 240, 480, 1440)

private fun idleLabel(m: Int): String = when {
    m == 0 -> t("Never")
    m < 60 || m % 60 != 0 -> "$m min"
    else -> tn(m / 60, "{n} hour", "{n} hours")
}

private fun choices(values: List<Int>, current: Int, label: (Int) -> String): List<Choice<String>> =
    (if (current in values) values else (values + current).sorted()).map { Choice(it.toString(), label(it)) }

class ServerSettingsViewModel(private val session: UserSession) : ViewModel(), ServerSettingsActions {
    private val http = AdminHttp(session.api)
    private val _state = MutableStateFlow(ServerSettingsState())
    val state: StateFlow<ServerSettingsState> = _state.asStateFlow()
    private val browser = FolderBrowser(http, viewModelScope)

    private var cfg = JsonObject(emptyMap())
    private var branding = JsonObject(emptyMap())
    private var info: SystemInfo? = null
    private var langs: List<LocalizationOption> = emptyList()
    private var splashVersion = 0

    private val lock = Mutex()
    private var saves = 0
    /** Text being typed, saved once typing stops: "cfg:ServerName", "branding:LoginDisclaimer"… */
    private val typed = mutableMapOf<String, JsonElement>()
    private var timer: Job? = null
    /** Saves finish even after the page is closed. */
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        viewModelScope.launch { browser.state.collect { p -> _state.update { it.copy(picker = p) } } }
    }

    fun load() {
        viewModelScope.launch {
            val c = async { runCatching { http.getObject("/System/Configuration") } }
            val b = async { runCatching { http.getObject("/System/Configuration/branding") } }
            val i = async { runCatching { http.get<SystemInfo>("/System/Info") } }
            val l = async { runCatching { http.get<List<LocalizationOption>>("/Localization/Options") }.getOrNull().orEmpty() }
            val cr = c.await()
            val br = b.await()
            val ir = i.await()
            if (cr.isFailure || br.isFailure || ir.isFailure) {
                _state.update { it.copy(loadError = t("Couldn’t load the server settings.")) }
                return@launch
            }
            cfg = cr.getOrThrow()
            branding = br.getOrThrow()
            info = ir.getOrThrow()
            langs = l.await()
            _state.update { it.copy(loaded = true, loadError = null) }
            publish(fields = true)
        }
    }

    override fun retry() = load()

    private fun publish(fields: Boolean = false) {
        if (!_state.value.loaded) return
        val i = info
        val culture = cfg.str("UICulture") ?: "en-US"
        val langOptions = withCurrent(langs.map { Choice(it.value, it.name) }, culture)
        val cache = cfg.str("CachePath")?.takeIf { it.isNotEmpty() } ?: i?.cachePath.orEmpty()
        val metadata = cfg.str("MetadataPath")?.takeIf { it.isNotEmpty() } ?: i?.internalMetadataPath.orEmpty()
        val splashOn = branding.bool("SplashscreenEnabled")
        val custom = !branding.str("SplashscreenLocation").isNullOrEmpty()
        val fanout = cfg.int("LibraryScanFanoutConcurrency")
        val images = cfg.int("ParallelImageEncodingLimit")
        val activity = cfg.int("ActivityLogRetentionDays")
        val logs = cfg.int("LogFileRetentionDays")
        val idle = cfg.int("InactiveSessionThreshold")
        val slow = cfg.bool("EnableSlowResponseWarning")
        val slowMs = cfg.int("SlowResponseThresholdMs", 500)
        val refreshes = cfg.int("LibraryMetadataRefreshConcurrency")
        val subs = cfg.objects("PathSubstitutions").map { Pair2(it.str("From").orEmpty(), it.str("To").orEmpty()) }
        val cast = cfg.objects("CastReceiverApplications").map { Pair2(it.str("Name").orEmpty(), it.str("Id").orEmpty()) }

        fun pick(key: String, name: String, help: String?, values: List<Int>, current: Int, label: (Int) -> String): ChoiceSetting {
            val options = choices(values, current, label)
            return ChoiceSetting(key, name, help, label(current), current.toString(), options)
        }
        val days = { n: Int -> tn(n, "{n} day", "{n} days") }

        val expert = listOf(
            SettingsGroup(
                null,
                listOf(
                    TextSetting("CorsHosts", t("Allowed web origins"), t("Sites allowed to call this server from a browser. * allows all"), (typedText("cfg:CorsHosts") ?: cfg.strings("CorsHosts").joinToString(", ")), "*", mono = true),
                    LinkSetting("pathSubs", t("Path substitution"), t("Rewrite server paths for apps that read files directly, like Kodi"), if (subs.isEmpty()) t("Add a rule") else tn(subs.size, "{n} rule · Edit", "{n} rules · Edit")),
                    NumberSetting("LibraryMonitorDelay", t("Wait after a file changes"), t("Before folder watching scans it"), "${cfg.int("LibraryMonitorDelay", 60)} s", cfg.int("LibraryMonitorDelay", 60).toDouble(), 0.0, 3600.0, "s"),
                    TextSetting("SortRemoveWords", t("Ignore when sorting"), t("Leading words skipped in A–Z order"), (typedText("cfg:SortRemoveWords") ?: cfg.strings("SortRemoveWords").joinToString(", ")), "the, a, an"),
                    pick("LibraryMetadataRefreshConcurrency", t("Parallel metadata refreshes"), t("Items refreshed at once"), ScanFanout, refreshes) { if (it == 0) t("Auto") else tn(it, "{n} item", "{n} items") },
                    LinkSetting("castApps", t("Cast receiver apps"), t("Chromecast apps people can pick when casting"), if (cast.isEmpty()) t("Add an app") else cast.joinToString(", ") { it.a.ifEmpty { it.b } }),
                    SwitchSetting("EnableLegacyAuthorization", t("Legacy authorization"), t("Accept the old sign-in header some older apps still send"), cfg.bool("EnableLegacyAuthorization")),
                ),
            ),
            SettingsGroup(
                t("Custom CSS for Jellyfin's app"),
                listOf(TextSetting("CustomCss", "", null, typedText("branding:CustomCss") ?: branding.str("CustomCss").orEmpty(), t("/* No custom CSS */"), mono = true, lines = 7)),
                foot = t("Applies to Jellyfin's own web app at /web. Seedy has its own theme under Branding & playback."),
            ),
        )
        val groups = listOf(
            SettingsGroup(null, listOf(ChoiceSetting("UICulture", t("Server language"), t("For notifications, logs and generated names"), labelOf(langOptions, culture), culture, langOptions))),
            SettingsGroup(
                t("Paths"),
                listOf(
                    LinkSetting("CachePath", t("Cache"), t("Images and other files Jellyfin can rebuild"), cache),
                    LinkSetting("MetadataPath", t("Metadata"), t("Artwork and info downloaded for your library"), metadata),
                ),
                foot = t("Changes need a restart."),
            ),
            SettingsGroup(
                t("Sign-in"),
                listOf(
                    SwitchSetting("QuickConnectAvailable", t("Quick Connect"), t("Sign in on a TV with a code from a signed-in device"), cfg.bool("QuickConnectAvailable")),
                    LinkSetting("splash", t("Splash screen"), t("Image shown while apps load"), if (!splashOn) t("Off") else if (custom) t("Custom image") else t("Default")),
                ),
            ),
            SettingsGroup(
                t("Performance"),
                listOf(
                    pick("LibraryScanFanoutConcurrency", t("Parallel library scans"), t("Items a scan works on at once"), ScanFanout, fanout) { if (it == 0) t("Auto") else tn(it, "{n} item", "{n} items") },
                    pick("ParallelImageEncodingLimit", t("Parallel image encoding"), t("Image jobs at once"), ScanFanout, images) { if (it == 0) t("Auto") else tn(it, "{n} job", "{n} jobs") },
                ),
            ),
            SettingsGroup(
                t("Logging and sessions"),
                listOfNotNull(
                    // Jellyfin's clean-up tasks read 0 days as "delete everything", not "keep forever".
                    pick("ActivityLogRetentionDays", t("Keep activity log for"), t("Sign-ins, playback and changes"), ActivityDays, activity.coerceAtLeast(1), days),
                    pick("LogFileRetentionDays", t("Keep log files for"), t("Server log files on disk"), LogDays, logs.coerceAtLeast(1), days),
                    pick("InactiveSessionThreshold", t("Close idle sessions after"), null, IdleMinutes, idle, ::idleLabel),
                    SwitchSetting("AllowClientLogUpload", t("Let apps upload their logs"), t("Helps when debugging a device"), cfg.bool("AllowClientLogUpload")),
                    SwitchSetting("EnableSlowResponseWarning", t("Warn about slow responses"), t("Logs requests slower than {ms} ms", "ms" to slowMs), slow),
                    if (slow) NumberSetting("SlowResponseThresholdMs", t("Slow response threshold"), null, "$slowMs ms", slowMs.toDouble(), 50.0, 60000.0, "ms") else null,
                    SwitchSetting("EnableMetrics", t("Metrics endpoint"), t("Prometheus metrics for monitoring tools"), cfg.bool("EnableMetrics")),
                ),
            ),
            SettingsGroup(null, listOf(LinkSetting("expert", t("Expert settings"), t("Settings Jellyfin only exposes in its config file. Change them with care."), tn(expert[0].rows.size, "{n} setting", "{n} settings")))),
        )
        _state.update {
            it.copy(
                groups = groups,
                expert = expert,
                pathSubs = subs,
                castApps = cast,
                splash = it.splash.copy(enabled = splashOn, custom = custom, imageUrl = session.api.imageApi.getSplashscreenUrl(tag = "v$splashVersion", format = ImageFormat.JPG)),
                serverNamePlaceholder = i?.serverName.orEmpty(),
                serverName = if (fields) cfg.str("ServerName")?.takeIf { n -> n.isNotEmpty() } ?: i?.serverName.orEmpty() else it.serverName,
                signInMessage = if (fields) branding.str("LoginDisclaimer").orEmpty() else it.signInMessage,
            )
        }
    }

    /** What's typed and not saved yet, as typed. */
    private fun typedText(key: String): String? = rawTyped[key]
    private val rawTyped = mutableMapOf<String, String>()

    /* ── Saving: "cfg:Key" or "branding:Key", one at a time ── */

    private suspend fun write(patch: Map<String, JsonElement>) {
        val c = patch.filterKeys { it.startsWith("cfg:") }.mapKeys { it.key.removePrefix("cfg:") }
        val b = patch.filterKeys { it.startsWith("branding:") }.mapKeys { it.key.removePrefix("branding:") }
        if (c.isNotEmpty()) http.patchObject("/System/Configuration", c)
        if (b.isNotEmpty()) {
            val fresh = http.getObject("/System/Configuration/branding")
            val body = buildJsonObject {
                put("LoginDisclaimer", fresh["LoginDisclaimer"] ?: kotlinx.serialization.json.JsonNull)
                put("CustomCss", fresh["CustomCss"] ?: kotlinx.serialization.json.JsonNull)
                put("SplashscreenEnabled", fresh.bool("SplashscreenEnabled"))
                for ((k, v) in b) put(k, v)
            }
            http.send("/System/Configuration/Branding", body)
        }
    }

    private fun save(patch: Map<String, JsonElement>, then: () -> Unit = {}) {
        val n = ++saves
        _state.update { it.copy(saving = true, error = null) }
        saveScope.launch {
            val result = lock.withLock { runCatching { write(patch) } }
            if (n == saves || result.isFailure) {
                _state.update { it.copy(saving = false, error = if (result.isFailure) t("Couldn’t save. Check your connection and try again.") else null) }
            }
            if (result.isSuccess) then()
        }
    }

    /** Shows the change at once and saves it. */
    private fun put(key: String, value: JsonElement) {
        if (key.startsWith("branding:")) branding = branding.with(key.removePrefix("branding:"), value) else cfg = cfg.with(key.removePrefix("cfg:"), value)
        publish()
        save(mapOf(key to value))
    }

    /** Text saves once typing stops for a moment. */
    private fun type(key: String, raw: String, value: JsonElement) {
        typed[key] = value
        rawTyped[key] = raw
        if (key.startsWith("branding:")) branding = branding.with(key.removePrefix("branding:"), value) else cfg = cfg.with(key.removePrefix("cfg:"), value)
        _state.update { it.copy(saving = true, error = null) }
        timer?.cancel()
        timer = saveScope.launch {
            delay(800)
            flushTyped()
        }
    }

    private fun flushTyped() {
        timer = null
        if (typed.isEmpty()) return
        val batch = typed.toMap()
        typed.clear()
        rawTyped.clear()
        save(batch)
    }

    override fun onCleared() {
        timer?.cancel()
        flushTyped()
    }

    override fun setServerName(name: String) {
        _state.update { it.copy(serverName = name) }
        type("cfg:ServerName", name, json(name.trim()))
    }

    override fun setSignInMessage(text: String) {
        _state.update { it.copy(signInMessage = text) }
        type("branding:LoginDisclaimer", text, json(text.trim().ifEmpty { null }))
    }

    override fun setSwitch(key: String, on: Boolean) = put("cfg:$key", json(on))

    override fun setChoice(key: String, value: String) = when (key) {
        "UICulture" -> put("cfg:$key", json(value))
        else -> put("cfg:$key", json(value.toIntOrNull() ?: 0))
    }

    override fun setNumber(key: String, value: Double) = put("cfg:$key", json(value.roundToInt()))

    override fun setText(key: String, value: String) {
        val list = { s: String -> json(s.split(',').map { it.trim() }.filter { it.isNotEmpty() }) }
        when (key) {
            "CorsHosts", "SortRemoveWords" -> type("cfg:$key", value, list(value))
            "CustomCss" -> type("branding:CustomCss", value, json(value.ifEmpty { null }))
        }
        publish()
    }

    override fun setPairs(key: String, rows: List<Pair2>) {
        val clean = rows.map { Pair2(it.a.trim(), it.b.trim()) }
        when (key) {
            "pathSubs" -> put("cfg:PathSubstitutions", JsonArray(clean.filter { it.a.isNotEmpty() && it.b.isNotEmpty() }.map { buildJsonObject { put("From", it.a); put("To", it.b) } }))
            "castApps" -> put("cfg:CastReceiverApplications", JsonArray(clean.filter { it.b.isNotEmpty() }.map { buildJsonObject { put("Id", it.b); put("Name", it.a.ifEmpty { it.b }) } }))
        }
    }

    override fun open(key: String) {
        when (key) {
            "CachePath", "MetadataPath" -> {
                val start = (if (key == "CachePath") cfg.str("CachePath")?.takeIf { it.isNotEmpty() } ?: info?.cachePath else cfg.str("MetadataPath")?.takeIf { it.isNotEmpty() } ?: info?.internalMetadataPath).orEmpty()
                browser.open(if (key == "CachePath") t("Cache folder") else t("Metadata folder"), t("Use this folder"), start) { path ->
                    _state.update { it.copy(pathChange = PathChange(key, path)) }
                }
            }
        }
    }

    override fun browse(path: String) = browser.go(path)
    override fun pickFolder(path: String) = browser.pick(path)
    override fun closePicker() = browser.close()

    override fun cancelPath() = _state.update { it.copy(pathChange = null) }

    override fun savePath(restart: Boolean) {
        val change = _state.value.pathChange ?: return
        _state.update { it.copy(pathChange = null) }
        val value = if (change.key == "CachePath") json(change.path.trim().ifEmpty { null }) else json(change.path.trim())
        cfg = cfg.with(change.key, value)
        publish()
        save(mapOf("cfg:${change.key}" to value)) {
            if (restart) restartServer() else say(t("Saved. The server uses the new folder after a restart."))
        }
    }

    private fun restartServer() {
        viewModelScope.launch {
            _state.update { it.copy(restarting = true, message = t("Restarting the server…")) }
            val sent = runCatching { http.send("/System/Restart") }
            if (sent.isFailure) {
                _state.update { it.copy(restarting = false) }
                say(t("Couldn’t restart. {error}", "error" to errorText(sent.exceptionOrNull()!!)))
                return@launch
            }
            delay(4_000)
            repeat(90) {
                val back = runCatching { http.get<SystemInfo>("/System/Info") }.getOrNull()
                if (back != null && !back.isShuttingDown) {
                    _state.update { it.copy(restarting = false) }
                    say(t("The server is back"))
                    return@launch
                }
                delay(2_000)
            }
            _state.update { it.copy(restarting = false) }
        }
    }

    /* ── Splash screen ── */

    override fun setSplash(on: Boolean) = put("branding:SplashscreenEnabled", json(on))

    override fun uploadSplash(bytes: ByteArray, mime: String) {
        _state.update { it.copy(splash = it.splash.copy(uploading = true)) }
        viewModelScope.launch {
            val result = runCatching {
                // Jellyfin reads images as base64 text.
                session.api.imageApi.uploadCustomSplashscreen(FileInfo(android.util.Base64.encode(bytes, android.util.Base64.NO_WRAP), mime))
                branding = http.getObject("/System/Configuration/branding")
            }
            splashVersion++
            _state.update { it.copy(splash = it.splash.copy(uploading = false)) }
            publish()
            result.onSuccess { say(t("Splash screen image uploaded")) }
                .onFailure { if (it is CancellationException) throw it; say(t("Couldn’t upload it. {error}", "error" to errorText(it))) }
        }
    }

    override fun resetSplash() {
        viewModelScope.launch {
            val result = runCatching {
                session.api.imageApi.deleteCustomSplashscreen()
                branding = http.getObject("/System/Configuration/branding")
            }
            splashVersion++
            publish()
            result.onSuccess { say(t("The splash screen is back to the default")) }
                .onFailure { if (it is CancellationException) throw it; say(t("Couldn’t remove it. {error}", "error" to errorText(it))) }
        }
    }

    private fun say(text: String) = _state.update { it.copy(message = text) }
    override fun messageShown() = _state.update { it.copy(message = null) }
}
