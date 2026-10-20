package io.github.arkmind.seedy.core.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import io.github.arkmind.seedy.core.seedy.SeedyClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Everything Seedy keeps on the device: the servers it knows, their accounts and which one is in use. */
@Serializable
data class AppData(
    val servers: List<SavedServer> = emptyList(),
    val currentServerId: String? = null,
    /** The last language shown, so the first screen of the next launch is already in it. */
    val lang: String? = null,
    /** The live channel each profile watched last ("serverId:userId" → channel id), for Live TV's "Last watched". */
    val lastChannel: Map<String, String> = emptyMap(),
) {
    val currentServer: SavedServer? get() = servers.firstOrNull { it.id == currentServerId }
}

@Serializable
data class SavedServer(
    val id: String,
    val name: String,
    /** The address that answered, e.g. "http://192.168.1.20:8096" or "https://media.example.org/jellyfin". */
    val address: String,
    val version: String? = null,
    /** Seedy's public info when the server runs the plugin (and speaks an API level this app knows). */
    val seedy: SeedyClient? = null,
    val accounts: List<SavedAccount> = emptyList(),
    val lastUserId: String? = null,
) {
    fun account(userId: String?): SavedAccount? = accounts.firstOrNull { it.userId == userId }
}

/**
 * An account signed in on this device. [deviceId] is its own: Jellyfin ends a device's other sessions when someone
 * signs in with the same device id, and each profile here keeps its session. [token] is null when the person turned
 * off "Stay signed in" (the session then lives only until the app closes).
 */
@Serializable
data class SavedAccount(
    val userId: String,
    val name: String,
    val deviceId: String,
    val token: String? = null,
    val imageTag: String? = null,
)

class AppStore(context: Context) {
    private val store: DataStore<AppData> = DataStoreFactory.create(
        serializer = AppDataSerializer,
        produceFile = { File(context.filesDir, "datastore/seedy.json") },
    )

    val data: Flow<AppData> = store.data

    suspend fun current(): AppData = store.data.first()

    suspend fun update(transform: (AppData) -> AppData): AppData = store.updateData { transform(it) }

    suspend fun updateServer(id: String, transform: (SavedServer) -> SavedServer) = update { d ->
        d.copy(servers = d.servers.map { if (it.id == id) transform(it) else it })
    }
}

private object AppDataSerializer : Serializer<AppData> {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    override val defaultValue = AppData()

    override suspend fun readFrom(input: InputStream): AppData = try {
        json.decodeFromString(AppData.serializer(), input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("Unreadable Seedy data", e)
    }

    override suspend fun writeTo(t: AppData, output: OutputStream) {
        output.write(json.encodeToString(AppData.serializer(), t).encodeToByteArray())
    }
}
