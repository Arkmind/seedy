package io.github.arkmind.seedy.core.admin.seedy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.admin.AdminHttp
import io.github.arkmind.seedy.core.admin.JellyfinJson
import io.github.arkmind.seedy.core.admin.errorText
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jellyfin.sdk.api.client.extensions.sessionApi

/*
 * Networking (web/src/screens/server/Networking.tsx, board ServerNetworking): ports, HTTPS, remote access and the home
 * network. Changes wait in a draft; saving writes only the changed keys over what the server has now
 * (/System/Configuration/network) and restarts it.
 */

/** The parts of NetworkConfiguration this page shows (PascalCase on the wire). */
@Serializable
data class NetworkConfig(
    val baseUrl: String = "",
    val enableHttps: Boolean = false,
    val requireHttps: Boolean = false,
    val certificatePath: String = "",
    val certificatePassword: String = "",
    val internalHttpPort: Int = 8096,
    val internalHttpsPort: Int = 8920,
    val publicHttpPort: Int = 8096,
    val publicHttpsPort: Int = 8920,
    val autoDiscovery: Boolean = true,
    val enableIPv6: Boolean = false,
    val enableRemoteAccess: Boolean = true,
    val localNetworkSubnets: List<String> = emptyList(),
    val localNetworkAddresses: List<String> = emptyList(),
    val knownProxies: List<String> = emptyList(),
    val ignoreVirtualInterfaces: Boolean = true,
    val publishedServerUriBySubnet: List<String> = emptyList(),
    val remoteIPFilter: List<String> = emptyList(),
    val isRemoteIPFilterBlacklist: Boolean = false,
)

/** The four ports, by their Jellyfin key. */
enum class Port(val key: String, val label: String) {
    LocalHttp("InternalHttpPort", "Local HTTP port"),
    LocalHttps("InternalHttpsPort", "Local HTTPS port"),
    PublicHttp("PublicHttpPort", "Public HTTP port"),
    PublicHttps("PublicHttpsPort", "Public HTTPS port");

    fun of(c: NetworkConfig): Int = when (this) {
        LocalHttp -> c.internalHttpPort
        LocalHttps -> c.internalHttpsPort
        PublicHttp -> c.publicHttpPort
        PublicHttps -> c.publicHttpsPort
    }

    fun set(c: NetworkConfig, n: Int): NetworkConfig = when (this) {
        LocalHttp -> c.copy(internalHttpPort = n)
        LocalHttps -> c.copy(internalHttpsPort = n)
        PublicHttp -> c.copy(publicHttpPort = n)
        PublicHttps -> c.copy(publicHttpsPort = n)
    }
}

fun validPort(n: Int?): Boolean = n != null && n in 1..65535

/** Jellyfin keeps "" for the root; the page shows "/". */
fun showBase(b: String): String = b.ifEmpty { "/" }

fun normaliseBase(v: String): String {
    val x = v.trim().trimEnd('/')
    if (x.isEmpty()) return ""
    return if (x.startsWith('/')) x else "/$x"
}

/** Published addresses are "network=address" pairs (external=https://watch.example.com). */
fun validPublished(v: String): String? {
    val i = v.indexOf('=')
    return if (i <= 0 || i == v.length - 1) t("Write it as network=address, e.g. external=https://watch.example.com.") else null
}

private fun encoded(c: NetworkConfig): JsonObject =
    JellyfinJson.encodeToJsonElement(NetworkConfig.serializer(), c.copy(baseUrl = normaliseBase(c.baseUrl))).jsonObject

/** The keys [draft] changes from [saved], as Jellyfin names them. */
fun networkPatch(saved: NetworkConfig, draft: NetworkConfig): Map<String, JsonElement> {
    val a = encoded(saved)
    val b = encoded(draft)
    return b.filter { (k, v) -> a[k] != v }
}

data class NetworkingState(
    val saved: NetworkConfig? = null,
    val draft: NetworkConfig? = null,
    val loadError: String? = null,
    /** Ports typed but not valid yet; each counts as a change and stops saving. */
    val portText: Map<Port, String> = emptyMap(),
    /** People watching now (their playback stops while the server restarts). */
    val watching: Int = 0,
    val saving: Boolean = false,
    val restarting: Boolean = false,
    val message: String? = null,
) {
    val value: NetworkConfig? get() = draft ?: saved
    val changes: Int get() = (if (saved != null && draft != null) networkPatch(saved, draft).size else 0) + portText.size
}

interface NetworkingActions {
    fun edit(fn: (NetworkConfig) -> NetworkConfig) {}
    fun setPort(port: Port, text: String) {}
    fun discard() {}
    /** Saves and restarts (after the page asked). */
    fun save() {}
    fun retry() {}
    fun say(text: String) {}
    fun messageShown() {}
}

class NetworkingViewModel(private val session: UserSession) : ViewModel(), NetworkingActions {
    private val http = AdminHttp(session.api)
    private val _state = MutableStateFlow(NetworkingState())
    val state: StateFlow<NetworkingState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loadError = null) }
            runCatching { http.get<NetworkConfig>("/System/Configuration/network") }
                .onSuccess { c -> _state.update { it.copy(saved = c, draft = null, portText = emptyMap()) } }
                .onFailure { e -> if (e is CancellationException) throw e; _state.update { it.copy(loadError = errorText(e)) } }
        }
    }

    /** Counts the people watching every 20 s while the page is open. */
    suspend fun live() {
        while (true) {
            runCatching { session.api.sessionApi.getSessions(activeWithinSeconds = 960).content }.getOrNull()?.let { list ->
                val n = list.filter { it.nowPlayingItem != null }.map { it.userId?.toString() ?: it.id }.toSet().size
                _state.update { it.copy(watching = n) }
            }
            delay(20_000)
        }
    }

    override fun retry() = load()

    override fun edit(fn: (NetworkConfig) -> NetworkConfig) = _state.update { s ->
        val cur = s.value ?: return@update s
        val next = fn(cur)
        s.copy(draft = if (s.saved != null && networkPatch(s.saved, next).isEmpty()) null else next)
    }

    override fun setPort(port: Port, text: String) {
        val digits = text.filter { it.isDigit() }
        val n = digits.toIntOrNull()
        if (validPort(n)) {
            _state.update { it.copy(portText = it.portText - port) }
            edit { port.set(it, n!!) }
        } else {
            _state.update { it.copy(portText = it.portText + (port to digits)) }
        }
    }

    override fun discard() = _state.update { it.copy(draft = null, portText = emptyMap()) }

    override fun say(text: String) = _state.update { it.copy(message = text) }

    override fun messageShown() = _state.update { it.copy(message = null) }

    override fun save() {
        val s = _state.value
        val saved = s.saved ?: return
        val draft = s.draft ?: return
        if (s.portText.isNotEmpty()) return say(t("Ports are numbers from 1 to 65535."))
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            try {
                // Jellyfin replaces the whole network configuration: put only this page's changes on what it has now.
                val result = http.patchObject("/System/Configuration/network", networkPatch(saved, draft))
                val c = JellyfinJson.decodeFromJsonElement(NetworkConfig.serializer(), result)
                _state.update { it.copy(saved = c, draft = null, portText = emptyMap()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, message = t("Couldn’t save the network settings.") + " " + errorText(e)) }
                return@launch
            }
            _state.update { it.copy(restarting = true, message = t("Restarting the server…")) }
            try {
                restartAndWait(http)
                _state.update { it.copy(message = t("The server is back")) }
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = t("Settings saved, but the server didn’t restart: {reason}", "reason" to errorText(e))) }
            } finally {
                _state.update { it.copy(saving = false, restarting = false) }
            }
        }
    }
}
