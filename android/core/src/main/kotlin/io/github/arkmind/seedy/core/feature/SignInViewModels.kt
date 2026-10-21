package io.github.arkmind.seedy.core.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.SeedyCore
import io.github.arkmind.seedy.core.data.SavedAccount
import io.github.arkmind.seedy.core.data.SavedServer
import io.github.arkmind.seedy.core.format.Images
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.server.Servers
import io.github.arkmind.seedy.core.session.SignIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** "Add a server": servers found on this network, or an address typed in. */
class AddServerViewModel(private val core: SeedyCore) : ViewModel() {
    data class Found(val name: String, val address: String)

    data class State(
        val found: List<Found> = emptyList(),
        val searching: Boolean = true,
        val busy: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        search()
    }

    fun search() {
        _state.update { it.copy(searching = true, found = emptyList()) }
        viewModelScope.launch {
            runCatching {
                core.servers.discover().collect { info ->
                    val address = info.address ?: return@collect
                    _state.update { s -> if (s.found.any { it.address == address }) s else s.copy(found = s.found + Found(info.name ?: address, address)) }
                }
            }
            _state.update { it.copy(searching = false) }
        }
    }

    fun add(address: String) {
        if (address.isBlank() || _state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = core.servers.add(address)) {
                is Servers.AddResult.Added -> core.sessions.useServer(result.server)
                Servers.AddResult.NotFound -> _state.update { it.copy(error = t("No Jellyfin server answered at {address}", "address" to address.trim())) }
                is Servers.AddResult.TooOld -> _state.update {
                    it.copy(error = t("This server runs Jellyfin {version}. Seedy needs Jellyfin 12 or later.", "version" to result.version))
                }
            }
            _state.update { it.copy(busy = false) }
        }
    }
}

/** "Who's watching?": the server's public profiles and the ones signed in on this device. */
class ProfilesViewModel(private val core: SeedyCore, val server: SavedServer) : ViewModel() {
    data class Profile(
        val userId: String,
        val name: String,
        val imageUrl: String?,
        /** A saved session opens straight away; without one the person types their password (the lock badge). */
        val hasSession: Boolean,
        val account: SavedAccount?,
        /** Index into the boards' four tile colours. */
        val colour: Int,
    )

    data class State(
        val loading: Boolean = true,
        val profiles: List<Profile> = emptyList(),
        val servers: List<SavedServer> = emptyList(),
        val quickConnect: Boolean = false,
        /** The profile being opened (its saved session is being checked). */
        val opening: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val images = Images(core.sessions.apiFor(server))

    init {
        viewModelScope.launch {
            val public = core.signIn.publicUsers(server)
            val saved = server.accounts
            val ids = (public.map { it.id.toString() } + saved.map { it.userId }).distinct()
            val profiles = ids.mapIndexed { i, id ->
                val user = public.firstOrNull { it.id.toString() == id }
                val account = saved.firstOrNull { it.userId == id }
                Profile(
                    userId = id,
                    name = user?.name ?: account?.name.orEmpty(),
                    imageUrl = images.user(UUID.fromString(id), user?.primaryImageTag ?: account?.imageTag),
                    hasSession = account != null && core.sessions.tokenFor(server, account) != null,
                    account = account,
                    colour = i % 4,
                )
            }
            val servers = core.store.current().servers
            _state.update { State(loading = false, profiles = profiles, servers = servers, quickConnect = core.signIn.quickConnectEnabled(server)) }
        }
    }

    /** Opens a profile with a saved session. Returns false when it needs the password instead. */
    fun open(profile: Profile, onNeedsPassword: () -> Unit) {
        val account = profile.account
        val token = account?.let { core.sessions.tokenFor(server, it) }
        if (account == null || token == null) return onNeedsPassword()
        _state.update { it.copy(opening = profile.userId) }
        viewModelScope.launch {
            if (!core.sessions.resume(server, account, token)) onNeedsPassword()
            _state.update { it.copy(opening = null) }
        }
    }

    fun useServer(other: SavedServer) {
        viewModelScope.launch { core.sessions.useServer(other) }
    }
}

/** Signing in to one profile (or a typed user name): a password, or Quick Connect from a signed-in device. */
class PasswordViewModel(private val core: SeedyCore, val server: SavedServer, userName: String?) : ViewModel() {
    data class State(
        val userName: String = "",
        val password: String = "",
        val remember: Boolean = true,
        val busy: Boolean = false,
        val error: String? = null,
        /** The Quick Connect code to approve, once asked for. */
        val code: String? = null,
        val quickConnectError: String? = null,
    )

    private val _state = MutableStateFlow(State(userName = userName.orEmpty()))
    val state: StateFlow<State> = _state.asStateFlow()
    private var quickConnect: Job? = null

    fun setUserName(v: String) = _state.update { it.copy(userName = v, error = null) }
    fun setPassword(v: String) = _state.update { it.copy(password = v, error = null) }
    fun setRemember(v: Boolean) = _state.update { it.copy(remember = v) }

    fun signIn() {
        val s = _state.value
        if (s.busy || s.userName.isBlank()) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val error = when (core.signIn.withPassword(server, s.userName.trim(), s.password, s.remember)) {
                SignIn.Result.Success -> null
                SignIn.Result.WrongPassword -> t("Wrong user name or password")
                SignIn.Result.Unreachable -> t("Couldn’t reach {server}", "server" to server.name)
            }
            _state.update { it.copy(busy = false, error = error) }
        }
    }

    fun startQuickConnect() {
        quickConnect?.cancel()
        _state.update { it.copy(code = null, quickConnectError = null) }
        quickConnect = viewModelScope.launch {
            core.signIn.quickConnect(server, _state.value.remember).collect { step ->
                when (step) {
                    is SignIn.QuickConnect.Code -> _state.update { it.copy(code = step.code) }
                    SignIn.QuickConnect.Approved -> Unit
                    SignIn.QuickConnect.Unavailable -> _state.update { it.copy(code = null, quickConnectError = t("Quick Connect is turned off on this server")) }
                    SignIn.QuickConnect.Expired -> _state.update { it.copy(code = null, quickConnectError = t("The code expired. Ask for a new one.")) }
                }
            }
        }
    }

    fun stopQuickConnect() {
        quickConnect?.cancel()
        _state.update { it.copy(code = null) }
    }
}
