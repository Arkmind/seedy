package io.github.arkmind.seedy.core.admin.seedy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.admin.AdminHttp
import io.github.arkmind.seedy.core.admin.SeedyJson
import io.github.arkmind.seedy.core.admin.Tone
import io.github.arkmind.seedy.core.admin.errorText
import io.github.arkmind.seedy.core.format.Images
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/*
 * Seedy · Requests (web/src/screens/admin/Requests.tsx, board AdminRequests): the Seerr link (address, key, test),
 * what Seedy shows, who can request, and which Seerr account each profile requests as. Saves as you go.
 */

@Serializable
data class SeerrAccount(
    val userId: String = "",
    val name: String = "",
    val isAdmin: Boolean = false,
    val maxParentalRating: String? = null,
    val hasImage: Boolean = false,
    val imageTag: String? = null,
    val seerrUserId: Int? = null,
    val seerrName: String? = null,
    /** "jellyfin" (Seerr's Jellyfin sign-in) or "manual" (an admin linked it). */
    val matchedBy: String? = null,
    val canRequest: Boolean = false,
) {
    val linked: Boolean get() = seerrUserId != null
}

@Serializable
data class SeerrUser(val id: Int = 0, val displayName: String = "", val email: String? = null, val jellyfinUserId: String? = null, val avatar: String? = null)

@Serializable
data class SeerrTestResult(val ok: Boolean = false, val message: String = "", val version: String? = null, val radarr: Boolean = false, val sonarr: Boolean = false)

/** The connection line: a dot, the first sentence in bold and the rest. */
data class ConnLine(val tone: Tone, val lead: String, val rest: String)

/** "Connected. Seerr can see Radarr and Sonarr." → ("Connected.", "Seerr can see Radarr and Sonarr."). */
private fun splitLead(message: String, fallback: String): Pair<String, String> {
    val m = Regex("^(.+?[.!])\\s+(.*)$", RegexOption.DOT_MATCHES_ALL).find(message.trim())
    if (m != null) return m.groupValues[1] to m.groupValues[2]
    return if (message.isNotBlank()) fallback to message.trim() else fallback to ""
}

data class AdminRequestsState(
    val config: AdminConfig? = null,
    val loadError: String? = null,
    val save: SaveState = SaveState.Saved,
    val testing: Boolean = false,
    val test: SeerrTestResult? = null,
    val testError: String? = null,
    val accounts: List<SeerrAccount>? = null,
    val accountsError: String? = null,
    /** Profile pictures by user id. */
    val avatars: Map<String, String> = emptyMap(),
    val matching: Boolean = false,
    /** Where browsers open Seerr (its Application URL when it has one). */
    val publicUrl: String? = null,
    val message: String? = null,
) {
    /** The connection line under the address and key, as the web words it. */
    val conn: ConnLine
        get() {
            val r = config?.requests ?: RequestsConfig()
            val hasUrl = r.url.isNotEmpty()
            val hasKey = !r.apiKeyHint.isNullOrEmpty()
            val result = test
            return when {
                !hasUrl || !hasKey -> ConnLine(Tone.Normal, t("Not connected."), if (!hasUrl) t("Add the address Jellyfin reaches Seerr at.") else t("Add Seerr’s API key from Settings › General in Seerr."))
                testing && result == null && testError == null -> ConnLine(Tone.Pending, t("Testing…"), t("Asking Seerr for its status."))
                testError != null -> ConnLine(Tone.Danger, t("Couldn’t reach Seerr."), testError)
                result?.ok == true -> {
                    val servers = when {
                        result.radarr && result.sonarr -> t("Seerr can see Radarr and Sonarr.")
                        result.radarr -> t("Seerr can see Radarr, but no Sonarr server is set up.")
                        result.sonarr -> t("Seerr can see Sonarr, but no Radarr server is set up.")
                        else -> t("Seerr has no Radarr or Sonarr server set up yet.")
                    }
                    val (lead, rest) = if (result.message.isNotEmpty()) splitLead(t(result.message), t("Connected.")) else t("Connected.") to servers
                    ConnLine(Tone.Ok, lead, rest)
                }
                result != null -> {
                    val (lead, rest) = splitLead(if (result.message.isNotEmpty()) t(result.message) else t("Seerr did not accept the connection."), t("Not connected."))
                    ConnLine(Tone.Danger, lead, rest)
                }
                else -> ConnLine(Tone.Pending, t("Not tested yet."), t("Test the connection to check the address and key."))
            }
        }
}

interface AdminRequestsActions {
    fun setUrl(v: String) {}
    fun test() {}
    fun set(fn: (RequestsConfig) -> RequestsConfig) {}
    fun setWho(v: String) {}
    fun matchAgain() {}
    fun setCanRequest(userId: String, on: Boolean) {}
    fun unlink(userId: String) {}
    fun retry() {}
    fun retryAccounts() {}
    fun messageShown() {}
    /** Saves the API key; null when it worked, else what to tell the person. */
    suspend fun saveKey(key: String): String? = null
    suspend fun seerrUsers(): Result<List<SeerrUser>> = Result.success(emptyList())
    /** Links a profile to a Seerr user; null when it worked. */
    suspend fun link(userId: String, seerrUserId: Int): String? = null
}

class AdminRequestsViewModel(private val session: UserSession) : ViewModel(), AdminRequestsActions {
    private val http = AdminHttp(session.api)
    private val images = Images(session.api)
    private val store = SeedyConfigStore(http, viewModelScope)
    private val extra = MutableStateFlow(AdminRequestsState(publicUrl = session.seedy?.requests?.seerrUrl))
    private var testSeq = 0

    val state: StateFlow<AdminRequestsState> = combine(store.config, store.loadError, store.save, extra) { c, err, save, x ->
        x.copy(config = c, loadError = err, save = save)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, extra.value)

    fun load() {
        viewModelScope.launch {
            store.load()
            if (store.config.value?.requests?.url?.isNotEmpty() == true) test()
        }
        loadAccounts()
    }

    override fun retry() = load()

    override fun retryAccounts() = loadAccounts()

    private fun takeAccounts(list: List<SeerrAccount>) = extra.update { s ->
        s.copy(
            accounts = list, accountsError = null,
            avatars = list.mapNotNull { a -> if (a.hasImage && a.imageTag != null) uuidOf(a.userId)?.let { id -> images.user(id, a.imageTag)?.let { a.userId to it } } else null }.toMap(),
        )
    }

    private fun loadAccounts() {
        viewModelScope.launch {
            runCatching { http.get<List<SeerrAccount>>("/Seedy/Admin/Seerr/Accounts", json = SeedyJson) }
                .onSuccess(::takeAccounts)
                .onFailure { e -> if (e is CancellationException) throw e; extra.update { it.copy(accountsError = errorText(e)) } }
        }
    }

    override fun test() {
        val seq = ++testSeq
        extra.update { it.copy(testing = true) }
        viewModelScope.launch {
            try {
                val res = http.post<SeerrTestResult>("/Seedy/Admin/Seerr/Test", JsonObject(emptyMap()), json = SeedyJson)
                if (seq == testSeq) extra.update { it.copy(testing = false, test = res, testError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (seq == testSeq) extra.update { it.copy(testing = false, testError = errorText(e)) }
            }
        }
    }

    override fun setUrl(v: String) {
        var url = v.trim().trimEnd('/')
        if (url.isNotEmpty() && !Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url)) url = "http://$url"
        val before = store.config.value?.requests?.url ?: return
        if (url == before) return
        viewModelScope.launch {
            store.edit(0) { it.copy(requests = it.requests.copy(url = url)) }
            if (url.isEmpty()) return@launch
            try {
                store.flush()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            test()
        }
    }

    override fun set(fn: (RequestsConfig) -> RequestsConfig) = store.edit { it.copy(requests = fn(it.requests)) }

    override fun setWho(v: String) = store.edit(0) { it.copy(requests = it.requests.copy(who = v)) }

    override suspend fun saveKey(key: String): String? {
        val v = key.trim()
        if (v.isEmpty()) return null
        return try {
            runCatching { store.flush() }
            val res = http.post<JsonObject>("/Seedy/Admin/Seerr/Key", buildJsonObject { put("apiKey", JsonPrimitive(v)) }, json = SeedyJson)
            store.take(res)
            extra.update { it.copy(message = t("API key saved")) }
            test()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            t("Couldn’t save the key.") + " " + errorText(e)
        }
    }

    override fun matchAgain() {
        viewModelScope.launch {
            extra.update { it.copy(matching = true) }
            try {
                val list = http.post<List<SeerrAccount>>("/Seedy/Admin/Seerr/Match", JsonObject(emptyMap()), json = SeedyJson)
                takeAccounts(list)
                extra.update { it.copy(message = t("{n} of {total} profiles matched", "n" to list.count { a -> a.linked }, "total" to list.size)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                extra.update { it.copy(message = t("Couldn’t match accounts.") + " " + errorText(e)) }
            } finally {
                extra.update { it.copy(matching = false) }
            }
        }
    }

    private fun patchAccount(userId: String, fn: (SeerrAccount) -> SeerrAccount) =
        extra.update { s -> s.copy(accounts = s.accounts?.map { if (it.userId == userId) fn(it) else it }) }

    override fun setCanRequest(userId: String, on: Boolean) {
        val name = extra.value.accounts?.firstOrNull { it.userId == userId }?.name.orEmpty()
        patchAccount(userId) { it.copy(canRequest = on) }
        viewModelScope.launch {
            try {
                val body = buildJsonObject { put("userId", JsonPrimitive(userId)); put("canRequest", JsonPrimitive(on)) }
                takeAccounts(http.post<List<SeerrAccount>>("/Seedy/Admin/Seerr/CanRequest", body, json = SeedyJson))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                patchAccount(userId) { it.copy(canRequest = !on) }
                extra.update { it.copy(message = t("Couldn’t change {name}.", "name" to name) + " " + errorText(e)) }
            }
        }
    }

    override fun unlink(userId: String) {
        val name = extra.value.accounts?.firstOrNull { it.userId == userId }?.name.orEmpty()
        viewModelScope.launch {
            try {
                val body = buildJsonObject { put("userId", JsonPrimitive(userId)); put("seerrUserId", JsonNull) }
                takeAccounts(http.post<List<SeerrAccount>>("/Seedy/Admin/Seerr/Link", body, json = SeedyJson))
                extra.update { it.copy(message = t("{name} unlinked", "name" to name)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                extra.update { it.copy(message = t("Couldn’t unlink {name}.", "name" to name) + " " + errorText(e)) }
            }
        }
    }

    override suspend fun seerrUsers(): Result<List<SeerrUser>> = try {
        Result.success(http.get<List<SeerrUser>>("/Seedy/Admin/Seerr/Users", json = SeedyJson))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(IllegalStateException(errorText(e)))
    }

    override suspend fun link(userId: String, seerrUserId: Int): String? {
        val name = extra.value.accounts?.firstOrNull { it.userId == userId }?.name.orEmpty()
        return try {
            val body = buildJsonObject { put("userId", JsonPrimitive(userId)); put("seerrUserId", JsonPrimitive(seerrUserId)) }
            val list = http.post<List<SeerrAccount>>("/Seedy/Admin/Seerr/Link", body, json = SeedyJson)
            takeAccounts(list)
            val seerr = list.firstOrNull { it.userId == userId }?.seerrName ?: ""
            extra.update { it.copy(message = t("{name} linked to {seerr}", "name" to name, "seerr" to seerr)) }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            t("Couldn’t link the account.") + " " + errorText(e)
        }
    }

    override fun messageShown() = extra.update { it.copy(message = null) }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override fun onCleared() {
        kotlinx.coroutines.GlobalScope.launch { runCatching { store.flush() } }
        super.onCleared()
    }
}
