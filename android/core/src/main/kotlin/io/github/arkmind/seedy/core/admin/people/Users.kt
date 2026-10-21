package io.github.arkmind.seedy.core.admin.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.admin.AdminHttp
import io.github.arkmind.seedy.core.admin.dayDiff
import io.github.arkmind.seedy.core.admin.errorText
import io.github.arkmind.seedy.core.admin.realDate
import io.github.arkmind.seedy.core.admin.sameId
import io.github.arkmind.seedy.core.i18n.formatNumber
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jellyfin.sdk.api.client.extensions.imageApi
import java.time.Clock

/*
 * Server › Users (board "Android · Server · Users", web/src/screens/server/Users.tsx): everyone with an account,
 * with their access at a glance, filtered to admins or disabled accounts; and adding someone.
 */

enum class UserFilter { All, Admins, Disabled }

/** A user's row: who, where they are, their tags and the libraries · limits line. */
data class UserRow(
    val id: String,
    val name: String,
    /** Place in the server's user list (the avatar colour). */
    val index: Int,
    val image: String? = null,
    val online: Boolean = false,
    val seen: String = "",
    val tags: List<String> = emptyList(),
    /** "All libraries · No limits". */
    val access: String = "",
    val isAdmin: Boolean = false,
    val disabled: Boolean = false,
    val isMe: Boolean = false,
)

data class UsersState(
    /** "5 users · 3 signed in today"; null while loading. */
    val lede: String? = null,
    /** In the server's order; null while loading. */
    val users: List<UserRow>? = null,
    val error: String? = null,
    val filter: UserFilter = UserFilter.All,
    val adding: Boolean = false,
    val addError: String? = null,
    /** A user just added: the page opens theirs. */
    val opened: String? = null,
    val message: String? = null,
) {
    fun count(f: UserFilter): Int = users.orEmpty().count { it.matches(f) }
    val shown: List<UserRow>? get() = users?.filter { it.matches(filter) }
}

fun UserRow.matches(f: UserFilter): Boolean = when (f) {
    UserFilter.All -> true
    UserFilter.Admins -> isAdmin
    UserFilter.Disabled -> disabled
}

interface UsersActions {
    fun filter(f: UserFilter) {}
    fun reload() {}
    /** Adds a user, starting from [copyFrom]'s access (never their admin rights) when given. */
    fun addUser(name: String, password: String, copyFrom: String?) {}
    fun clearAddError() {}
    fun setDisabled(id: String, disabled: Boolean) {}
    fun signOutEverywhere(id: String) {}
    fun delete(id: String) {}
    fun openedShown() {}
    fun messageShown() {}
}

class UsersViewModel(
    private val session: UserSession,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel(), UsersActions {
    private val http = AdminHttp(session.api)
    private val _state = MutableStateFlow(UsersState())
    val state: StateFlow<UsersState> = _state.asStateFlow()
    private var data = PeopleData()

    fun load() {
        viewModelScope.launch {
            runCatching { loadPeople(http) }
                .onSuccess { d ->
                    data = d
                    _state.update { it.copy(users = rows(d), lede = lede(d), error = null) }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(error = errorText(e), lede = if (it.users == null) t("Users unavailable") else it.lede) }
                }
        }
    }

    override fun reload() = load()

    private fun lede(d: PeopleData): String {
        val today = d.users.count { u -> realDate(u.user.lastActivityDate)?.let { dayDiff(it, clock) == 0L } == true }
        // "1 signed in today" and "3 signed in today" read the same in English; the {count} key gives French its singular.
        return tn(d.users.size, "{n} user", "{n} users") + " · " + tn(today, "{count} signed in today", "{n} signed in today", "count" to formatNumber(today))
    }

    private fun rows(d: PeopleData): List<UserRow> = d.users.mapIndexed { i, pj ->
        val u = pj.user
        val p = pj.policy
        val pres = presence(u, d.sessions, d.devices, clock)
        val tags = buildList {
            if (p.flag("IsAdministrator")) add(t("Admin"))
            if (p.flag("IsDisabled")) add(t("Disabled"))
            if (p.flag("IsHidden")) add(t("Hidden"))
            ratingName(p.intOrNull("MaxParentalRating"), d.ratings)?.let(::add)
        }
        UserRow(
            id = u.id,
            name = u.name,
            index = i,
            image = u.primaryImageTag?.let { tag -> uuidOf(u.id)?.let { session.api.imageApi.getUserImageUrl(userId = it, tag = tag) } },
            online = pres.online,
            seen = pres.text,
            tags = tags,
            access = libraryLine(p, d.folders) + " · " + limitsLine(p),
            isAdmin = p.flag("IsAdministrator"),
            disabled = p.flag("IsDisabled"),
            isMe = sameId(u.id, session.userId.toString()),
        )
    }

    private fun say(text: String) = _state.update { it.copy(message = text) }
    override fun messageShown() = _state.update { it.copy(message = null) }
    override fun openedShown() = _state.update { it.copy(opened = null) }
    override fun clearAddError() = _state.update { it.copy(addError = null) }
    override fun filter(f: UserFilter) = _state.update { it.copy(filter = f) }

    private fun nameOf(id: String) = data.users.firstOrNull { sameId(it.user.id, id) }?.user?.name.orEmpty()

    override fun addUser(name: String, password: String, copyFrom: String?) {
        val clean = name.trim()
        if (clean.isEmpty() || _state.value.adding) return
        _state.update { it.copy(adding = true, addError = null) }
        viewModelScope.launch {
            val result = runCatching {
                val body = buildJsonObject { put("Name", clean); if (password.isNotEmpty()) put("Password", password) }
                val created = person(http.post<JsonObject>("/Users/New", body))
                val source = data.users.firstOrNull { sameId(it.user.id, copyFrom) }
                if (source != null) {
                    val made = created.policy
                    val policy = JsonObject(
                        source.policy + mapOf(
                            "IsAdministrator" to JsonPrimitive(false),
                            "IsDisabled" to JsonPrimitive(false),
                            "InvalidLoginAttemptCount" to JsonPrimitive(0),
                        ) + listOfNotNull(
                            (made["AuthenticationProviderId"] ?: source.policy["AuthenticationProviderId"])?.let { "AuthenticationProviderId" to it },
                            (made["PasswordResetProviderId"] ?: source.policy["PasswordResetProviderId"])?.let { "PasswordResetProviderId" to it },
                        ),
                    )
                    http.send("/Users/${created.user.id}/Policy", policy)
                    (source.json["Configuration"] as? JsonObject)?.let { cfg ->
                        runCatching { http.send("/Users/Configuration", cfg, mapOf("userId" to created.user.id)) }
                    }
                }
                created
            }
            result.onSuccess { created ->
                _state.update { it.copy(adding = false, opened = created.user.id) }
                say(t("{name} was added", "name" to created.user.name))
                load()
            }.onFailure { e ->
                if (e is CancellationException) throw e
                _state.update { it.copy(adding = false, addError = t("Jellyfin didn’t create the user.")) }
            }
        }
    }

    override fun setDisabled(id: String, disabled: Boolean) {
        val name = nameOf(id)
        viewModelScope.launch {
            runCatching { patchUserPolicy(http, id, mapOf("IsDisabled" to JsonPrimitive(disabled))) }
                .onSuccess { say(if (disabled) t("{name} is disabled", "name" to name) else t("{name} can sign in again", "name" to name)); load() }
                .onFailure { if (it is CancellationException) throw it; say(t("Couldn’t change it. {error}", "error" to errorText(it))) }
        }
    }

    override fun signOutEverywhere(id: String) {
        val name = nameOf(id)
        viewModelScope.launch {
            runCatching { signOutEverywhere(http, id, session.userId.toString(), session.api.deviceInfo.id) }
                .onSuccess { n ->
                    say(if (n > 0) tn(n, "Signed {name} out on {n} device", "Signed {name} out on {n} devices", "name" to name) else t("{name} wasn’t signed in anywhere else", "name" to name))
                    load()
                }
                .onFailure { if (it is CancellationException) throw it; say(t("Couldn’t sign out. {error}", "error" to errorText(it))) }
        }
    }

    override fun delete(id: String) {
        val name = nameOf(id)
        viewModelScope.launch {
            runCatching { http.delete("/Users/$id") }
                .onSuccess { say(t("{name} was deleted", "name" to name)); load() }
                .onFailure { if (it is CancellationException) throw it; say(t("Couldn’t delete. {error}", "error" to errorText(it))) }
        }
    }
}
