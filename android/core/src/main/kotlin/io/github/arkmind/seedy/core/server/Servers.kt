package io.github.arkmind.seedy.core.server

import io.github.arkmind.seedy.core.data.AppStore
import io.github.arkmind.seedy.core.data.SavedServer
import io.github.arkmind.seedy.core.seedy.SeedyApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.discovery.RecommendedServerIssue
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo

/** The servers this device knows: finding them on the network, adding one by address, keeping their info current. */
class Servers(private val jellyfin: Jellyfin, private val store: AppStore) {

    sealed interface AddResult {
        data class Added(val server: SavedServer) : AddResult
        /** Nothing answered as Jellyfin at that address. */
        data object NotFound : AddResult
        /** A Jellyfin older than this app supports (Seedy needs Jellyfin 12). */
        data class TooOld(val version: String) : AddResult
    }

    /** Servers answering Jellyfin's discovery broadcast on this network (a few seconds, then the flow ends). */
    fun discover(): Flow<ServerDiscoveryInfo> = jellyfin.discovery.discoverLocalServers()

    /** Tries the address as typed ("192.168.1.20", "media.example.org/jellyfin"…), with and without https and the default ports. */
    suspend fun add(input: String): AddResult = withContext(Dispatchers.IO) {
        val found = jellyfin.discovery.getRecommendedServers(input.trim())
        val best = found
            .filter { it.score != RecommendedServerInfoScore.BAD && it.systemInfo.isSuccess }
            .sortedWith(compareBy({ it.score.ordinal }, { it.responseTime }))
            .firstOrNull()
        if (best == null) {
            val old = found.flatMap { it.issues }.filterIsInstance<RecommendedServerIssue.UnsupportedServerVersion>().firstOrNull()
            return@withContext if (old != null) AddResult.TooOld(old.version.toString()) else AddResult.NotFound
        }
        val info = best.systemInfo.getOrThrow()
        val id = info.id ?: return@withContext AddResult.NotFound
        val seedy = SeedyApi.probe(jellyfin.createApi(baseUrl = best.address))
        var saved: SavedServer? = null
        store.update { d ->
            val existing = d.servers.firstOrNull { it.id == id }
            val server = (existing ?: SavedServer(id = id, name = "", address = best.address)).copy(
                name = info.serverName ?: best.address,
                address = best.address,
                version = info.version,
                seedy = seedy,
            )
            saved = server
            d.copy(servers = d.servers.filter { it.id != id } + server, currentServerId = id)
        }
        AddResult.Added(saved!!)
    }

    /** Re-reads the server's name, version and Seedy info (the plugin may have been installed or removed since). */
    suspend fun refresh(server: SavedServer): SavedServer = withContext(Dispatchers.IO) {
        val api = jellyfin.createApi(baseUrl = server.address)
        val info = runCatching { api.systemApi.getPublicSystemInfo().content }.getOrNull()
            ?: return@withContext server
        val updated = server.copy(name = info.serverName ?: server.name, version = info.version, seedy = SeedyApi.probe(api))
        if (updated != server) store.updateServer(server.id) { updated }
        updated
    }

    suspend fun use(id: String) {
        store.update { it.copy(currentServerId = id) }
    }

    suspend fun remove(id: String) {
        store.update { d ->
            val rest = d.servers.filter { it.id != id }
            d.copy(servers = rest, currentServerId = if (d.currentServerId == id) rest.firstOrNull()?.id else d.currentServerId)
        }
    }
}
