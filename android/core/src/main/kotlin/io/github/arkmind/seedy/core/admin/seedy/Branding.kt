package io.github.arkmind.seedy.core.admin.seedy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.admin.AdminHttp
import io.github.arkmind.seedy.core.admin.SeedyJson
import io.github.arkmind.seedy.core.admin.errorText
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.seedy.PlaybackPrefs
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.model.FileInfo
import java.net.URI
import java.util.UUID

/*
 * Seedy · Branding & playback (web/src/screens/admin/General.tsx, board AdminGeneral): where Seedy opens, its name,
 * logo, accent and sign-in background, and the playback defaults new profiles start with. Saves as you go.
 */

/** The accent swatches, as the web names them. */
val AccentSwatches = listOf("Pink" to "#ff9fff", "Sky" to "#9fd3ff", "Mint" to "#b8f59f", "Apricot" to "#ffc98a")

private val Hex = Regex("^#[0-9a-fA-F]{6}$")

fun validHex(v: String): Boolean = Hex.matches(v)

/** The logo types the web lets admins upload. */
val LogoTypes = listOf("image/svg+xml", "image/png", "image/webp")

data class BrandingState(
    val config: AdminConfig? = null,
    val loadError: String? = null,
    val save: SaveState = SaveState.Saved,
    /** Where Seedy opens alongside Jellyfin ("/seedy"). */
    val alongsidePath: String = "/seedy",
    /** The uploaded logo, or null for the lettered mark. */
    val logoUrl: String? = null,
    val logoBusy: Boolean = false,
    val message: String? = null,
)

interface BrandingActions {
    fun setOpensAt(v: String) {}
    fun setName(v: String) {}
    fun setAccent(hex: String) {}
    fun setSignInBackground(v: String) {}
    fun setPlayback(fn: (PlaybackPrefs) -> PlaybackPrefs) {}
    fun uploadLogo(bytes: ByteArray, fileName: String, mime: String) {}
    fun removeLogo() {}
    fun retry() {}
    fun say(text: String) {}
    fun messageShown() {}
}

class BrandingViewModel(private val session: UserSession) : ViewModel(), BrandingActions {
    private val http = AdminHttp(session.api)
    // The app's own accent, logo and name follow each saved change.
    private val store = SeedyConfigStore(http, viewModelScope, onSaved = { viewModelScope.launch { session.refreshSeedy() } })
    private val extra = MutableStateFlow(BrandingState(alongsidePath = alongside(session.server.address)))

    val state: StateFlow<BrandingState> = combine(store.config, store.loadError, store.save, extra) { c, err, save, x ->
        x.copy(config = c, loadError = err, save = save, logoUrl = c?.branding?.takeIf { it.hasLogo }?.let { logo(it.logoVersion) })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, extra.value)

    fun load() {
        viewModelScope.launch { store.load() }
    }

    override fun retry() = load()

    private fun logo(version: String) = "${session.server.address.trimEnd('/')}/Seedy/Client/Logo?v=$version"

    private fun branding(delayMs: Long = 600, fn: (BrandingConfig) -> BrandingConfig) = store.edit(delayMs) { it.copy(branding = fn(it.branding)) }

    override fun setOpensAt(v: String) = store.edit(0) { it.copy(opensAt = v) }

    override fun setName(v: String) {
        val name = v.trim()
        if (name.isEmpty() || name == store.config.value?.branding?.name) return
        branding(0) { it.copy(name = name.take(40)) }
    }

    override fun setAccent(hex: String) {
        val v = hex.lowercase()
        if (!validHex(v) || v == store.config.value?.branding?.accent?.lowercase()) return
        branding { it.copy(accent = v) }
    }

    override fun setSignInBackground(v: String) = branding { it.copy(signInBackground = v) }

    override fun setPlayback(fn: (PlaybackPrefs) -> PlaybackPrefs) = store.edit { it.copy(playbackDefaults = fn(it.playbackDefaults)) }

    override fun uploadLogo(bytes: ByteArray, fileName: String, mime: String) {
        if (mime !in LogoTypes && !Regex("\\.(svg|png|webp)$", RegexOption.IGNORE_CASE).containsMatchIn(fileName)) return say(t("Choose an SVG, PNG or WebP file"))
        if (bytes.size > 2 * 1024 * 1024) return say(t("That file is over 2 MB. Try a smaller logo."))
        viewModelScope.launch {
            extra.update { it.copy(logoBusy = true) }
            try {
                runCatching { store.flush() }
                val boundary = "seedy-" + UUID.randomUUID().toString().replace("-", "")
                val name = fileName.replace("\"", "").ifEmpty { "logo" }
                val head = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: $mime\r\n\r\n".toByteArray()
                val tail = "\r\n--$boundary--\r\n".toByteArray()
                val res = http.raw(HttpMethod.POST, "/Seedy/Admin/Logo", body = FileInfo(head + bytes + tail, "multipart/form-data; boundary=$boundary"))
                store.take(SeedyJson.decodeFromString(JsonObject.serializer(), res.decodeToString()))
                session.refreshSeedy()
                say(t("Logo updated"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(t("Couldn’t upload the logo.") + " " + errorText(e))
            } finally {
                extra.update { it.copy(logoBusy = false) }
            }
        }
    }

    override fun removeLogo() {
        viewModelScope.launch {
            extra.update { it.copy(logoBusy = true) }
            try {
                runCatching { store.flush() }
                val res = http.raw(HttpMethod.DELETE, "/Seedy/Admin/Logo")
                store.take(SeedyJson.decodeFromString(JsonObject.serializer(), res.decodeToString()))
                session.refreshSeedy()
                say(t("Logo removed"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(t("Couldn’t remove the logo.") + " " + errorText(e))
            } finally {
                extra.update { it.copy(logoBusy = false) }
            }
        }
    }

    override fun say(text: String) = extra.update { it.copy(message = text) }

    override fun messageShown() = extra.update { it.copy(message = null) }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override fun onCleared() {
        // Changes still waiting for their pause go out now.
        kotlinx.coroutines.GlobalScope.launch { runCatching { store.flush() } }
        super.onCleared()
    }

    private companion object {
        fun alongside(address: String): String = (runCatching { URI(address).path }.getOrNull().orEmpty().trimEnd('/')) + "/seedy"
    }
}
