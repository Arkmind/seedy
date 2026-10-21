package io.github.arkmind.seedy.core.admin.seedy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.admin.AdminHttp
import io.github.arkmind.seedy.core.admin.SeedyJson
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jellyfin.sdk.api.client.HttpMethod

/*
 * Seedy · Play log (web/src/screens/admin/PlayLog.tsx, board AdminPlayLog): whether plays are recorded and for how
 * long, what they're used for, and each profile's part in it (Top 10, history page, export, clear). Saves as you go.
 */

@Serializable
data class PlayLogProfile(val userId: String = "", val name: String = "", val imageTag: String? = null, val plays: Int = 0, val top10: Boolean = true, val history: Boolean = true)

data class PlayLogAdminState(
    val config: AdminConfig? = null,
    val loadError: String? = null,
    val save: SaveState = SaveState.Saved,
    val profiles: List<PlayLogProfile>? = null,
    val profilesError: String? = null,
    val avatars: Map<String, String> = emptyMap(),
    /** The profile something is being done to (export, clear). */
    val busy: String? = null,
    val clearing: Boolean = false,
    val message: String? = null,
)

interface PlayLogAdminActions {
    fun set(fn: (PlayLogConfig) -> PlayLogConfig) {}
    fun setTop10(userId: String, on: Boolean) {}
    fun setHistory(userId: String, on: Boolean) {}
    fun clear(userId: String) {}
    fun clearAll() {}
    fun retry() {}
    fun retryProfiles() {}
    fun say(text: String) {}
    fun messageShown() {}
    /** A profile's plays as CSV, or what went wrong. */
    suspend fun export(userId: String): Result<ByteArray> = Result.success(ByteArray(0))
}

class PlayLogAdminViewModel(private val session: UserSession) : ViewModel(), PlayLogAdminActions {
    private val http = AdminHttp(session.api)
    private val images = Images(session.api)
    private val store = SeedyConfigStore(http, viewModelScope)
    private val extra = MutableStateFlow(PlayLogAdminState())

    val state: StateFlow<PlayLogAdminState> = combine(store.config, store.loadError, store.save, extra) { c, err, save, x ->
        x.copy(config = c, loadError = err, save = save)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, extra.value)

    fun load() {
        viewModelScope.launch { store.load() }
        loadProfiles()
    }

    override fun retry() = load()

    override fun retryProfiles() = loadProfiles()

    private fun takeProfiles(list: List<PlayLogProfile>) = extra.update { s ->
        s.copy(
            profiles = list, profilesError = null,
            avatars = list.mapNotNull { p -> p.imageTag?.let { tag -> uuidOf(p.userId)?.let { id -> images.user(id, tag)?.let { p.userId to it } } } }.toMap(),
        )
    }

    private fun loadProfiles() {
        viewModelScope.launch {
            runCatching { http.get<List<PlayLogProfile>>("/Seedy/Admin/PlayLog/Profiles", json = SeedyJson) }
                .onSuccess(::takeProfiles)
                .onFailure { e -> if (e is CancellationException) throw e; extra.update { it.copy(profilesError = errorText(e)) } }
        }
    }

    override fun set(fn: (PlayLogConfig) -> PlayLogConfig) = store.edit { it.copy(playLog = fn(it.playLog)) }

    private fun patch(userId: String, fn: (PlayLogProfile) -> PlayLogProfile) =
        extra.update { s -> s.copy(profiles = s.profiles?.map { if (it.userId == userId) fn(it) else it }) }

    private fun toggle(userId: String, key: String, on: Boolean, flip: (PlayLogProfile, Boolean) -> PlayLogProfile) {
        val name = extra.value.profiles?.firstOrNull { it.userId == userId }?.name.orEmpty()
        patch(userId) { flip(it, on) }
        viewModelScope.launch {
            try {
                takeProfiles(http.post<List<PlayLogProfile>>("/Seedy/Admin/PlayLog/Profiles/$userId", buildJsonObject { put(key, JsonPrimitive(on)) }, json = SeedyJson))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                patch(userId) { flip(it, !on) }
                say(t("Couldn’t change {name}.", "name" to name) + " " + errorText(e))
            }
        }
    }

    override fun setTop10(userId: String, on: Boolean) = toggle(userId, "top10", on) { p, v -> p.copy(top10 = v) }

    override fun setHistory(userId: String, on: Boolean) = toggle(userId, "history", on) { p, v -> p.copy(history = v) }

    override suspend fun export(userId: String): Result<ByteArray> {
        val name = extra.value.profiles?.firstOrNull { it.userId == userId }?.name.orEmpty()
        extra.update { it.copy(busy = userId) }
        return try {
            Result.success(http.raw(HttpMethod.GET, "/Seedy/Admin/PlayLog/Export", mapOf("userId" to userId)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(IllegalStateException(t("Couldn’t export {name}’s plays.", "name" to name) + " " + errorText(e)))
        } finally {
            extra.update { it.copy(busy = null) }
        }
    }

    override fun clear(userId: String) {
        val name = extra.value.profiles?.firstOrNull { it.userId == userId }?.name.orEmpty()
        viewModelScope.launch {
            extra.update { it.copy(busy = userId) }
            try {
                http.delete("/Seedy/Admin/PlayLog", mapOf("userId" to userId))
                say(t("{name}’s plays cleared", "name" to name))
                loadProfiles()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(t("Couldn’t clear {name}’s plays.", "name" to name) + " " + errorText(e))
            } finally {
                extra.update { it.copy(busy = null) }
            }
        }
    }

    override fun clearAll() {
        viewModelScope.launch {
            extra.update { it.copy(clearing = true) }
            try {
                http.delete("/Seedy/Admin/PlayLog")
                say(t("Play log cleared"))
                loadProfiles()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(t("Couldn’t clear the play log.") + " " + errorText(e))
            } finally {
                extra.update { it.copy(clearing = false) }
            }
        }
    }

    override fun say(text: String) = extra.update { it.copy(message = text) }

    override fun messageShown() = extra.update { it.copy(message = null) }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override fun onCleared() {
        kotlinx.coroutines.GlobalScope.launch { runCatching { store.flush() } }
        super.onCleared()
    }
}
