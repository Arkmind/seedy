package io.github.arkmind.seedy.core.session

import android.content.Context
import io.github.arkmind.seedy.core.data.AppStore
import io.github.arkmind.seedy.core.data.SavedAccount
import io.github.arkmind.seedy.core.data.SavedServer
import io.github.arkmind.seedy.core.seedy.SeedyApi
import io.github.arkmind.seedy.core.seedy.SeedyMe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.android.androidDevice
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.UserDto
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed interface SessionState {
    data object Starting : SessionState
    /** No server yet: the app opens on "Add a server". */
    data object NoServer : SessionState
    /** A server is chosen and nobody is signed in: "Who's watching?". */
    data class SignedOut(val server: SavedServer) : SessionState
    /** The server didn't answer while reopening the last profile's session. */
    data class Unreachable(val server: SavedServer) : SessionState
    data class SignedIn(val session: UserSession) : SessionState
}

/** One person signed in on one server. */
class UserSession internal constructor(
    val server: SavedServer,
    val account: SavedAccount,
    val api: ApiClient,
    val user: UserDto,
    me: SeedyMe?,
) {
    val userId: UUID get() = user.id

    private val _seedy = MutableStateFlow(me)

    /** Seedy's view of this person, or null on plain Jellyfin (and when the plugin didn't answer). */
    val seedyFlow: StateFlow<SeedyMe?> = _seedy.asStateFlow()
    val seedy: SeedyMe? get() = _seedy.value

    /** The plugin's API when the server runs Seedy. */
    val seedyApi: SeedyApi? = if (me != null) SeedyApi(api) else null

    /** Settings saved: the screens and players opened from now on use them. */
    fun updatePrefs(prefs: io.github.arkmind.seedy.core.seedy.UserPrefs) {
        _seedy.value = _seedy.value?.copy(prefs = prefs)
    }

    /** Requests (Seerr) are shown only when Seedy is installed and connected to Seerr. */
    val hasRequests: Boolean get() = seedy?.requests?.connected == true
}

/** Opens, switches and ends sessions; the app's screens follow [state]. */
class Sessions(context: Context, private val jellyfin: Jellyfin, private val store: AppStore) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Starting)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val device = androidDevice(context)

    /** Tokens of people who turned off "Stay signed in": kept until the app closes. Keyed "serverId:userId". */
    private val memoryTokens = ConcurrentHashMap<String, String>()

    /** A client for [server], signed in when a token is given. */
    fun apiFor(server: SavedServer, deviceId: String = deviceIdFor(server, ""), token: String? = null): ApiClient =
        jellyfin.createApi(baseUrl = server.address, accessToken = token, deviceInfo = DeviceInfo(id = deviceId, name = device.name))

    /**
     * The device id a profile signs in with. Each profile gets its own, because Jellyfin ends a device's other
     * sessions when someone signs in with the same id; it stays the same for that name, so signing in again replaces
     * the old session instead of adding one.
     */
    fun deviceIdFor(server: SavedServer, userName: String): String =
        "seedy-" + sha256("${device.id}:${server.id}:${userName.lowercase()}").take(32)

    /** Quick Connect doesn't know who will approve it, so each attempt gets a fresh id. */
    fun quickConnectDeviceId(): String = "seedy-qc-" + UUID.randomUUID().toString().replace("-", "")

    fun tokenFor(server: SavedServer, account: SavedAccount): String? =
        account.token ?: memoryTokens[key(server, account.userId)]

    /** On launch: reopens the last profile's session on the last server, or shows where to start. */
    suspend fun start() {
        val server = store.current().currentServer ?: return set(SessionState.NoServer)
        val account = server.account(server.lastUserId)
        val token = account?.let { tokenFor(server, it) }
        if (account == null || token == null) set(SessionState.SignedOut(server)) else resume(server, account, token)
    }

    /** Opens a saved session. False when it can't: the token was revoked (sign in again) or the server is away. */
    suspend fun resume(server: SavedServer, account: SavedAccount, token: String): Boolean = withContext(Dispatchers.IO) {
        val api = apiFor(server, account.deviceId, token)
        try {
            val user = api.userApi.getCurrentUser().content
            open(server, account, api, user)
            true
        } catch (e: InvalidStatusException) {
            if (e.status == 401) forget(server, account.userId)
            set(SessionState.SignedOut(fresh(server)))
            false
        } catch (_: ApiClientException) {
            set(SessionState.Unreachable(server))
            false
        }
    }

    /** Saves a new session (password or Quick Connect) and opens it. */
    suspend fun completeSignIn(server: SavedServer, deviceId: String, result: AuthenticationResult, remember: Boolean) {
        val user = requireNotNull(result.user)
        val token = requireNotNull(result.accessToken)
        val account = SavedAccount(
            userId = user.id.toString(),
            name = user.name.orEmpty(),
            deviceId = deviceId,
            token = token.takeIf { remember },
            imageTag = user.primaryImageTag,
        )
        if (remember) memoryTokens.remove(key(server, account.userId)) else memoryTokens[key(server, account.userId)] = token
        store.updateServer(server.id) { s -> s.copy(accounts = s.accounts.filter { it.userId != account.userId } + account) }
        open(fresh(server), account, apiFor(server, deviceId, token), user)
    }

    /** Back to "Who's watching?"; every saved session stays. */
    suspend fun switchProfile() {
        val server = when (val s = state.value) {
            is SessionState.SignedIn -> s.session.server
            is SessionState.Unreachable -> s.server
            else -> return
        }
        set(SessionState.SignedOut(fresh(server)))
    }

    /** Ends this profile's session on the server and forgets it on this device. */
    suspend fun signOut() {
        val session = (state.value as? SessionState.SignedIn)?.session ?: return
        withContext(Dispatchers.IO) { runCatching { session.api.sessionApi.reportSessionEnded() } }
        forget(session.server, session.account.userId)
        set(SessionState.SignedOut(fresh(session.server)))
    }

    /** Makes [server] the current one and opens its last profile, if it has one. */
    suspend fun useServer(server: SavedServer) {
        store.update { it.copy(currentServerId = server.id) }
        start()
    }

    private suspend fun open(server: SavedServer, account: SavedAccount, api: ApiClient, user: UserDto) {
        val me = if (server.seedy != null) withContext(Dispatchers.IO) { runCatching { SeedyApi(api).me() }.getOrNull() } else null
        store.update { d ->
            d.copy(
                currentServerId = server.id,
                servers = d.servers.map { if (it.id == server.id) it.copy(lastUserId = account.userId) else it },
            )
        }
        set(SessionState.SignedIn(UserSession(server, account, api, user, me)))
    }

    private suspend fun forget(server: SavedServer, userId: String) {
        memoryTokens.remove(key(server, userId))
        store.updateServer(server.id) { s ->
            s.copy(
                accounts = s.accounts.map { if (it.userId == userId) it.copy(token = null) else it },
                lastUserId = if (s.lastUserId == userId) null else s.lastUserId,
            )
        }
    }

    private suspend fun fresh(server: SavedServer): SavedServer =
        store.current().servers.firstOrNull { it.id == server.id } ?: server

    private fun set(state: SessionState) {
        _state.value = state
    }

    private fun key(server: SavedServer, userId: String) = "${server.id}:$userId"

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
