package io.github.arkmind.seedy.core.admin.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.admin.AdminHttp
import io.github.arkmind.seedy.core.admin.ItemsResult
import io.github.arkmind.seedy.core.admin.ago
import io.github.arkmind.seedy.core.admin.dayDiff
import io.github.arkmind.seedy.core.format.fullDate
import io.github.arkmind.seedy.core.admin.errorText
import io.github.arkmind.seedy.core.admin.realDate
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock

/*
 * Server › API keys (web/src/screens/server/ApiKeys.tsx and its board): keys for apps that reach the server without
 * signing in. A new key shows in full once; old ones show their last four characters, and keys unused for 90 days
 * are flagged.
 */

const val StaleDays = 90

data class KeyRow(
    val token: String,
    val app: String,
    /** "•••• 4a5b". */
    val masked: String,
    /** "Created today", "Created 2 Mar 2026". */
    val created: String,
    /** "Last used 3 min ago", "Never used", "Last used 6 months ago · unused". */
    val used: String,
    val stale: Boolean,
)

data class ApiKeysState(
    /** Newest first; null while loading. */
    val keys: List<KeyRow>? = null,
    val error: String? = null,
    /** A key just made, shown in full until the page closes (app to key). */
    val created: Pair<String, String>? = null,
    val creating: Boolean = false,
    val createError: String? = null,
    /** The key being revoked. */
    val busy: String? = null,
    val message: String? = null,
)

interface ApiKeysActions {
    fun reload() {}
    fun create(app: String) {}
    fun clearCreateError() {}
    fun revoke(token: String) {}
    fun messageShown() {}
    fun say(text: String) {}
}

class ApiKeysViewModel(
    session: UserSession,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel(), ApiKeysActions {
    private val http = AdminHttp(session.api)
    private val _state = MutableStateFlow(ApiKeysState())
    val state: StateFlow<ApiKeysState> = _state.asStateFlow()
    private var raw: List<ApiKeyInfo> = emptyList()

    fun load() {
        viewModelScope.launch { fetch() }
    }

    override fun reload() = load()

    private suspend fun fetch(): List<ApiKeyInfo>? = runCatching { http.get<ItemsResult<ApiKeyInfo>>("/Auth/Keys").items }
        .onSuccess { list ->
            raw = list.sortedByDescending { realDate(it.dateCreated)?.toEpochMilli() ?: 0 }
            _state.update { it.copy(keys = raw.map(::row), error = null) }
        }
        .onFailure { e -> if (e is CancellationException) throw e; _state.update { it.copy(error = errorText(e)) } }
        .getOrNull()

    private fun row(k: ApiKeyInfo): KeyRow {
        val created = realDate(k.dateCreated)
        val used = realDate(k.dateLastActivity)
        val ref = used ?: created
        val stale = ref != null && clock.millis() - ref.toEpochMilli() > StaleDays * 86_400_000L
        val whenUsed = used?.let { t("Last used {when}", "when" to ago(it, clock)) } ?: t("Never used")
        return KeyRow(
            token = k.accessToken,
            app = k.appName.ifEmpty { t("Unnamed app") },
            masked = "•••• " + k.accessToken.takeLast(4),
            created = when {
                created == null -> ""
                dayDiff(created, clock) == 0L -> t("Created today")
                dayDiff(created, clock) == 1L -> t("Created yesterday")
                else -> t("Created {date}", "date" to fullDate(created.atZone(clock.zone).toLocalDate()))
            },
            used = if (stale) t("{when} · unused", "when" to whenUsed) else whenUsed,
            stale = stale,
        )
    }

    override fun say(text: String) = _state.update { it.copy(message = text) }
    override fun messageShown() = _state.update { it.copy(message = null) }
    override fun clearCreateError() = _state.update { it.copy(createError = null) }

    override fun create(app: String) {
        val name = app.trim()
        if (name.isEmpty()) return _state.update { it.copy(createError = t("Name the app the key is for.")) }
        _state.update { it.copy(creating = true, createError = null) }
        viewModelScope.launch {
            val before = raw.map { it.accessToken }.toSet()
            val result = runCatching { http.send("/Auth/Keys", query = mapOf("app" to name)) }
            if (result.isFailure) {
                val e = result.exceptionOrNull()!!
                if (e is CancellationException) throw e
                _state.update { it.copy(creating = false, createError = t("Couldn’t create the key. {error}", "error" to errorText(e))) }
                return@launch
            }
            val list = fetch()
            val fresh = list?.firstOrNull { it.accessToken !in before && it.appName == name } ?: list?.firstOrNull { it.accessToken !in before }
            _state.update { it.copy(creating = false, created = fresh?.let { k -> name to k.accessToken } ?: it.created) }
            if (fresh == null) say(t("Key created for {app}", "app" to name))
        }
    }

    override fun revoke(token: String) {
        val app = raw.firstOrNull { it.accessToken == token }?.appName.orEmpty()
        _state.update { it.copy(busy = token) }
        viewModelScope.launch {
            runCatching { http.delete("/Auth/Keys/$token") }
                .onSuccess {
                    _state.update { it.copy(created = if (it.created?.second == token) null else it.created) }
                    say(t("Key for {app} revoked", "app" to app))
                    fetch()
                }
                .onFailure { if (it is CancellationException) throw it; say(t("Couldn’t revoke the key. {error}", "error" to errorText(it))) }
            _state.update { it.copy(busy = null) }
        }
    }
}
