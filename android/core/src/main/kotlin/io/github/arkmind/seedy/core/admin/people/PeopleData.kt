package io.github.arkmind.seedy.core.admin.people

import io.github.arkmind.seedy.core.admin.AccessSchedule
import io.github.arkmind.seedy.core.admin.AdminDevice
import io.github.arkmind.seedy.core.admin.AdminHttp
import io.github.arkmind.seedy.core.admin.AdminUser
import io.github.arkmind.seedy.core.admin.ItemsResult
import io.github.arkmind.seedy.core.admin.JellyfinJson
import io.github.arkmind.seedy.core.admin.VirtualFolder
import io.github.arkmind.seedy.core.admin.dayDiff
import io.github.arkmind.seedy.core.admin.hhmm
import io.github.arkmind.seedy.core.admin.lastSeen
import io.github.arkmind.seedy.core.admin.mbps
import io.github.arkmind.seedy.core.admin.realDate
import io.github.arkmind.seedy.core.admin.sameId
import io.github.arkmind.seedy.core.admin.shortDate
import io.github.arkmind.seedy.core.format.fullDate
import io.github.arkmind.seedy.core.i18n.I18n
import io.github.arkmind.seedy.core.i18n.formatNumber
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.jellyfin.sdk.api.client.HttpMethod
import java.time.Clock
import java.time.Instant
import java.time.format.TextStyle
import java.util.UUID
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
 * What the people pages (Users, a user, Devices, API keys) read from Jellyfin and how they word it, as in
 * web/src/screens/server/Users.tsx, UserEdit.tsx, Devices.tsx and ApiKeys.tsx.
 */

/** GET /Sessions: who is signed in where, and what they're playing. */
@Serializable
data class PeopleSession(
    val id: String = "",
    val userId: String? = null,
    val userName: String? = null,
    val client: String? = null,
    val deviceName: String? = null,
    val deviceId: String? = null,
    val lastActivityDate: String? = null,
    val nowPlayingItem: PlayingItem? = null,
    val playState: PlayingState? = null,
)

@Serializable
data class PlayingItem(val name: String? = null, val type: String? = null, val seriesName: String? = null)

@Serializable
data class PlayingState(val isPaused: Boolean = false)

/** GET /Auth/Keys. */
@Serializable
data class ApiKeyInfo(
    val accessToken: String = "",
    val appName: String = "",
    val dateCreated: String? = null,
    val dateLastActivity: String? = null,
)

/** GET /Seedy/Admin/Seerr/Accounts (camelCase). */
@Serializable
data class SeerrAccount(val userId: String = "", val name: String = "", val seerrUserId: Int? = null, val seerrName: String? = null, val canRequest: Boolean = false)

/** GET /Seedy/Admin/PlayLog/Profiles (camelCase). */
@Serializable
data class PlayLogProfile(val userId: String = "", val name: String = "", val plays: Int = 0, val top10: Boolean = true, val history: Boolean = true)

/** GET /Localization/ParentalRatings: a rating's name and score (Jellyfin 12 nests the score as camelCase). */
data class ParentalRating(val name: String, val score: Int?, val subScore: Int?)

fun parseRatings(e: JsonElement): List<ParentalRating> = (e as? JsonArray).orEmpty().mapNotNull { r ->
    val o = r as? JsonObject ?: return@mapNotNull null
    val rs = o["RatingScore"] as? JsonObject
    ParentalRating(
        name = o.text("Name").orEmpty(),
        score = (rs?.get("score") as? JsonPrimitive)?.intOrNull ?: (o["Value"] as? JsonPrimitive)?.intOrNull,
        subScore = (rs?.get("subScore") as? JsonPrimitive)?.intOrNull,
    )
}

/** A user as Jellyfin sent it: the parts the lists read, and the whole JSON for saving. */
data class PersonJson(val user: AdminUser, val json: JsonObject) {
    val policy: JsonObject get() = json["Policy"] as? JsonObject ?: JsonObject(emptyMap())
}

fun person(json: JsonObject): PersonJson = PersonJson(JellyfinJson.decodeFromJsonElement(AdminUser.serializer(), json), json)

/** Everything the users list and a user's page need besides the user. */
data class PeopleData(
    val users: List<PersonJson> = emptyList(),
    val sessions: List<PeopleSession> = emptyList(),
    val devices: List<AdminDevice> = emptyList(),
    val folders: List<VirtualFolder> = emptyList(),
    val ratings: List<ParentalRating> = emptyList(),
)

/** As the web's loadUsersData: the users must load; the rest may fail and then reads as empty. */
suspend fun loadPeople(http: AdminHttp): PeopleData = coroutineScope {
    val users = async { http.get<List<JsonObject>>("/Users").map(::person) }
    val sessions = async { runCatching { http.get<List<PeopleSession>>("/Sessions", mapOf("activeWithinSeconds" to 960)) }.getOrDefault(emptyList()) }
    val devices = async { runCatching { http.get<ItemsResult<AdminDevice>>("/Devices").items }.getOrDefault(emptyList()) }
    val folders = async { runCatching { http.get<List<VirtualFolder>>("/Library/VirtualFolders") }.getOrDefault(emptyList()) }
    val ratings = async { runCatching { parseRatings(http.element(HttpMethod.GET, "/Localization/ParentalRatings")) }.getOrDefault(emptyList()) }
    PeopleData(users.await(), sessions.await(), devices.await(), folders.await(), ratings.await())
}

/* ── Reading a policy ── */

fun JsonObject.flag(key: String, default: Boolean = false): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: default
fun JsonObject.long(key: String, default: Long = 0): Long = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: default
fun JsonObject.int(key: String, default: Int = 0): Int = long(key, default.toLong()).toInt()
fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())

fun JsonObject.schedules(): List<AccessSchedule> =
    runCatching { JellyfinJson.decodeFromJsonElement(ListSerializer(AccessSchedule.serializer()), array("AccessSchedules")) }.getOrDefault(emptyList())

fun strings(list: List<String>): JsonArray = JsonArray(list.map(::JsonPrimitive))

/** Jellyfin's ids come without dashes; the SDK's image URLs want a UUID. */
fun uuidOf(id: String): UUID? = runCatching {
    val h = id.replace("-", "")
    UUID.fromString("${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20, 32)}")
}.getOrNull()

/* ── Wording ── */

/** "9:00", "20:30". */
fun hourLabel(h: Double): String = "${floor(h).toInt()}:${"%02d".format(((h % 1) * 60).roundToInt())}"

private val ShortDays = mapOf(
    "Everyday" to "", "Weekday" to "Weekdays", "Weekend" to "Weekends", "Sunday" to "Sun", "Monday" to "Mon", "Tuesday" to "Tue",
    "Wednesday" to "Wed", "Thursday" to "Thu", "Friday" to "Fri", "Saturday" to "Sat",
)

/** "Every day", "Weekdays", "Monday". */
fun dayLabel(day: String): String = t(Days.firstOrNull { it.first == day }?.second ?: day)

/** The schedule's days, as the web's ScheduleSheet lists them. */
val Days = listOf(
    "Everyday" to "Every day", "Weekday" to "Weekdays", "Weekend" to "Weekends", "Monday" to "Monday", "Tuesday" to "Tuesday",
    "Wednesday" to "Wednesday", "Thursday" to "Thursday", "Friday" to "Friday", "Saturday" to "Saturday", "Sunday" to "Sunday",
)

/**
 * The rating name for a maximum score ("TV-PG", "PG-13"): a named rating (TV ratings first) over a bare age, then
 * the US name metadata mostly carries when the server's country has none.
 */
fun ratingName(score: Int?, ratings: List<ParentalRating>): String? {
    if (score == null) return null
    val names = ratings.filter { it.score == score && it.name != "Unrated" && !Regex("^\\d+\\+?$").matches(it.name) }.map { it.name }
    val named = names.firstOrNull { it.startsWith("TV-") } ?: names.firstOrNull()
    if (named != null) return named
    return when {
        score <= 0 -> "TV-G"
        score <= 7 -> "TV-Y7"
        score <= 10 -> "TV-PG"
        score <= 13 -> "PG-13"
        score <= 14 -> "TV-14"
        score <= 17 -> "R"
        else -> "TV-MA"
    }
}

/** "All libraries", "Movies, Shows", "No libraries". */
fun libraryLine(p: JsonObject, folders: List<VirtualFolder>): String {
    if (p.flag("EnableAllFolders", true)) return t("All libraries")
    val enabled = p.strings("EnabledFolders")
    val names = folders.filter { f -> enabled.any { sameId(it, f.itemId) } }.map { it.name }
    return if (names.isNotEmpty()) names.joinToString(", ") else t("No libraries")
}

/** "No limits", "Remote: 8 Mbps", "No remote access · 9:00–20:00", "Locked after 3 failed sign-ins". */
fun limitsLine(p: JsonObject): String {
    val attempts = p.int("LoginAttemptsBeforeLockout", -1)
    if (p.flag("IsDisabled") && attempts > 0 && p.int("InvalidLoginAttemptCount") >= attempts) {
        // English says "sign-ins" for any number; the {count} key gives French its singular.
        return tn(attempts, "Locked after {count} failed sign-ins", "Locked after {n} failed sign-ins", "count" to formatNumber(attempts))
    }
    val parts = mutableListOf<String>()
    val rate = p.long("RemoteClientBitrateLimit")
    if (!p.flag("EnableRemoteAccess", true)) parts += t("No remote access")
    else if (rate > 0) parts += t("Remote: {rate}", "rate" to mbps(rate))
    val streams = p.int("MaxActiveSessions")
    if (streams > 0) parts += tn(streams, "Up to {n} stream", "Up to {n} streams")
    val schedules = p.schedules()
    schedules.firstOrNull()?.let { s ->
        val day = ShortDays[s.dayOfWeek] ?: s.dayOfWeek
        parts += (if (day.isNotEmpty()) t(day) + " " else "") + "${hourLabel(s.startHour)}–${hourLabel(s.endHour)}" + (if (schedules.size > 1) " +${schedules.size - 1}" else "")
    }
    if (!p.flag("EnableMediaPlayback", true)) parts += t("Can’t play")
    return if (parts.isNotEmpty()) parts.joinToString(" · ") else t("No limits")
}

data class Presence(val text: String, val online: Boolean, val watching: Boolean)

/** "Watching now on iPhone", "Online now on Firefox", "Last seen yesterday on Living room TV". */
fun presence(u: AdminUser, sessions: List<PeopleSession>, devices: List<AdminDevice>, clock: Clock): Presence {
    val mine = sessions.filter { sameId(it.userId, u.id) }.sortedByDescending { it.lastActivityDate.orEmpty() }
    mine.firstOrNull { it.nowPlayingItem != null }?.let {
        return Presence(t("Watching now on {device}", "device" to (it.deviceName ?: it.client ?: t("a device"))), online = true, watching = true)
    }
    mine.firstOrNull { s -> realDate(s.lastActivityDate)?.let { clock.millis() - it.toEpochMilli() < 5 * 60_000 } == true }?.let {
        return Presence(t("Online now on {device}", "device" to (it.deviceName ?: it.client ?: t("a device"))), online = true, watching = false)
    }
    val device = devices.filter { sameId(it.lastUserId, u.id) }.maxByOrNull { it.dateLastActivity.orEmpty() }
    val name = device?.customName?.takeIf { it.isNotBlank() } ?: device?.name
    return Presence(lastSeen(realDate(u.lastActivityDate), clock, name?.takeIf { it.isNotEmpty() }), online = false, watching = false)
}

/** Devices' "Last active": "Now", "2 min ago", "Today, 14:02", "Yesterday, 19:40", "14 Oct". */
fun lastActive(d: Instant?, clock: Clock): String {
    if (d == null) return t("Never")
    val s = ((clock.millis() - d.toEpochMilli()) / 1000.0).roundToLong()
    if (s < 60) return t("Now")
    val m = (s / 60.0).roundToLong()
    if (m < 60) return t("{n} min ago", "n" to m)
    return when (dayDiff(d, clock)) {
        0L -> t("Today, {time}", "time" to hhmm(d, clock))
        1L -> t("Yesterday, {time}", "time" to hhmm(d, clock))
        else -> shortDate(d, clock)
    }
}

/** A key's "Created": "Today", "Yesterday", "2 Mar 2026". */
fun createdDay(d: Instant, clock: Clock): String = when (dayDiff(d, clock)) {
    0L -> t("Today")
    1L -> t("Yesterday")
    else -> fullDate(d.atZone(clock.zone).toLocalDate())
}

/** "March 2024" ("mars 2024"). */
fun monthYearLong(d: Instant, clock: Clock): String {
    val z = d.atZone(clock.zone)
    return "${z.month.getDisplayName(TextStyle.FULL, I18n.locale).replaceFirstChar { if (I18n.locale.language == "en") it.uppercaseChar() else it }} ${z.year}"
}
