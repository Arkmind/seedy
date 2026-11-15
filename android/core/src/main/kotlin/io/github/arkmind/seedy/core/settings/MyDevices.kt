package io.github.arkmind.seedy.core.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.admin.AdminDevice
import io.github.arkmind.seedy.core.admin.AdminHttp
import io.github.arkmind.seedy.core.admin.ItemsResult
import io.github.arkmind.seedy.core.admin.atLabel
import io.github.arkmind.seedy.core.admin.dayDiff
import io.github.arkmind.seedy.core.admin.hhmm
import io.github.arkmind.seedy.core.admin.majorMinor
import io.github.arkmind.seedy.core.admin.people.DeviceKind
import io.github.arkmind.seedy.core.admin.people.appLabel
import io.github.arkmind.seedy.core.admin.people.deleteDevices
import io.github.arkmind.seedy.core.admin.people.deviceName
import io.github.arkmind.seedy.core.admin.people.kindOf
import io.github.arkmind.seedy.core.admin.people.lastActive
import io.github.arkmind.seedy.core.admin.realDate
import io.github.arkmind.seedy.core.admin.sameId
import io.github.arkmind.seedy.core.admin.shortDate
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.seedy.MyDevice
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import kotlin.math.max
import kotlin.math.roundToLong

/** One place the person is signed in, as Settings › Profile › Signed in on shows it (AndroidSettingsDevices board). */
data class MyDeviceRow(
    val id: String,
    val name: String,
    val kind: DeviceKind,
    /** The phone this app runs on. */
    val current: Boolean = false,
    /** "Seedy for Android TV 1.4 · Home network"; the address, when shown, is [ip]. */
    val app: String,
    val ip: String? = null,
    /** Playing (not paused) right now: the green tag. */
    val playingNow: Boolean = false,
    /** "Playing Northbound · S3 E2", "Paused Northbound". */
    val playing: String? = null,
    /** "Active now · Signed in 28 Aug", "2 h ago · Signed in 2 Nov". */
    val status: String,
    val isNew: Boolean = false,
    /** Under a new device seen from outside the home network; its Sign out turns red. */
    val warning: String? = null,
)

/** The person's devices, this phone first; null while loading. */
data class MyDevicesState(
    val devices: List<MyDeviceRow>? = null,
    val error: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    /** "Signs out all 4 other devices, including the TV playing Northbound. You stay signed in on this phone." */
    val everywhere: String = "",
)

interface MyDevicesActions {
    fun reload() {}
    fun signOut(id: String) {}
    fun signOutOthers() {}
    fun messageShown() {}
}

/** The devices Jellyfin lists with this profile as their last user (only admins may list and remove devices). */
suspend fun myDevices(http: AdminHttp, userId: String): List<AdminDevice> =
    http.get<ItemsResult<AdminDevice>>("/Devices").items
        .filter { sameId(it.lastUserId, userId) }
        .distinctBy { it.id }
        .sortedByDescending { it.dateLastActivity.orEmpty() }

private val TvWords = Regex("\\btv\\b|android ?tv|fire ?tv|roku|shield|chromecast|apple ?tv|tvos|tizen|webos|kodi|xbox|playstation|living room|bedroom")
private val PhoneWords = Regex("iphone|ipod|android|mobile|phone|pixel|galaxy|swiftfin|findroid|\\bios\\b")
private val Browser = Regex("\\b(Chrome|Chromium|Firefox|Safari|Edge|Opera|Vivaldi|Brave)\\b", RegexOption.IGNORE_CASE)

/** Seedy's web app reports itself as plain "Seedy". */
private const val WebApp = "Seedy"

/** The board draws four kinds of device; guess which from its names and app. */
fun deviceKind(d: MyDevice): DeviceKind {
    val s = "${d.name} ${d.reportedName} ${d.appName}".lowercase()
    return when {
        "ipad" in s || "tablet" in s || "galaxy tab" in s -> DeviceKind.Tablet
        TvWords.containsMatchIn(s) -> DeviceKind.Tv
        PhoneWords.containsMatchIn(s) -> DeviceKind.Phone
        else -> DeviceKind.Computer
    }
}

/** "Chrome · Seedy web · Home network", "Seedy for Android TV 1.4 · Home network": the browser only when the name doesn't say it. */
fun deviceAppLine(d: MyDevice): String {
    val app = if (d.appName == WebApp) t("Seedy web") else listOf(d.appName, majorMinor(d.appVersion)).filter { it.isNotEmpty() }.joinToString(" ")
    val browser = Browser.find(d.reportedName)?.groupValues?.get(1)?.takeIf { b -> !"${d.name} ${d.appName}".lowercase().contains(b.lowercase()) }
    val network = when (d.network) {
        "home" -> t("Home network")
        "away" -> t("Away")
        else -> null
    }
    return listOfNotNull(browser, app.takeIf { it.isNotEmpty() }, network).joinToString(" · ")
}

/** "Active now", "12 min ago", "2 h ago", "Yesterday, 23:12", "3 days ago", "4 Jul". */
fun lastUsed(d: MyDevice, clock: Clock): String {
    if (d.active) return t("Active now")
    val at = realDate(d.lastActiveAt) ?: return t("Not used yet")
    val mins = ((clock.millis() - at.toEpochMilli()) / 60_000.0).roundToLong()
    val days = dayDiff(at, clock)
    return when {
        mins < 60 -> t("{n} min ago", "n" to max(1L, mins))
        days <= 0L -> t("{n} h ago", "n" to (mins / 60.0).roundToLong())
        days == 1L -> t("Yesterday, {time}", "time" to hhmm(at, clock))
        days < 30 -> t("{n} days ago", "n" to days)
        else -> shortDate(at, clock)
    }
}

/** "Signed in 12 Sep", or for a new device "First seen yesterday". */
fun signedInLine(d: MyDevice, clock: Clock): String {
    val first = realDate(d.firstSeenAt)
    if (d.isNew && first != null) {
        val days = dayDiff(first, clock)
        return when {
            days <= 0L -> t("First seen today")
            days == 1L -> t("First seen yesterday")
            else -> t("First seen {n} days ago", "n" to days)
        }
    }
    return realDate(d.signedInAt)?.let { t("Signed in {date}", "date" to shortDate(it, clock)) }.orEmpty()
}

/** "Playing Northbound · S3 E2", "Paused Northbound". */
fun playingLine(d: MyDevice): String? {
    val p = d.playing ?: return null
    val tag = when {
        p.episode == null -> ""
        p.season == 0 -> t("Special {n}", "n" to p.episode)
        p.season != null -> t("S{s} E{e}", "s" to p.season, "e" to p.episode)
        else -> "E${p.episode}"
    }
    val what = if (tag.isEmpty()) p.title else "${p.title} · $tag"
    return if (p.paused) t("Paused {title}", "title" to what) else t("Playing {title}", "title" to what)
}

fun myDeviceRow(d: MyDevice, clock: Clock): MyDeviceRow {
    val flagged = d.isNew && d.network == "away"
    return MyDeviceRow(
        id = d.id,
        name = d.name.ifEmpty { t("Unknown device") },
        kind = deviceKind(d),
        current = d.current,
        app = deviceAppLine(d),
        // The board shows only a new device's address, so you can tell whether it's yours.
        ip = d.ip?.takeIf { d.isNew },
        playingNow = d.playing?.paused == false,
        playing = playingLine(d),
        status = listOf(lastUsed(d, clock), signedInLine(d, clock)).filter { it.isNotEmpty() }.joinToString(" · "),
        isNew = d.isNew,
        warning = realDate(d.firstSeenAt)?.takeIf { flagged }?.let {
            t("First seen {when}, from outside your home network. Not you? Sign it out, then change your password.", "when" to atLabel(it, clock))
        },
    )
}

private fun theKind(k: DeviceKind) = when (k) {
    DeviceKind.Tv -> t("the TV")
    DeviceKind.Phone -> t("the phone")
    DeviceKind.Tablet -> t("the tablet")
    DeviceKind.Computer -> t("the computer")
}

/** What "Sign out everywhere else" does, naming what plays. */
fun everywhereText(others: List<MyDevice>): String {
    val n = others.size
    val playing = others.filter { it.playing?.paused == false }
    return when (playing.size) {
        0 -> tn(n, "Signs out your other device. You stay signed in on this phone.", "Signs out all {n} other devices. You stay signed in on this phone.")
        1 -> tn(
            n, "Signs out your other device, {device} playing {title}. You stay signed in on this phone.",
            "Signs out all {n} other devices, including {device} playing {title}. You stay signed in on this phone.",
            "device" to theKind(deviceKind(playing[0])), "title" to playing[0].playing!!.title,
        )
        else -> t("Signs out all {n} other devices, including the {playing} playing right now. You stay signed in on this phone.", "n" to n, "playing" to playing.size)
    }
}

/**
 * Settings › Profile › Signed in on: where this profile is signed in and signing those devices out (playback there
 * stops, its token stops working). This phone stays: Sign out in Settings does that. Seedy answers for everyone; on
 * plain Jellyfin only admins can list devices, from Jellyfin's own list.
 */
class MyDevicesViewModel(private val session: UserSession, private val clock: Clock = Clock.systemDefaultZone()) : ViewModel(), MyDevicesActions {
    private val http = AdminHttp(session.api)
    private val mine = session.api.deviceInfo.id
    private val me = session.userId.toString()
    private val _state = MutableStateFlow(MyDevicesState())
    val state: StateFlow<MyDevicesState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val seedy = session.seedyApi
            runCatching {
                if (seedy != null) {
                    val list = seedy.myDevices()
                    list.map { myDeviceRow(it, clock) } to everywhereText(list.filter { !it.current })
                } else {
                    val list = myDevices(http, me).map(::adminRow)
                    list to everywhereText(list.filter { !it.current }.map { MyDevice(id = it.id) })
                }
            }
                .onSuccess { (rows, text) -> _state.update { it.copy(devices = rows.sortedByDescending { r -> r.current }, everywhere = text, error = null) } }
                .onFailure { e -> if (e is CancellationException) throw e; _state.update { it.copy(error = t("Couldn’t load your devices.")) } }
        }
    }

    private fun adminRow(d: AdminDevice) = MyDeviceRow(
        id = d.id, name = deviceName(d), kind = kindOf(d), current = d.id == mine,
        app = appLabel(d), status = lastActive(realDate(d.dateLastActivity), clock),
    )

    override fun reload() = load()

    override fun signOut(id: String) {
        val row = _state.value.devices?.firstOrNull { it.id == id } ?: return
        if (row.current) return
        viewModelScope.launch {
            runCatching { session.seedyApi?.signOutDevice(id) ?: deleteDevices(http, listOf(id)) }
                .onSuccess { _state.update { s -> s.copy(message = t("{name} signed out", "name" to row.name)) } }
                .onFailure { if (it is CancellationException) throw it; _state.update { s -> s.copy(message = t("Couldn’t sign that device out.")) } }
            load()
        }
    }

    override fun signOutOthers() {
        val others = _state.value.devices.orEmpty().filter { !it.current }
        if (others.isEmpty()) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { session.seedyApi?.signOutOtherDevices() ?: deleteDevices(http, others.map { it.id }) }
                .onSuccess { n -> _state.update { s -> s.copy(message = tn(n, "{n} device signed out", "{n} devices signed out")) } }
                .onFailure { if (it is CancellationException) throw it; _state.update { s -> s.copy(message = t("Couldn’t sign the other devices out.")) } }
            _state.update { it.copy(busy = false) }
            load()
        }
    }

    override fun messageShown() = _state.update { it.copy(message = null) }
}
