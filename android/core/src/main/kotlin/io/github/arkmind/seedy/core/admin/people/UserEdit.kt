package io.github.arkmind.seedy.core.admin.people

import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.admin.ActivityEntry
import io.github.arkmind.seedy.core.admin.AdminHttp
import io.github.arkmind.seedy.core.admin.ItemsResult
import io.github.arkmind.seedy.core.admin.SeedyJson
import io.github.arkmind.seedy.core.admin.errorText
import io.github.arkmind.seedy.core.admin.normId
import io.github.arkmind.seedy.core.admin.realDate
import io.github.arkmind.seedy.core.admin.sameId
import io.github.arkmind.seedy.core.i18n.formatNumber
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.session.UserSession
import io.github.arkmind.seedy.core.settings.Choice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.FileInfo
import java.time.Clock

/*
 * Server › Users › a user (board "Android · Server · Edit user", web/src/screens/server/UserEdit.tsx): one person's
 * account and playback, libraries and devices, parental controls, password and Seedy switches. Changes wait in a
 * patch until Save, which puts only the changed keys onto the policy as the server has it then.
 */

enum class UserTab(val label: String) {
    Account("Account and playback"),
    Access("Libraries and devices"),
    Parental("Parental controls"),
    Password("Password"),
    Seedy("Seedy"),
}

data class SeedyFlags(val canRequest: Boolean = false, val top10: Boolean = true, val history: Boolean = true)

/** A library the person may be allowed: its name and "Movies · /media/Movies". */
data class FolderChoice(val id: String, val name: String, val sub: String)

/** A device on the server: its name, "Seedy · Sam", whether this person last used it and whether it's this phone. */
data class DeviceChoice(val id: String, val name: String, val sub: String, val theirs: Boolean, val mine: Boolean = false)

data class PasswordState(val busy: Boolean = false, val error: String? = null, val done: Int = 0)

data class UserEditState(
    val id: String = "",
    val loaded: Boolean = false,
    val error: String? = null,
    /** The name as saved, and as typed (the header). */
    val savedName: String = "",
    val name: String = "",
    val index: Int = 0,
    val image: String? = null,
    val hasImage: Boolean = false,
    /** "Watching now on iPhone" (green when [online]). */
    val presence: String = "",
    val online: Boolean = false,
    /** "Signed in on 3 devices · created March 2024". */
    val meta: String = "",
    val isMe: Boolean = false,
    /** The policy as saved, and with the unsaved changes on top. */
    val saved: JsonObject = JsonObject(emptyMap()),
    val policy: JsonObject = JsonObject(emptyMap()),
    val folders: List<FolderChoice> = emptyList(),
    val devices: List<DeviceChoice> = emptyList(),
    val ratings: List<ParentalRating> = emptyList(),
    /** The server runs Seedy (the Seedy tab). */
    val seedyOn: Boolean = false,
    val seedyLoaded: Boolean = false,
    val seedyError: Boolean = false,
    val savedSeedy: SeedyFlags = SeedyFlags(),
    val seedy: SeedyFlags = SeedyFlags(),
    /** The Seerr account this person requests as; null when not linked. */
    val seerrName: String? = null,
    val linked: Boolean = false,
    val changes: Int = 0,
    val saving: Boolean = false,
    val photoBusy: Boolean = false,
    val password: PasswordState = PasswordState(),
    val tab: UserTab = UserTab.Account,
    val deleted: Boolean = false,
    val message: String? = null,
) {
    /** "User · can sign in from anywhere". */
    val lede: String
        get() {
            if (!loaded) return ""
            val role = if (saved.flag("IsAdministrator")) t("Administrator") else t("User")
            val access = when {
                saved.flag("IsDisabled") -> t("account disabled")
                !saved.flag("EnableRemoteAccess", true) -> t("home network only")
                else -> t("can sign in from anywhere")
            }
            return "$role · $access"
        }

    /** Whether [key] differs from what's saved. */
    fun changed(key: String): Boolean = !sameJson(policy[key], saved[key])

    val tabs: List<UserTab> get() = UserTab.entries.filter { it != UserTab.Seedy || seedyOn }
}

interface UserEditActions {
    fun tab(tab: UserTab) {}
    fun set(key: String, value: JsonElement) {}
    fun setFlag(key: String, on: Boolean) = set(key, JsonPrimitive(on))
    /** "score:sub" from [ratingOptions], or "" for no limit. */
    fun setRating(value: String) {}
    fun rename(name: String) {}
    fun setSeedy(flags: SeedyFlags) {}
    fun discard() {}
    fun save() {}
    fun uploadPhoto(bytes: ByteArray, mime: String) {}
    fun removePhoto() {}
    fun signOutEverywhere() {}
    fun delete() {}
    fun setPassword(current: String, new: String) {}
    fun removePassword() {}
    fun clearPasswordError() {}
    fun reload() {}
    fun messageShown() {}
}

/* ── Choices ── */

/** Wrong passwords in a row before the account locks; 0 is Jellyfin's default (3, or 5 for admins). */
fun lockOptions(isAdmin: Boolean): List<Choice<Int>> {
    val default = if (isAdmin) 5 else 3
    val list = listOf(Choice(0, tn(default, "{n} attempt", "{n} attempts"))) +
        listOf(1, 2, 3, 5, 10).filter { it != default }.map { Choice(it, tn(it, "{n} attempt", "{n} attempts")) } +
        Choice(-1, t("Never lock"))
    return list.sortedWith(compareBy { if (it.value == -1) Int.MAX_VALUE else if (it.value == 0) default else it.value })
}

fun streamOptions(): List<Choice<Int>> =
    listOf(Choice(0, t("No limit"))) + listOf(1, 2, 3, 4, 5, 6, 8, 10).map { Choice(it, if (it == 1) t("Only 1") else t("Up to {n}", "n" to it)) }

private val Bitrates = listOf(0.0, 120.0, 80.0, 60.0, 40.0, 30.0, 20.0, 15.0, 10.0, 8.0, 6.0, 4.0, 3.0, 2.0, 1.5, 1.0, 0.72, 0.42)

fun bitrateOptions(): List<Choice<Long>> = Bitrates.map { m ->
    val b = (m * 1_000_000).toLong()
    Choice(b, if (b == 0L) t("No limit") else "${formatNumber(if (m % 1.0 == 0.0) m.toLong() else m)} Mbps")
}

fun syncPlayOptions(): List<Choice<String>> = listOf(
    Choice("CreateAndJoinGroups", t("Create and join")),
    Choice("JoinGroups", t("Join only")),
    Choice("None", t("Off")),
)

/** [options] with [value] added when it isn't one of them (a value set elsewhere). */
fun <T> withValue(options: List<Choice<T>>, value: T): List<Choice<T>> =
    if (options.any { it.value == value }) options else options + Choice(value, value.toString())

fun <T> labelOf(options: List<Choice<T>>, value: T): String = options.firstOrNull { it.value == value }?.label ?: value.toString()

/** The ratings as one choice per score (and sub-score), lowest first, with "No limit" on top; and the current one. */
fun ratingOptions(ratings: List<ParentalRating>, policy: JsonObject): Pair<List<Choice<String>>, String> {
    data class Group(val score: Int, val sub: Int?, val names: MutableList<String>)
    val groups = linkedMapOf<String, Group>()
    for (r in ratings) {
        val score = r.score ?: continue
        if (r.name == "Unrated") continue
        val key = "$score:${r.subScore ?: ""}"
        groups.getOrPut(key) { Group(score, r.subScore, mutableListOf()) }.names += r.name
    }
    val sorted = groups.values.sortedWith(compareBy<Group> { it.score }.thenBy { it.sub ?: 0 })
    val options = mutableListOf(Choice("", t("No limit")))
    sorted.forEach { g -> options += Choice("${g.score}:${g.sub ?: ""}", g.names.take(4).joinToString(" · ")) }
    val max = policy.intOrNull("MaxParentalRating")
    val current = if (max == null) "" else "$max:${policy.intOrNull("MaxParentalSubRating") ?: ""}"
    if (current.isNotEmpty() && options.none { it.value == current } && options.none { it.value.startsWith("$max:") }) {
        options += Choice(current, t("Rating {n}", "n" to max))
    }
    val value = if (options.any { it.value == current }) current else options.firstOrNull { it.value.startsWith("$max:") }?.value ?: current
    return options to value
}

/** The kinds of title that can be hidden when they have no rating (Jellyfin's UnratedItem). */
val UnratedKinds = listOf(
    "Movie" to "Movies", "Series" to "Shows", "Trailer" to "Trailers", "Music" to "Music", "Book" to "Books",
    "LiveTvChannel" to "Live TV channels", "LiveTvProgram" to "Live TV programmes", "ChannelContent" to "Channel content", "Other" to "Everything else",
)

private val TypeLabels = mapOf(
    "movies" to "Movies", "tvshows" to "Shows", "music" to "Music", "musicvideos" to "Music videos", "homevideos" to "Home videos",
    "boxsets" to "Collections", "books" to "Books", "photos" to "Photos", "livetv" to "Live TV", "playlists" to "Playlists", "mixed" to "Mixed",
)

/** The same JSON value, reading a missing key as null, numbers by value and lists of strings in any order. */
fun sameJson(a: JsonElement?, b: JsonElement?): Boolean {
    val x = a ?: JsonNull
    val y = b ?: JsonNull
    if (x is JsonPrimitive && y is JsonPrimitive && !x.isString && !y.isString && x !is JsonNull && y !is JsonNull) {
        val dx = x.doubleOrNull
        val dy = y.doubleOrNull
        if (dx != null && dy != null) return dx == dy
    }
    if (x is JsonArray && y is JsonArray && x.all { it is JsonPrimitive } && y.all { it is JsonPrimitive }) {
        return x.map { (it as JsonPrimitive).contentOrNull }.sortedBy { it } == y.map { (it as JsonPrimitive).contentOrNull }.sortedBy { it }
    }
    return x == y
}

class UserEditViewModel(
    private val session: UserSession,
    private val id: String,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel(), UserEditActions {
    private val http = AdminHttp(session.api)
    private val isMe = sameId(id, session.userId.toString())
    private val _state = MutableStateFlow(UserEditState(id = id, isMe = isMe, seedyOn = session.seedyApi != null))
    val state: StateFlow<UserEditState> = _state.asStateFlow()

    private var patch: Map<String, JsonElement> = emptyMap()
    private var namePatch: String? = null
    private var seedyPatch = mapOf<String, Boolean>()

    fun load() {
        viewModelScope.launch {
            runCatching {
                val user = async { person(http.getObject("/Users/$id")) }
                val people = async { loadPeople(http) }
                val created = async {
                    runCatching {
                        http.get<ItemsResult<ActivityEntry>>("/System/ActivityLog/Entries", mapOf("type" to "UserCreated", "limit" to 1000))
                            .items.firstOrNull { sameId(it.userId, id) }?.let { realDate(it.date) }
                    }.getOrNull()
                }
                Triple(user.await(), people.await(), created.await())
            }.onSuccess { (user, people, created) -> loaded(user, people, created) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(error = errorText(e)) }
                }
        }
        if (session.seedyApi != null) loadSeedy()
    }

    override fun reload() = load()

    private fun loaded(pj: PersonJson, people: PeopleData, created: java.time.Instant?) {
        val u = pj.user
        val pres = presence(u, people.sessions, people.devices, clock)
        val latest = people.devices.groupBy { it.id }.map { (_, list) -> list.maxBy { it.dateLastActivity.orEmpty() } }
            .sortedByDescending { it.dateLastActivity.orEmpty() }
        val theirs = latest.count { sameId(it.lastUserId, u.id) }
        val meta = listOfNotNull(
            if (theirs > 0) tn(theirs, "Signed in on {n} device", "Signed in on {n} devices") else t("Not signed in on any device"),
            created?.let { t("created {date}", "date" to monthYearLong(it, clock)) },
        ).joinToString(" · ")
        _state.update {
            it.copy(
                loaded = true,
                error = null,
                savedName = u.name,
                index = people.users.indexOfFirst { p -> sameId(p.user.id, u.id) }.coerceAtLeast(0),
                image = u.primaryImageTag?.let { tag -> uuidOf(u.id)?.let { uid -> session.api.imageApi.getUserImageUrl(userId = uid, tag = tag) } },
                hasImage = u.primaryImageTag != null,
                presence = pres.text,
                online = pres.online,
                meta = meta,
                saved = pj.policy,
                folders = people.folders.map { f ->
                    FolderChoice(f.itemId, f.name, (listOf(t(TypeLabels[f.collectionType ?: "mixed"] ?: "Mixed")) + f.locations).joinToString(" · "))
                },
                devices = latest.map { d ->
                    DeviceChoice(d.id, d.customName?.takeIf { n -> n.isNotBlank() } ?: d.name ?: d.id, listOfNotNull(d.appName, d.lastUserName).filter { s -> s.isNotEmpty() }.joinToString(" · "), sameId(d.lastUserId, u.id), d.id == session.api.deviceInfo.id)
                },
                ratings = people.ratings,
            )
        }
        publish()
    }

    private fun loadSeedy() {
        viewModelScope.launch {
            val accounts = async { runCatching { http.get<List<SeerrAccount>>("/Seedy/Admin/Seerr/Accounts", json = SeedyJson) }.getOrNull() }
            val profiles = async { runCatching { http.get<List<PlayLogProfile>>("/Seedy/Admin/PlayLog/Profiles", json = SeedyJson) }.getOrNull() }
            val a = accounts.await()
            val p = profiles.await()
            val account = a?.firstOrNull { sameId(it.userId, id) }
            val profile = p?.firstOrNull { sameId(it.userId, id) }
            _state.update {
                it.copy(
                    seedyLoaded = true,
                    seedyError = a == null && p == null,
                    savedSeedy = SeedyFlags(account?.canRequest == true, profile?.top10 ?: true, profile?.history ?: true),
                    linked = account?.seerrUserId != null,
                    seerrName = account?.seerrName,
                )
            }
            publish()
        }
    }

    /** The saved state with the unsaved changes on top, and how many there are. */
    private fun publish() = _state.update { s ->
        val policy = JsonObject(s.saved + patch)
        val seedy = SeedyFlags(
            seedyPatch["canRequest"] ?: s.savedSeedy.canRequest,
            seedyPatch["top10"] ?: s.savedSeedy.top10,
            seedyPatch["history"] ?: s.savedSeedy.history,
        )
        val name = namePatch ?: s.savedName
        var n = if (name.trim() != s.savedName) 1 else 0
        // The rating and its sub-rating are one choice on the page.
        val ratingChanged = !sameJson(policy["MaxParentalRating"], s.saved["MaxParentalRating"])
        for (k in patch.keys) {
            if (k == "MaxParentalSubRating" && ratingChanged) continue
            if (!sameJson(policy[k], s.saved[k])) n++
        }
        if (seedy.canRequest != s.savedSeedy.canRequest) n++
        if (seedy.top10 != s.savedSeedy.top10) n++
        if (seedy.history != s.savedSeedy.history) n++
        s.copy(policy = policy, seedy = seedy, name = name, changes = n)
    }

    override fun tab(tab: UserTab) = _state.update { it.copy(tab = tab) }

    override fun set(key: String, value: JsonElement) {
        patch = patch + (key to value)
        publish()
    }

    override fun setRating(value: String) {
        if (value.isEmpty()) {
            patch = patch + ("MaxParentalRating" to JsonNull) + ("MaxParentalSubRating" to JsonNull)
        } else {
            val (score, sub) = value.split(':').let { it[0] to it.getOrElse(1) { "" } }
            patch = patch + ("MaxParentalRating" to JsonPrimitive(score.toInt())) + ("MaxParentalSubRating" to (sub.toIntOrNull()?.let(::JsonPrimitive) ?: JsonNull))
        }
        publish()
    }

    override fun rename(name: String) {
        if (name.isBlank()) return
        namePatch = name.trim()
        publish()
    }

    override fun setSeedy(flags: SeedyFlags) {
        val saved = _state.value.savedSeedy
        seedyPatch = buildMap {
            if (flags.canRequest != saved.canRequest) put("canRequest", flags.canRequest)
            if (flags.top10 != saved.top10) put("top10", flags.top10)
            if (flags.history != saved.history) put("history", flags.history)
        }
        publish()
    }

    override fun discard() {
        patch = emptyMap()
        namePatch = null
        seedyPatch = emptyMap()
        publish()
    }

    private fun say(text: String) = _state.update { it.copy(message = text) }
    override fun messageShown() = _state.update { it.copy(message = null) }

    override fun save() {
        val s = _state.value
        if (s.saving || !s.loaded || s.name.isBlank()) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = runCatching {
                val name = s.name.trim()
                if (name.isNotEmpty() && name != s.savedName) renameUser(http, id, name)
                // Only the keys changed here go onto the server's current policy, so edits made elsewhere since stay.
                val changed = s.policy.filterKeys { !sameJson(s.policy[it], s.saved[it]) }.toMutableMap()
                if ("MaxParentalRating" in changed || "MaxParentalSubRating" in changed) {
                    changed["MaxParentalRating"] = s.policy["MaxParentalRating"] ?: JsonNull
                    changed["MaxParentalSubRating"] = s.policy["MaxParentalSubRating"] ?: JsonNull
                }
                // Never take admin access away from, or disable, the account doing the editing, or lock it out of this device.
                if (isMe) {
                    changed.remove("IsAdministrator")
                    changed.remove("IsDisabled")
                    val own = session.api.deviceInfo.id
                    val enabled = s.policy.strings("EnabledDevices")
                    if (!s.policy.flag("EnableAllDevices", true) && ("EnableAllDevices" in changed || "EnabledDevices" in changed) && own !in enabled) {
                        changed["EnabledDevices"] = strings(enabled + own)
                    }
                }
                if (changed.isNotEmpty()) patchUserPolicy(http, id, changed)
                if (s.seedy.canRequest != s.savedSeedy.canRequest) {
                    http.send("/Seedy/Admin/Seerr/CanRequest", buildJsonObject { put("userId", id); put("canRequest", s.seedy.canRequest) }, json = SeedyJson)
                }
                if (s.seedy.top10 != s.savedSeedy.top10 || s.seedy.history != s.savedSeedy.history) {
                    http.send("/Seedy/Admin/PlayLog/Profiles/$id", buildJsonObject { put("top10", s.seedy.top10); put("history", s.seedy.history) }, json = SeedyJson)
                }
                name.ifEmpty { s.savedName }
            }
            _state.update { it.copy(saving = false) }
            result.onSuccess { name ->
                discard()
                say(t("{name}’s settings saved", "name" to name))
                load()
            }.onFailure { e ->
                if (e is CancellationException) throw e
                say(t("Couldn’t save. {error}", "error" to errorText(e)))
            }
        }
    }

    override fun uploadPhoto(bytes: ByteArray, mime: String) {
        val uid = uuidOf(id) ?: return
        _state.update { it.copy(photoBusy = true) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    // Jellyfin reads the picture as base64 text.
                    session.api.imageApi.postUserImage(uid, FileInfo(Base64.encode(bytes, Base64.NO_WRAP), mime))
                }
            }
            _state.update { it.copy(photoBusy = false) }
            result.onSuccess { say(t("Photo updated")); load() }
                .onFailure { if (it is CancellationException) throw it; say(t("Couldn’t upload the photo. {error}", "error" to errorText(it))) }
        }
    }

    override fun removePhoto() {
        viewModelScope.launch {
            runCatching { http.delete("/UserImage", mapOf("userId" to id)) }
                .onSuccess { _state.update { it.copy(image = null, hasImage = false) }; say(t("Photo removed")); load() }
                .onFailure { if (it is CancellationException) throw it; say(t("Couldn’t remove the photo. {error}", "error" to errorText(it))) }
        }
    }

    override fun signOutEverywhere() {
        val name = _state.value.savedName
        viewModelScope.launch {
            runCatching { signOutEverywhere(http, id, session.userId.toString(), session.api.deviceInfo.id) }
                .onSuccess { n ->
                    say(if (n > 0) tn(n, "Signed {name} out on {n} device", "Signed {name} out on {n} devices", "name" to name) else t("{name} wasn’t signed in anywhere else", "name" to name))
                    load()
                }
                .onFailure { if (it is CancellationException) throw it; say(t("Couldn’t sign out. {error}", "error" to errorText(it))) }
        }
    }

    override fun delete() {
        if (isMe) return
        viewModelScope.launch {
            runCatching { http.delete("/Users/$id") }
                .onSuccess { _state.update { it.copy(deleted = true) } }
                .onFailure { if (it is CancellationException) throw it; say(t("Couldn’t delete. {error}", "error" to errorText(it))) }
        }
    }

    override fun setPassword(current: String, new: String) {
        if (new.isEmpty() || _state.value.password.busy) return
        val name = _state.value.savedName
        _state.update { it.copy(password = it.password.copy(busy = true, error = null)) }
        viewModelScope.launch {
            val result = runCatching { setUserPassword(http, id, new, if (isMe) current else null) }
            result.onSuccess {
                _state.update { it.copy(password = PasswordState(done = it.password.done + 1)) }
                say(t("{name}’s password was changed", "name" to name))
            }.onFailure { e ->
                if (e is CancellationException) throw e
                _state.update { it.copy(password = it.password.copy(busy = false, error = t("Jellyfin didn’t accept the password."))) }
            }
        }
    }

    override fun clearPasswordError() = _state.update { it.copy(password = it.password.copy(error = null)) }

    override fun removePassword() {
        val name = _state.value.savedName
        viewModelScope.launch {
            runCatching { resetUserPassword(http, id) }
                .onSuccess { say(t("{name} signs in without a password now", "name" to name)); load() }
                .onFailure { if (it is CancellationException) throw it; say(t("Couldn’t remove it. {error}", "error" to errorText(it))) }
        }
    }
}

/** Whether the person is shown this device id ([DeviceChoice.id]) in [EnabledDevices]. */
fun JsonObject.allows(device: String): Boolean = strings("EnabledDevices").any { it == device || normId(it) == normId(device) }
