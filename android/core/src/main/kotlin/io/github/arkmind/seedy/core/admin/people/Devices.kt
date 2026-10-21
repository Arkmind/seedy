package io.github.arkmind.seedy.core.admin.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.admin.AdminDevice
import io.github.arkmind.seedy.core.admin.AdminHttp
import io.github.arkmind.seedy.core.admin.AdminUser
import io.github.arkmind.seedy.core.admin.ItemsResult
import io.github.arkmind.seedy.core.admin.errorText
import io.github.arkmind.seedy.core.admin.realDate
import io.github.arkmind.seedy.core.admin.sameId
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jellyfin.sdk.api.client.extensions.imageApi
import java.time.Clock

/*
 * Server › Devices (web/src/screens/server/Devices.tsx and its board): every app that has signed in, with who used it
 * last and what it's playing; renaming one, removing one (it's signed out) or signing out every other device.
 */

enum class DeviceKind { Tv, Phone, Computer }

private val TvWords = Regex("\\btv\\b|android ?tv|fire ?tv|roku|shield|chromecast|apple ?tv|tvos|tizen|webos|kodi|xbox|playstation|living room|bedroom")
private val PhoneWords = Regex("iphone|ipad|ipod|android|mobile|phone|tablet|swiftfin|findroid|\\bios\\b")
private val Browser = Regex("\\b(Chrome|Chromium|Firefox|Safari|Edge|Opera|Vivaldi|Brave)\\b", RegexOption.IGNORE_CASE)

/** The boards draw three kinds of device; guess which from the app and the device's own name. */
fun kindOf(d: AdminDevice): DeviceKind {
    val s = "${d.name.orEmpty()} ${d.customName.orEmpty()} ${d.appName.orEmpty()}".lowercase()
    return when {
        TvWords.containsMatchIn(s) -> DeviceKind.Tv
        PhoneWords.containsMatchIn(s) -> DeviceKind.Phone
        else -> DeviceKind.Computer
    }
}

/** "Seedy 0.1 · Chrome", "Swiftfin 1.6". */
fun appLabel(d: AdminDevice): String {
    val version = d.appVersion?.split('.')?.take(2)?.joinToString(".").orEmpty()
    val app = listOf(d.appName.orEmpty(), version).filter { it.isNotEmpty() }.joinToString(" ")
    val browser = Browser.find(d.name.orEmpty())?.groupValues?.get(1)
    return if (browser != null && !d.appName.orEmpty().lowercase().contains(browser.lowercase())) "$app · $browser" else app
}

fun deviceName(d: AdminDevice): String = d.customName?.trim()?.takeIf { it.isNotEmpty() } ?: d.name?.takeIf { it.isNotEmpty() } ?: t("Unknown device")

data class DeviceRow(
    val id: String,
    val name: String,
    /** The name the app reports (renaming back to it clears the custom name). */
    val reported: String,
    val kind: DeviceKind,
    val app: String,
    val userId: String?,
    val user: String,
    val userIndex: Int,
    val userImage: String?,
    /** "Now", "2 min ago", "Yesterday, 19:40", "14 Oct". */
    val last: String,
    /** "Playing Northbound", "Paused Northbound"; null when idle. */
    val playing: String? = null,
    /** The device this app runs on. */
    val mine: Boolean = false,
)

data class DevicesState(
    /** Null while loading. */
    val devices: List<DeviceRow>? = null,
    /** The people to filter by (id to name), in the server's order. */
    val people: List<Pair<String, String>> = emptyList(),
    val error: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

/** Devices matching [who] (a user id, or null for everyone) and [query] (name, app or person). */
fun DevicesState.filtered(who: String?, query: String): List<DeviceRow> {
    val q = query.trim().lowercase()
    return devices.orEmpty().filter { d ->
        (who == null || sameId(d.userId, who)) && (q.isEmpty() || listOf(d.name, d.reported, d.app, d.user).any { it.lowercase().contains(q) })
    }
}

interface DevicesActions {
    fun reload() {}
    fun rename(id: String, name: String) {}
    fun remove(id: String) {}
    fun signOutOthers() {}
    fun messageShown() {}
}

class DevicesViewModel(
    private val session: UserSession,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel(), DevicesActions {
    private val http = AdminHttp(session.api)
    private val _state = MutableStateFlow(DevicesState())
    val state: StateFlow<DevicesState> = _state.asStateFlow()
    private val mine = session.api.deviceInfo.id

    private var devices: List<AdminDevice>? = null
    private var users: List<AdminUser> = emptyList()
    private var sessions: List<PeopleSession> = emptyList()

    fun load() {
        viewModelScope.launch {
            runCatching { http.get<ItemsResult<AdminDevice>>("/Devices").items }
                .onSuccess { devices = it; _state.update { s -> s.copy(error = null) } }
                .onFailure { e -> if (e is CancellationException) throw e; _state.update { it.copy(error = errorText(e)) } }
            publish()
        }
        viewModelScope.launch {
            runCatching { http.get<List<AdminUser>>("/Users") }.onSuccess { users = it; publish() }
        }
        loadSessions()
    }

    private fun loadSessions() {
        viewModelScope.launch {
            runCatching { http.get<List<PeopleSession>>("/Sessions", mapOf("activeWithinSeconds" to 960)) }.onSuccess { sessions = it; publish() }
        }
    }

    /** What's playing refreshes every 15 s while the page is open. */
    suspend fun live() {
        while (true) {
            delay(15_000)
            loadSessions()
        }
    }

    override fun reload() = load()

    private fun playing(s: PeopleSession?): String? {
        val item = s?.nowPlayingItem ?: return null
        val title = if (item.type == "Episode" && !item.seriesName.isNullOrEmpty()) item.seriesName else item.name.orEmpty()
        return if (s.playState?.isPaused == true) t("Paused {title}", "title" to title) else t("Playing {title}", "title" to title)
    }

    private fun publish() {
        val list = devices
        val bySession = mutableMapOf<String, PeopleSession>()
        for (s in sessions) {
            val id = s.deviceId ?: continue
            if (id !in bySession || s.nowPlayingItem != null) bySession[id] = s
        }
        _state.update { st ->
            st.copy(
                people = users.map { it.id to it.name },
                devices = list?.let { all ->
                    // Jellyfin lists a device once per person who signed in on it; show each once, with its last user.
                    val latest = all.groupBy { it.id }.map { (_, l) -> l.maxBy { it.dateLastActivity.orEmpty() } }
                    latest.map { d ->
                        val ui = users.indexOfFirst { sameId(it.id, d.lastUserId) }
                        val user = users.getOrNull(ui)
                        val play = playing(bySession[d.id])
                        DeviceRow(
                            id = d.id,
                            name = deviceName(d),
                            reported = d.name.orEmpty(),
                            kind = kindOf(d),
                            app = appLabel(d),
                            userId = d.lastUserId,
                            user = user?.name ?: d.lastUserName.orEmpty(),
                            userIndex = ui.coerceAtLeast(0),
                            userImage = user?.primaryImageTag?.let { tag -> uuidOf(user.id)?.let { session.api.imageApi.getUserImageUrl(userId = it, tag = tag) } },
                            last = if (play != null) t("Now") else lastActive(realDate(d.dateLastActivity), clock),
                            playing = play,
                            mine = d.id == mine,
                        ) to d.dateLastActivity.orEmpty()
                    }.sortedWith(compareByDescending<Pair<DeviceRow, String>> { if (it.first.playing != null) 1 else 0 }.thenByDescending { it.second }).map { it.first }
                },
            )
        }
    }

    private fun say(text: String) = _state.update { it.copy(message = text) }
    override fun messageShown() = _state.update { it.copy(message = null) }

    override fun rename(id: String, name: String) {
        val row = _state.value.devices?.firstOrNull { it.id == id } ?: return
        // The app's own name back means no custom name.
        val custom = if (name.trim() == row.reported) "" else name.trim()
        viewModelScope.launch {
            runCatching { http.send("/Devices/Options", buildJsonObject { put("CustomName", custom) }, mapOf("id" to id)) }
                .onSuccess {
                    devices = devices?.map { if (it.id == id) it.copy(customName = custom) else it }
                    publish()
                    say(if (custom.isNotEmpty()) t("Renamed to {name}", "name" to custom) else t("{name} uses its own name again", "name" to row.reported))
                }
                .onFailure { if (it is CancellationException) throw it; say(t("Couldn’t rename the device.")) }
        }
    }

    override fun remove(id: String) {
        val row = _state.value.devices?.firstOrNull { it.id == id } ?: return
        if (row.mine) return say(t("You’re using this device. To sign it out, use Sign out in the account menu."))
        viewModelScope.launch {
            runCatching { deleteDevices(http, listOf(id)) }
                .onSuccess { say(t("{name} removed", "name" to row.name)); load() }
                .onFailure { if (it is CancellationException) throw it; say(t("Couldn’t remove that device.")) }
        }
    }

    override fun signOutOthers() {
        val others = _state.value.devices.orEmpty().filter { !it.mine }
        if (others.isEmpty()) return say(t("No other devices are signed in"))
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { deleteDevices(http, others.map { it.id }) }
                .onSuccess { say(tn(others.size, "{n} device signed out", "{n} devices signed out")) }
                .onFailure { if (it is CancellationException) throw it; say(t("Couldn’t sign the other devices out.")) }
            _state.update { it.copy(busy = false) }
            load()
        }
    }
}

/** The confirm text before signing out every other device. */
fun signOutOthersText(state: DevicesState): String {
    val others = state.devices.orEmpty().filter { !it.mine }
    val watching = others.count { it.playing != null }
    val n = others.size
    val first = when (watching) {
        0 -> tn(n, "{n} device is signed out.", "{n} devices are signed out.")
        1 -> tn(n, "{n} device is signed out, and playback stops on the one in use right now.", "{n} devices are signed out, and playback stops on the one in use right now.")
        else -> tn(n, "{n} device is signed out, and playback stops on the {watching} in use right now.", "{n} devices are signed out, and playback stops on the {watching} in use right now.", "watching" to watching)
    }
    return first + " " + t("This one stays signed in. People sign in again to keep watching.")
}
