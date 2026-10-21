package io.github.arkmind.seedy.core.feature

import android.os.Build
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.SeedyCore
import io.github.arkmind.seedy.core.browse.typesFor
import io.github.arkmind.seedy.core.data.DeviceSettings
import io.github.arkmind.seedy.core.data.SubtitleStyle
import io.github.arkmind.seedy.core.format.Images
import io.github.arkmind.seedy.core.format.dayMonth
import io.github.arkmind.seedy.core.i18n.I18n
import io.github.arkmind.seedy.core.i18n.Lang
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.seedy.HomeRowPref
import io.github.arkmind.seedy.core.seedy.PlaybackPrefs
import io.github.arkmind.seedy.core.seedy.PrivacyPrefs
import io.github.arkmind.seedy.core.seedy.SubtitlePrefs
import io.github.arkmind.seedy.core.seedy.UserPrefs
import io.github.arkmind.seedy.core.session.UserSession
import io.github.arkmind.seedy.core.settings.Choice
import io.github.arkmind.seedy.core.settings.LibraryInfo
import io.github.arkmind.seedy.core.settings.PasswordForm
import io.github.arkmind.seedy.core.settings.ResolvedStyle
import io.github.arkmind.seedy.core.settings.SettingsActions
import io.github.arkmind.seedy.core.settings.SettingsState
import io.github.arkmind.seedy.core.settings.SettingsState.Companion.Keys
import io.github.arkmind.seedy.core.settings.CodeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.localizationApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userViewApi
import org.jellyfin.sdk.model.FileInfo
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.UpdateUserPassword
import org.jellyfin.sdk.model.api.UserConfiguration
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * Settings, phone and TV (web/src/screens/me): the profile's Seedy settings (`POST /Seedy/Me/Prefs`, a partial patch
 * where playback and subtitles go whole and custom keys merge), Jellyfin's own (UserConfiguration, so they apply in
 * Jellyfin's other apps too) and this device's (DevicePrefs). Changes show at once and save as they're made, one at a
 * time; the session keeps the saved prefs so screens and players opened next use them.
 */
class SettingsViewModel(
    private val core: SeedyCore,
    private val session: UserSession,
    private val now: () -> Instant = Instant::now,
) : ViewModel(), SettingsActions {
    private val extras = MutableStateFlow(SettingsState())
    private val images = Images(session.api)
    private val json = Json { encodeDefaults = true }
    private val prefsLock = Mutex()
    private val configLock = Mutex()
    private var prefsVersion = 0

    val state: StateFlow<SettingsState> = combine(session.seedyFlow, session.userFlow, core.device.flow, extras) { me, user, device, x ->
        x.copy(
            seedy = me != null,
            prefs = me?.prefs ?: UserPrefs(),
            defaults = me?.defaults ?: PlaybackPrefs(),
            adminRows = me?.homeRows.orEmpty(),
            requests = me?.requests ?: x.requests,
            historyEnabled = me?.historyEnabled == true,
            pluginVersion = me?.pluginVersion.orEmpty(),
            config = user.configuration,
            device = device,
            name = user.name.orEmpty(),
            imageUrl = images.user(user.id, user.primaryImageTag),
            isAdmin = me?.isAdmin ?: (user.policy?.isAdministrator == true),
            remoteLimit = user.policy?.remoteClientBitrateLimit ?: 0,
            serverName = session.server.name,
            serverAddress = session.server.address,
            serverVersion = session.server.version,
            deviceName = deviceName(),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsState())

    /** Loads what [pages] show ("home", "profile", "homescreen", "playback", "subtitles"; the TV asks for all). */
    fun load(vararg pages: String) {
        val all = pages.toSet()
        io { session.api.userApi.getCurrentUser().content.let(session::updateUser) }
        if ("home" in all) {
            io { lastPlayed()?.let { v -> extras.update { it.copy(lastPlayed = v) } } }
        }
        if ("home" in all || "profile" in all) {
            val api = session.seedyApi
            if (api != null && session.hasRequests) io { api.openRequests().let { n -> extras.update { it.copy(openRequests = n) } } }
        }
        if ("profile" in all) {
            session.seedyApi?.let { api -> io { api.passwordState().changedAt?.let { d -> extras.update { it.copy(passwordChanged = changedLabel(d)) } } } }
            io {
                val sessions = session.api.sessionApi.getSessions().content
                val n = sessions.filter { it.userId == session.userId }.mapNotNull { it.deviceId }.distinct().size
                extras.update { it.copy(devices = n) }
            }
        }
        if ("homescreen" in all) {
            io { libraries() }
            session.seedyApi?.let { api -> io { api.homeRows().map { it.kind }.distinct().let { o -> extras.update { it.copy(autoOrder = o) } } } }
        }
        if ("playback" in all || "subtitles" in all) loadLanguages()
    }

    /* ── Seedy's profile settings ── */

    override fun setLanguage(value: String) {
        if (session.seedyApi != null) {
            setCustom(Keys.Language, if (value == "auto") JsonNull else JsonPrimitive(value))
        } else {
            // Plain Jellyfin: this device's language (the activity reloads into it).
            viewModelScope.launch { core.store.update { it.copy(lang = if (value == "auto") null else value) } }
        }
    }

    override fun setCustom(key: String, value: JsonElement?) {
        val v = value ?: JsonNull
        savePrefs(buildJsonObject { put("custom", buildJsonObject { put(key, v) }) }) { p ->
            p.copy(custom = if (v is JsonNull) p.custom - key else p.custom + (key to v))
        }
    }

    override fun setPlayback(transform: (PlaybackPrefs) -> PlaybackPrefs) {
        val next = transform(currentPrefs()?.playback ?: return)
        savePrefs(buildJsonObject { put("playback", json.encodeToJsonElement(next)) }) { it.copy(playback = next) }
    }

    /** Seedy's Up next follows the profile; Jellyfin's own apps read their copy of it. */
    override fun setAutoPlayNext(on: Boolean) {
        setPlayback { it.copy(autoPlayNext = on) }
        setConfig { it.copy(enableNextEpisodeAutoPlay = on) }
    }

    override fun setStillWatching(value: String) {
        val v: JsonElement = if (value == "off") JsonPrimitive(false) else {
            val (e, m) = value.split(':').map { it.toInt() }
            buildJsonObject { put("episodes", e); put("minutes", m) }
        }
        setCustom(Keys.StillWatching, v)
    }

    override fun setPrivacy(personalRecs: Boolean, countInTop10: Boolean) {
        val next = PrivacyPrefs(personalRecs, countInTop10)
        savePrefs(buildJsonObject { put("privacy", json.encodeToJsonElement(next)) }) { it.copy(privacy = next) }
    }

    /** "My order" starts from the list on screen, so switching never shuffles it. */
    override fun setRowOrder(mine: Boolean) {
        val rows = state.value.rows().map { HomeRowPref(it.kind, it.enabled, it.count, it.pinned) }
        if (mine) {
            savePrefs(buildJsonObject { put("rowOrder", "mine"); put("rows", json.encodeToJsonElement(rows)) }) { it.copy(rowOrder = "mine", rows = rows) }
        } else {
            savePrefs(buildJsonObject { put("rowOrder", "auto") }) { it.copy(rowOrder = "auto") }
        }
    }

    override fun setRows(rows: List<HomeRowPref>) {
        savePrefs(buildJsonObject { put("rows", json.encodeToJsonElement(rows)) }) { it.copy(rows = rows) }
    }

    /** Size and position also feed the profile's size and lift, which the web player's quick menu follows. */
    override fun setSubtitleStyle(transform: (SubtitleStyle) -> SubtitleStyle) {
        val before = state.value
        core.device.update { it.copy(subtitleStyle = transform(it.subtitleStyle)) }
        val next = ResolvedStyle.of(core.device.current, before.prefs.subtitles)
        val subs = before.prefs.subtitles
        val size = ResolvedStyle.SizeToPrefs[next.size] ?: subs.size
        val lift = if (next.position == "top") subs.lift else next.position == "lifted"
        if (session.seedyApi != null && (size != subs.size || lift != subs.lift)) {
            val p = SubtitlePrefs(size, lift)
            savePrefs(buildJsonObject { put("subtitles", json.encodeToJsonElement(p)) }) { it.copy(subtitles = p) }
        }
    }

    private fun currentPrefs(): UserPrefs? = session.seedy?.prefs

    /** Shows [local] at once, then saves [patch]; saves go one at a time, and only the last answer replaces the prefs. */
    private fun savePrefs(patch: JsonObject, local: (UserPrefs) -> UserPrefs) {
        val api = session.seedyApi ?: return
        val me = session.seedy ?: return
        session.updatePrefs(local(me.prefs))
        val version = ++prefsVersion
        extras.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val saved = prefsLock.withLock { withContext(Dispatchers.IO) { runCatching { api.savePrefs(patch) } } }
            saved.onSuccess { if (version == prefsVersion) session.updatePrefs(it) }
            extras.update {
                it.copy(
                    saving = version != prefsVersion && it.saving,
                    error = if (saved.isFailure) t("Couldn’t save. Check your connection and try again.") else it.error,
                )
            }
        }
    }

    /* ── Jellyfin's own settings ── */

    override fun setConfig(transform: (UserConfiguration) -> UserConfiguration) {
        val before = session.user
        val cfg = before.configuration ?: return
        session.updateUser(before.copy(configuration = transform(cfg)))
        extras.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            // Applied to a fresh copy: another app may have changed something since.
            val result = configLock.withLock {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val fresh = session.api.userApi.getCurrentUser().content
                        val next = transform(fresh.configuration ?: cfg)
                        session.api.userApi.updateUserConfiguration(session.userId, next)
                        fresh.copy(configuration = next)
                    }
                }
            }
            result.onSuccess { u -> session.updateUser(u) }
            extras.update { it.copy(saving = false, error = if (result.isFailure) t("Couldn’t save. Check your connection and try again.") else it.error) }
        }
    }

    override fun setSubtitleMode(mode: String) = setConfig { it.copy(subtitleMode = SettingsState.subtitleMode(mode)) }

    /** Off leaves the library out of My media and of Continue watching and Latest; it stays in Libraries. */
    override fun setLibraryOnHome(id: UUID, on: Boolean) = setConfig { c ->
        c.copy(
            myMediaExcludes = if (on) c.myMediaExcludes - id else c.myMediaExcludes - id + id,
            latestItemsExcludes = if (on) c.latestItemsExcludes - id else c.latestItemsExcludes - id + id,
        )
    }

    /* ── This device ── */

    override fun setDevice(transform: (DeviceSettings) -> DeviceSettings) = core.device.update(transform)

    /* ── Profile ── */

    override fun setCode(code: String) = extras.update { it.copy(code = CodeState(code.filter(Char::isLetterOrDigit).uppercase(Locale.ROOT).take(6))) }

    /** Lets the device showing the code sign in as this profile. */
    override fun approveCode() {
        val c = extras.value.code
        if (c.busy) return
        if (c.code.length < 6) return extras.update { it.copy(code = c.copy(ok = false, message = t("Type the 6-digit code the TV shows."))) }
        extras.update { it.copy(code = c.copy(busy = true, ok = null, message = null)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    if (session.api.authenticationApi.authorizeQuickConnect(code = c.code, userId = session.userId).content) 200 else 404
                } catch (e: InvalidStatusException) {
                    e.status
                } catch (_: ApiClientException) {
                    0
                }
            }
            val message = when (result) {
                200 -> t("The TV is signed in as you.")
                404 -> t("That code didn't work. Check the TV and try again.")
                401 -> t("Quick Connect is turned off on this server.")
                else -> t("Couldn't sign the TV in. The code may have been used already.")
            }
            extras.update { it.copy(code = CodeState(if (result == 200) "" else c.code, busy = false, ok = result == 200, message = message)) }
        }
    }

    /** An empty [new] removes the password. Other devices then need to sign in again. */
    override fun changePassword(current: String, new: String) {
        if (extras.value.password.busy) return
        extras.update { it.copy(password = PasswordForm(busy = true)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    session.api.userApi.updateUserPassword(session.userId, UpdateUserPassword(currentPassword = null, currentPw = current, newPw = new, resetPassword = false))
                    null
                } catch (e: InvalidStatusException) {
                    if (e.status == 401 || e.status == 403) t("That current password didn't work.") else t("Couldn't change the password. Try again.")
                } catch (_: ApiClientException) {
                    t("Couldn't change the password. Try again.")
                }
            }
            extras.update {
                it.copy(password = PasswordForm(error = result, done = result == null), passwordChanged = if (result == null) t("Changed just now") else it.passwordChanged)
            }
        }
    }

    override fun resetPasswordForm() = extras.update { it.copy(password = PasswordForm()) }

    override fun uploadPicture(bytes: ByteArray, mime: String) {
        extras.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // Jellyfin reads the picture as base64 text.
                    val body = Base64.encode(bytes, Base64.NO_WRAP)
                    session.api.imageApi.postUserImage(session.userId, FileInfo(body, mime))
                    session.updateUser(session.api.userApi.getCurrentUser().content)
                }
            }
            extras.update { it.copy(saving = false, error = if (result.isFailure) t("Couldn't change the picture") else null) }
        }
    }

    override fun switchProfile() {
        viewModelScope.launch { core.sessions.switchProfile() }
    }

    override fun signOut() {
        viewModelScope.launch { core.sessions.signOut() }
    }

    /* ── Loading ── */

    /** Jellyfin's languages (ISO 639-2 codes, as it stores them), named in the language shown. */
    override fun loadLanguages() {
        if (extras.value.languages.isNotEmpty()) return
        io {
            val shown = if (I18n.lang == Lang.Fr) Locale.FRENCH else Locale.ENGLISH
            val seen = mutableSetOf<String>()
            val list = session.api.localizationApi.getCultures().content
                .filter { c -> !c.threeLetterIsoLanguageName.isNullOrEmpty() && seen.add(c.threeLetterIsoLanguageName!!) }
                .map { c ->
                    val local = if (I18n.lang == Lang.Fr) c.twoLetterIsoLanguageName?.takeIf { it.isNotEmpty() }?.let { Locale(it).getDisplayLanguage(shown) } else null
                    Choice(c.threeLetterIsoLanguageName!!, (local?.takeIf { it.isNotEmpty() } ?: c.displayName.orEmpty()).replaceFirstChar { it.uppercase(shown) })
                }
                .sortedBy { it.label.lowercase(shown) }
            extras.update { it.copy(languages = list) }
        }
    }

    private suspend fun libraries() {
        val views = session.api.userViewApi.getUserViews(userId = session.userId, includeHidden = true).content.items
        val list = views.map { LibraryInfo(it.id, it.name.orEmpty(), it.collectionType) }
        extras.update { it.copy(libraries = list) }
        val counted = list.map { l ->
            val n = runCatching {
                session.api.libraryApi.getItems(
                    userId = session.userId, parentId = l.id, includeItemTypes = typesFor(l.type), recursive = true, limit = 0, enableTotalRecordCount = true,
                ).content.totalRecordCount
            }.getOrNull()
            l.copy(count = n)
        }
        extras.update { it.copy(libraries = counted) }
    }

    /** "The Pass, today": the last thing played, by its show's name for an episode. */
    private suspend fun lastPlayed(): String? {
        val item = session.api.libraryApi.getItems(
            userId = session.userId, recursive = true, includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.VIDEO),
            sortBy = listOf(ItemSortBy.DATE_PLAYED), sortOrder = listOf(SortOrder.DESCENDING), limit = 1, enableUserData = true,
        ).content.items.firstOrNull() ?: return null
        val played = item.userData?.lastPlayedDate ?: return null
        val name = item.seriesName ?: item.name ?: return null
        val zone = ZoneId.systemDefault()
        val day = played.atZone(ZoneId.of("UTC")).withZoneSameInstant(zone).toLocalDate()
        val today = now().atZone(zone).toLocalDate()
        val label = when (day) {
            today -> t("today")
            today.minusDays(1) -> t("yesterday")
            else -> dayMonth(day)
        }
        return "$name, $label"
    }

    /** "Changed 3 months ago": one sentence per case, so French can word each its own way. */
    private fun changedLabel(iso: String): String? {
        val at = runCatching { Instant.parse(if (iso.endsWith("Z") || iso.contains('+')) iso else iso + "Z") }.getOrNull() ?: return null
        val days = Duration.between(at, now()).toDays()
        if (days < 1) return t("Changed today")
        if (days == 1L) return t("Changed yesterday")
        if (days < 30) return t("Changed {n} days ago", "n" to days)
        val months = Math.round(days / 30.4).toInt()
        if (months < 12) return if (months == 1) t("Changed a month ago") else t("Changed {n} months ago", "n" to months)
        val years = Math.round(days / 365.0).toInt()
        return if (years == 1) t("Changed a year ago") else t("Changed {n} years ago", "n" to years)
    }

    private fun deviceName(): String = Build.MODEL.orEmpty()

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch { withContext(Dispatchers.IO) { runCatching { block() } } }
    }

    /** "Seedy 0.1.0", the server and its versions, for About. */
    fun about(appVersion: String): List<Pair<String, String>> = aboutRows(state.value, appVersion)

    companion object {
        fun aboutRows(s: SettingsState, appVersion: String): List<Pair<String, String>> = listOfNotNull(
            t("App") to "Seedy $appVersion",
            t("Server") to s.serverName,
            s.serverVersion?.let { t("Jellyfin") to it },
            s.pluginVersion.takeIf { it.isNotEmpty() }?.let { t("Seedy plugin") to it },
            t("This device") to s.deviceName,
        )
    }
}
