package io.github.arkmind.seedy.core.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.SeedyCore
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.seedy.PlaybackPrefs
import io.github.arkmind.seedy.core.seedy.SubtitlePrefs
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.authenticationApi

/**
 * The apps' settings: language, playback and subtitles (saved to the person's Seedy profile, so every device follows
 * them), approving another device's Quick Connect code, and what this app and the server are.
 */
class SettingsViewModel(private val core: SeedyCore, private val session: UserSession) : ViewModel() {
    data class State(
        /** "auto", "en" or "fr". */
        val language: String = "auto",
        val playback: PlaybackPrefs = PlaybackPrefs(),
        val subtitles: SubtitlePrefs = SubtitlePrefs(),
        /** Playback and subtitle settings live in Seedy: without the plugin they can't be changed here. */
        val canSave: Boolean = false,
        val saving: Boolean = false,
        val error: String? = null,
        val code: String = "",
        val approving: Boolean = false,
        /** What happened to the last Quick Connect code. */
        val approved: String? = null,
    )

    private val _state = MutableStateFlow(read())
    val state: StateFlow<State> = _state.asStateFlow()

    private fun read(): State {
        val me = session.seedy
        val lang = me?.prefs?.custom?.get("display.language")?.jsonPrimitive?.contentOrNull ?: "auto"
        return State(
            language = lang,
            playback = me?.prefs?.playback ?: PlaybackPrefs(),
            subtitles = me?.prefs?.subtitles ?: SubtitlePrefs(),
            canSave = me != null,
        )
    }

    /** The profile's language; on plain Jellyfin it's this device's (the activity reloads into it either way). */
    fun setLanguage(value: String) {
        _state.update { it.copy(language = value) }
        if (session.seedyApi != null) {
            save(buildJsonObject { put("custom", buildJsonObject { put("display.language", JsonPrimitive(value)) }) })
        } else {
            viewModelScope.launch { core.store.update { it.copy(lang = if (value == "auto") null else value) } }
        }
    }

    fun setPlayback(p: PlaybackPrefs) {
        _state.update { it.copy(playback = p) }
        save(buildJsonObject { put("playback", Json.encodeToJsonElement(p)) })
    }

    fun setSubtitles(s: SubtitlePrefs) {
        _state.update { it.copy(subtitles = s) }
        save(buildJsonObject { put("subtitles", Json.encodeToJsonElement(s)) })
    }

    private fun save(patch: kotlinx.serialization.json.JsonObject) {
        val api = session.seedyApi ?: return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { api.savePrefs(patch) } }
            saved.onSuccess { session.updatePrefs(it) }
            _state.update { it.copy(saving = false, error = if (saved.isFailure) t("Couldn’t save. Check your connection and try again.") else null) }
        }
    }

    fun setCode(v: String) = _state.update { it.copy(code = v.filter(Char::isDigit).take(6), approved = null) }

    /** Lets the device showing [State.code] sign in as this profile. */
    fun approve() {
        val code = _state.value.code
        if (code.length < 6 || _state.value.approving) return
        _state.update { it.copy(approving = true, approved = null) }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    session.api.authenticationApi.authorizeQuickConnect(code = code, userId = session.userId).content
                } catch (_: ApiClientException) {
                    false
                }
            }
            _state.update {
                it.copy(
                    approving = false, code = if (ok) "" else it.code,
                    approved = if (ok) t("Approved. The other device is signing in.") else t("That code didn’t work. Check it and try again."),
                )
            }
        }
    }

    fun switchProfile() {
        viewModelScope.launch { core.sessions.switchProfile() }
    }

    fun signOut() {
        viewModelScope.launch { core.sessions.signOut() }
    }

    /** "Seedy for Android 0.1.0", the server's name and versions. */
    fun about(appVersion: String): List<Pair<String, String>> = listOfNotNull(
        t("App") to "Seedy $appVersion",
        t("Server") to session.server.name,
        session.server.version?.let { t("Jellyfin") to it },
        session.seedy?.pluginVersion?.takeIf { it.isNotEmpty() }?.let { t("Seedy plugin") to it },
    )
}
