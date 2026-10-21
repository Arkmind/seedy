package io.github.arkmind.seedy.core.settings

import io.github.arkmind.seedy.core.browse.countLabel
import io.github.arkmind.seedy.core.browse.tabsFor
import io.github.arkmind.seedy.core.data.DeviceSettings
import io.github.arkmind.seedy.core.i18n.I18n
import io.github.arkmind.seedy.core.i18n.Lang
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.seedy.HomeRowPref
import io.github.arkmind.seedy.core.seedy.HomeRowSetting
import io.github.arkmind.seedy.core.seedy.PlaybackPrefs
import io.github.arkmind.seedy.core.seedy.RequestsFeatures
import io.github.arkmind.seedy.core.seedy.UserPrefs
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.SubtitlePlaybackMode
import org.jellyfin.sdk.model.api.UserConfiguration
import java.util.Locale
import java.util.UUID

/** A library as Settings · Home screen lists it. */
data class LibraryInfo(val id: UUID, val name: String, val type: CollectionType?, val count: Int? = null)

/** One Home row in the settings list. */
data class RowView(val kind: String, val name: String, val help: String, val enabled: Boolean, val count: Int?, val pinned: Boolean)

/** Approving another device's Quick Connect code. */
data class CodeState(val code: String = "", val busy: Boolean = false, val ok: Boolean? = null, val message: String? = null)

/** The password form (a sheet on the phone). */
data class PasswordForm(val busy: Boolean = false, val error: String? = null, val done: Boolean = false)

/**
 * Everything the settings screens show, phone and TV: the profile's Seedy settings, Jellyfin's own (UserConfiguration),
 * this device's, and a few facts loaded for the pages (libraries, last play, devices…). [seedy] is false on plain
 * Jellyfin, where only this device's and Jellyfin's settings can change.
 */
data class SettingsState(
    val seedy: Boolean = false,
    val prefs: UserPrefs = UserPrefs(),
    /** The admin's playback defaults. */
    val defaults: PlaybackPrefs = PlaybackPrefs(),
    /** The admin's Home rows, as this person can have them. */
    val adminRows: List<HomeRowSetting> = emptyList(),
    val requests: RequestsFeatures = RequestsFeatures(),
    val historyEnabled: Boolean = false,
    val pluginVersion: String = "",
    val config: UserConfiguration? = null,
    val device: DeviceSettings = DeviceSettings(),
    val name: String = "",
    val imageUrl: String? = null,
    val isAdmin: Boolean = false,
    /** The admin's limit for streams away from home (bits per second, 0 = none). */
    val remoteLimit: Int = 0,
    val serverName: String = "",
    val serverAddress: String = "",
    val serverVersion: String? = null,
    /** This device as people know it ("Pixel 9"). */
    val deviceName: String = "",
    val languages: List<Choice<String>> = emptyList(),
    val libraries: List<LibraryInfo>? = null,
    /** Home's rows today, in the order Seedy picked (for "Order automatically"). */
    val autoOrder: List<String>? = null,
    /** "The Pass, today". */
    val lastPlayed: String? = null,
    /** "Changed 3 months ago". */
    val passwordChanged: String? = null,
    val devices: Int? = null,
    val openRequests: Int? = null,
    val saving: Boolean = false,
    val error: String? = null,
    val code: CodeState = CodeState(),
    val password: PasswordForm = PasswordForm(),
) {
    val custom: Map<String, JsonElement> get() = prefs.custom
    fun customBool(key: String, default: Boolean): Boolean = custom[key]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } ?: default
    fun customInt(key: String, default: Int): Int = custom[key]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() } ?: default
    fun customString(key: String): String? = custom[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    val playback: PlaybackPrefs get() = prefs.playback
    val style: ResolvedStyle get() = ResolvedStyle.of(device, prefs.subtitles)
    val language: String get() = customString(Keys.Language) ?: "auto"
    val backdrops: Boolean get() = customBool(Keys.Backdrops, true)

    /** "auto", "en" or "fr" → its name. */
    fun languageLabel(v: String = language): String = when (v.take(2)) {
        "en" -> "English"
        "fr" -> "Français"
        else -> t("Auto")
    }

    /** The "Are you still watching?" choice: "off" or "episodes:minutes". */
    val stillWatching: String
        get() {
            val v = custom[Keys.StillWatching] ?: return "3:90"
            if (v is JsonObject) {
                val e = v["episodes"]?.jsonPrimitive?.intOrNull ?: return "3:90"
                val m = v["minutes"]?.jsonPrimitive?.intOrNull ?: 0
                return if (e > 0) "$e:$m" else "off"
            }
            val p = runCatching { v.jsonPrimitive }.getOrNull()
            return if (p?.booleanOrNull == false || p?.contentOrNull == "off") "off" else "3:90"
        }

    val seekBack: Int get() = device.seekBack ?: playback.seekBackSeconds
    val seekForward: Int get() = device.seekForward ?: playback.seekForwardSeconds

    val subtitleMode: String get() = config?.subtitleMode?.serialName ?: "Default"

    /** A Jellyfin language code ("eng") → its name in the language shown. */
    fun languageName(code: String?): String? {
        if (code.isNullOrEmpty()) return null
        languages.firstOrNull { it.value == code }?.let { return it.label }
        return displayLanguage(code)
    }

    val audioLanguage: String get() = languageName(config?.audioLanguagePreference) ?: t("Original language")
    val subtitleLanguage: String get() = languageName(config?.subtitleLanguagePreference) ?: t("Any")

    /** The rows Settings · Home screen lists: the admin's (switched-on ones only), in this person's order. */
    fun rows(): List<RowView> {
        val allowed = adminRows.ifEmpty { DefaultRows }.filter { it.enabled && it.kind in RowInfo }
        val mine = prefs.rows.filter { p -> allowed.any { it.kind == p.kind } }
        val base = mine.map { it.kind } + allowed.map { it.kind }.filter { k -> mine.none { it.kind == k } }
        val auto = autoOrder
        val ordered = if (prefs.rowOrder != "mine" && auto != null) auto.filter { it in base } + base.filter { it !in auto } else base
        return ordered.map { kind ->
            val a = allowed.first { it.kind == kind }
            val p = mine.firstOrNull { it.kind == kind }
            val info = RowInfo.getValue(kind)
            RowView(kind, t(info.first), t(info.second), p?.enabled ?: true, if (kind in Counted) p?.count ?: a.count else null, p?.pinned == true)
        }
    }

    /** "248 movies · opens on All movies". */
    fun libraryLine(l: LibraryInfo): String {
        val parts = mutableListOf<String>()
        l.count?.let { parts += countLabel(l.type, it) }
        val tabs = tabsFor(l.type)
        val opensOn = (custom[Keys.OpensOn] as? JsonObject)?.get(l.id.toString().replace("-", ""))?.jsonPrimitive?.contentOrNull
            ?: (custom[Keys.OpensOn] as? JsonObject)?.get(l.id.toString())?.jsonPrimitive?.contentOrNull
        if (l.type == CollectionType.MOVIES || l.type == CollectionType.TVSHOWS) {
            val tab = tabs.firstOrNull { it.first.name.equals(opensOn, ignoreCase = true) } ?: tabs.first()
            parts += t("opens on {tab}", "tab" to t(tab.second))
        }
        if (l.type == CollectionType.HOMEVIDEOS || l.type == CollectionType.FOLDERS || l.type == null) parts += t("shown as folders")
        return parts.joinToString(" · ")
    }

    /** A library is on Home when it's neither left out of My media nor of the Latest rows. */
    fun onHome(l: LibraryInfo): Boolean {
        val c = config ?: return true
        return l.id !in c.myMediaExcludes && l.id !in c.latestItemsExcludes
    }

    /* The settings home's lines under each page. */

    fun homeSummary(): String {
        val rows = rows()
        val on = rows.count { it.enabled }
        val count = t("{n} of {total} rows", "n" to on, "total" to rows.size)
        return "$count · " + if (prefs.rowOrder == "mine") t("in my order") else t("ordered automatically")
    }

    fun playbackSummary(): String {
        val q = if (device.qualityHome <= 0) t("Auto quality") else t("{rate} at home", "rate" to Choices.bitrate(device.qualityHome))
        val intro = when (playback.skipIntro) {
            "auto" -> t("intros skip")
            "off" -> t("intros play")
            else -> t("intros show a button")
        }
        return "$q · $intro"
    }

    fun subtitlesSummary(): String {
        val mode = Choices.modes().firstOrNull { it.value == subtitleMode }?.name ?: subtitleMode
        val s = style
        val size = Choices.label(Choices.sizes(), s.size).lowercase(I18n.locale)
        val look = if (s.weight == "bold") t("{size} and bold", "size" to size) else size
        return listOfNotNull(mode, languageName(config?.subtitleLanguagePreference), look).joinToString(" · ")
    }

    fun displaySummary(): String = languageLabel() + " · " + if (backdrops) t("backdrops on") else t("backdrops off")

    fun gesturesSummary(): String {
        val parts = listOfNotNull(
            t("Double-tap to skip").takeIf { device.doubleTapSeek },
            t("swipe for brightness and volume").takeIf { device.swipeControls },
        )
        return if (parts.isEmpty()) t("Off") else parts.joinToString(" · ").replaceFirstChar { it.uppercase(I18n.locale) }
    }

    fun aboutSummary(appVersion: String): String =
        listOfNotNull("Seedy $appVersion", serverVersion?.let { t("Jellyfin {version} server", "version" to it.split('.').take(2).joinToString(".")) }).joinToString(" · ")

    companion object {
        /** prefs.custom keys (the web's, web/src/screens/me/prefs.ts and web/src/player/settings.ts). */
        object Keys {
            const val Accent = "display.accent"
            const val Language = "display.language"
            const val Backdrops = "display.backdrops"
            const val ThemeMusic = "display.themeMusic"
            const val OpensOn = "home.opensOn"
            const val NextUpDays = "home.nextUpDays"
            const val Rewatching = "home.rewatching"
            const val EpisodeImages = "home.episodeImages"
            const val StillWatching = "playback.stillWatching"
        }

        /** Home rows Settings can list, with their names and lines (English, for t()). */
        val RowInfo = linkedMapOf(
            "nextup" to ("Next up" to "Next episode of the shows you’re watching"),
            "because" to ("Because you watched" to "One row per title you watched recently"),
            "top10" to ("Top 10 this week" to "Most watched on this server"),
            "genres" to ("Genre rows" to "The genres you watch most"),
            "latest" to ("Latest in each library" to "Newest additions"),
            "mymedia" to ("My media" to "One tile per library"),
            "upcomingShows" to ("Upcoming shows" to "Next episodes airing soon"),
            "discover" to ("Discover" to "Popular titles you can request"),
            "upcomingMovies" to ("Upcoming movies" to "Requested movies not out yet"),
            "livetv" to ("Live TV" to "Channels on now"),
            "collections" to ("Collections" to "One row per collection"),
        )

        private val Counted = setOf("because", "genres")

        /** The server's starting rows, when the profile didn't bring the admin's list. */
        val DefaultRows = listOf(
            HomeRowSetting("nextup"), HomeRowSetting("top10"), HomeRowSetting("because", count = 6), HomeRowSetting("genres", count = 4),
            HomeRowSetting("mymedia"), HomeRowSetting("latest"), HomeRowSetting("discover"), HomeRowSetting("upcomingMovies"),
            HomeRowSetting("upcomingShows"), HomeRowSetting("collections", enabled = false),
        )

        /** ISO 639-2 bibliographic codes (Jellyfin's) that Java only knows by their terminology code. */
        private val Bibliographic = mapOf(
            "fre" to "fra", "ger" to "deu", "dut" to "nld", "chi" to "zho", "cze" to "ces", "gre" to "ell", "per" to "fas", "rum" to "ron",
            "slo" to "slk", "ice" to "isl", "may" to "msa", "arm" to "hye", "baq" to "eus", "geo" to "kat", "mac" to "mkd", "alb" to "sqi", "wel" to "cym",
        )

        fun displayLanguage(code: String): String {
            val shown = if (I18n.lang == Lang.Fr) Locale.FRENCH else Locale.ENGLISH
            val name = runCatching { Locale(Bibliographic[code] ?: code).getDisplayLanguage(shown) }.getOrNull()
            if (name.isNullOrEmpty() || name.equals(code, ignoreCase = true)) return code
            return name.replaceFirstChar { it.uppercase(shown) }
        }

        fun subtitleMode(value: String): SubtitlePlaybackMode = SubtitlePlaybackMode.entries.firstOrNull { it.serialName == value } ?: SubtitlePlaybackMode.DEFAULT
    }
}

/** What the settings screens can change; the view model does it, previews and screenshot tests ignore it. */
interface SettingsActions {
    fun setLanguage(value: String) {}
    /** A prefs.custom key; null removes it. */
    fun setCustom(key: String, value: JsonElement?) {}
    fun setPlayback(transform: (PlaybackPrefs) -> PlaybackPrefs) {}
    fun setAutoPlayNext(on: Boolean) {}
    fun setStillWatching(value: String) {}
    fun setPrivacy(personalRecs: Boolean, countInTop10: Boolean) {}
    fun setRowOrder(mine: Boolean) {}
    fun setRows(rows: List<HomeRowPref>) {}
    fun setLibraryOnHome(id: UUID, on: Boolean) {}
    fun setConfig(transform: (UserConfiguration) -> UserConfiguration) {}
    fun setSubtitleMode(mode: String) {}
    fun setDevice(transform: (DeviceSettings) -> DeviceSettings) {}
    /** Size and position also feed the profile's size and lift (the player's quick S / M / L on the web). */
    fun setSubtitleStyle(transform: (io.github.arkmind.seedy.core.data.SubtitleStyle) -> io.github.arkmind.seedy.core.data.SubtitleStyle) {}
    fun loadLanguages() {}
    fun setCode(code: String) {}
    fun approveCode() {}
    fun changePassword(current: String, new: String) {}
    fun resetPasswordForm() {}
    fun uploadPicture(bytes: ByteArray, mime: String) {}
    fun switchProfile() {}
    fun signOut() {}
}
