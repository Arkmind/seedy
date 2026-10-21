package io.github.arkmind.seedy.core.admin.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.admin.AdminHttp
import io.github.arkmind.seedy.core.admin.AdminUser
import io.github.arkmind.seedy.core.admin.normId
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.session.UserSession
import io.github.arkmind.seedy.core.settings.Choice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

/*
 * The settings every library shares (web/src/screens/server/Libraries.tsx, tabs Display, Metadata and NFO files),
 * each its own page under Libraries. Changes save as they're made, one at a time, on top of what the server has.
 */

/** The library pages that aren't one library: the "library" page's id for them. */
object LibraryPages {
    const val Display = "display"
    const val Metadata = "metadata"
    const val Nfo = "nfo"
    val all = listOf(Display, Metadata, Nfo)
}

data class LibraryDefaultsState(
    val title: String = "",
    val lede: String? = null,
    /** Null while loading. */
    val groups: List<SettingsGroup>? = null,
    val loadError: String? = null,
    val saving: Boolean = false,
    val error: String? = null,
)

interface LibraryDefaultsActions : SettingActions {
    fun retry() {}
}

private val ChapterSizes = listOf("MatchSource" to "Same as the video", "P144" to "144p", "P240" to "240p", "P360" to "360p", "P480" to "480p", "P720" to "720p", "P1080" to "1080p", "P1440" to "1440p", "P2160" to "2160p")

class LibraryDefaultsViewModel(session: UserSession, private val page: String) : ViewModel(), LibraryDefaultsActions {
    private val http = AdminHttp(session.api)
    private val _state = MutableStateFlow(LibraryDefaultsState(title = title(page), lede = lede(page)))
    val state: StateFlow<LibraryDefaultsState> = _state.asStateFlow()

    /** The settings objects this page shows: "" is the server configuration, else a named one ("metadata"…). */
    private val configs = mutableMapOf<String, JsonObject>()
    private var cultures: List<Culture> = emptyList()
    private var countries: List<Country> = emptyList()
    private var users: List<AdminUser> = emptyList()
    private val lock = Mutex()
    private val pending = mutableMapOf<String, JsonElement>()
    private var timer: Job? = null
    private var saves = 0
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun names(): List<String> = when (page) {
        LibraryPages.Display -> listOf("", "metadata")
        LibraryPages.Metadata -> listOf("")
        else -> listOf("xbmcmetadata")
    }

    private fun path(name: String) = if (name.isEmpty()) "/System/Configuration" else "/System/Configuration/$name"

    fun load() {
        viewModelScope.launch {
            val loaded = names().map { n -> n to async { runCatching { http.getObject(path(n)) } } }.map { (n, d) -> n to d.await() }
            if (loaded.any { it.second.isFailure }) {
                _state.update { it.copy(loadError = t("Couldn’t load these settings.")) }
                return@launch
            }
            loaded.forEach { (n, r) -> configs[n] = r.getOrThrow() }
            _state.update { it.copy(loadError = null) }
            publish()
            if (page == LibraryPages.Metadata) {
                val c = async { runCatching { http.get<List<Culture>>("/Localization/Cultures") }.getOrNull() }
                val k = async { runCatching { http.get<List<Country>>("/Localization/Countries") }.getOrNull() }
                cultures = c.await().orEmpty()
                countries = k.await().orEmpty()
                publish()
            }
            if (page == LibraryPages.Nfo) {
                users = runCatching { http.get<List<AdminUser>>("/Users") }.getOrNull().orEmpty()
                publish()
            }
        }
    }

    override fun retry() = load()

    private fun cfg(name: String) = configs[name] ?: JsonObject(emptyMap())

    private fun publish() {
        _state.update { it.copy(groups = libraryDefaultsGroups(page, configs, cultures, countries, users)) }
    }

    /* ── Saving: "config:Key" ── */

    private fun put(key: String, value: JsonElement, now: Boolean = true) {
        val name = key.substringBefore(':')
        val field = key.substringAfter(':')
        configs[name] = cfg(name).with(field, value)
        publish()
        pending[key] = value
        timer?.cancel()
        _state.update { it.copy(saving = true, error = null) }
        timer = saveScope.launch {
            if (!now) delay(700)
            flush()
        }
    }

    private suspend fun flush() {
        val batch = pending.toMap()
        pending.clear()
        if (batch.isEmpty()) return
        val n = ++saves
        val result = lock.withLock {
            runCatching {
                for ((name, keys) in batch.entries.groupBy { it.key.substringBefore(':') }) {
                    http.patchObject(path(name), keys.associate { it.key.substringAfter(':') to it.value })
                }
            }
        }
        if (result.isFailure) batch.forEach { (k, v) -> pending.putIfAbsent(k, v) }
        if (n == saves || result.isFailure) _state.update { it.copy(saving = false, error = if (result.isFailure) t("Couldn’t save. Check your connection and try again.") else null) }
    }

    override fun onCleared() {
        if (pending.isNotEmpty()) saveScope.launch { flush() }
    }

    override fun setSwitch(key: String, on: Boolean) = put(key, json(on))

    override fun setChoice(key: String, value: String) = when (key) {
        "metadata:UseFileCreationTimeForDateAdded" -> put(key, json(value == "file"))
        "xbmcmetadata:UserId" -> put(key, json(value.ifEmpty { null }))
        else -> put(key, json(value))
    }

    override fun setNumber(key: String, value: Double) = when (key) {
        ":DummyChapterDuration" -> put(key, json(value.roundToInt() * 60))
        else -> put(key, json(value.roundToInt()))
    }

    override fun setText(key: String, value: String) = put(key, json(value), now = false)

    companion object {
        fun title(page: String) = when (page) {
            LibraryPages.Display -> t("Display")
            LibraryPages.Metadata -> t("Metadata")
            else -> t("NFO files")
        }

        fun lede(page: String) = when (page) {
            LibraryPages.Display -> t("How every app shows what’s in the libraries.")
            LibraryPages.Metadata -> t("What new libraries start with. Each library can still choose its own.")
            else -> t("Kodi and other apps read these files next to the media. Jellyfin writes them for libraries that save artwork next to the media.")
        }
    }
}

/** A shared library page's cards, from its settings objects ("" is the server configuration, else a named one). */
fun libraryDefaultsGroups(page: String, configs: Map<String, JsonObject>, cultures: List<Culture>, countries: List<Country>, users: List<AdminUser>): List<SettingsGroup> {
    val v = DefaultsView(cultures, countries, users)
    fun cfg(name: String) = configs[name] ?: JsonObject(emptyMap())
    return when (page) {
        LibraryPages.Display -> v.display(cfg(""), cfg("metadata"))
        LibraryPages.Metadata -> v.metadata(cfg(""))
        else -> v.nfo(cfg("xbmcmetadata"))
    }
}

private class DefaultsView(val cultures: List<Culture>, val countries: List<Country>, val users: List<AdminUser>) {
    private fun sw(key: String, o: JsonObject, name: String, help: String?) = SwitchSetting(key, name, help, o.bool(key.substringAfter(':')))

    fun display(c: JsonObject, m: JsonObject): List<SettingsGroup> {
        val dateOptions = listOf(Choice("file", t("When the file was created")), Choice("scan", t("When a scan found it")))
        val date = if (m.bool("UseFileCreationTimeForDateAdded")) "file" else "scan"
        return listOf(
            SettingsGroup(
                t("Browsing"),
                listOf(
                    sw(":EnableFolderView", c, t("Folders view"), t("Adds a library that shows the media folders as they are on disk")),
                    sw(":DisplaySpecialsWithinSeasons", c, t("Specials inside their seasons"), t("Specials sit between the episodes they aired with")),
                    sw(":EnableGroupingMoviesIntoCollections", c, t("Group movies into collections"), t("Movies in a collection show as one card in Movies")),
                    sw(":EnableGroupingShowsIntoCollections", c, t("Group shows into collections"), t("Shows in a collection show as one card in Shows")),
                    sw(":EnableExternalContentInSuggestions", c, t("Suggest channel content"), t("Live TV and channel titles can appear in suggestions")),
                ),
            ),
            SettingsGroup(t("New titles"), listOf(ChoiceSetting("metadata:UseFileCreationTimeForDateAdded", t("Date added"), t("What “Recently added” sorts by"), labelOf(dateOptions, date), date, dateOptions))),
        )
    }

    fun metadata(c: JsonObject): List<SettingsGroup> {
        val langs = cultures.filter { it.twoLetterISOLanguageName.isNotEmpty() }.map { Choice(it.twoLetterISOLanguageName, it.displayName) }
        val ctry = countries.map { Choice(it.twoLetterISORegionName, it.displayName) }
        val lang = c.str("PreferredMetadataLanguage").orEmpty()
        val country = c.str("MetadataCountryCode").orEmpty()
        val langOptions = withCurrent(langs, lang)
        val countryOptions = withCurrent(ctry, country)
        val sizes = ChapterSizes.map { (v, l) -> Choice(v, if (v == "MatchSource") t(l) else l) }
        val size = c.str("ChapterImageResolution") ?: "MatchSource"
        val minutes = (c.int("DummyChapterDuration") / 60.0).roundToInt()
        val names = listOf(Choice("Legacy", t("Jellyfin’s own names")), Choice("Compatible", t("Kodi and Emby names")))
        val convention = c.str("ImageSavingConvention") ?: "Legacy"
        return listOf(
            SettingsGroup(
                t("Language and country"),
                listOf(
                    ChoiceSetting(":PreferredMetadataLanguage", t("Preferred language"), t("For titles, descriptions and artwork"), labelOf(langOptions, lang).ifEmpty { t("Not set") }, lang, langOptions),
                    ChoiceSetting(":MetadataCountryCode", t("Country"), t("For release dates and age ratings"), labelOf(countryOptions, country).ifEmpty { t("Not set") }, country, countryOptions),
                ),
            ),
            SettingsGroup(
                t("Chapters"),
                listOf(
                    ChoiceSetting(":ChapterImageResolution", t("Chapter image size"), t("Pictures for the chapter list in the player"), labelOf(sizes, size), size, withCurrent(sizes, size)),
                    NumberSetting(":DummyChapterDuration", t("Add chapters every"), t("For files without chapters of their own. 0 adds none"), if (minutes == 0) t("Never") else "$minutes min", minutes.toDouble(), 0.0, 240.0, "min"),
                ),
            ),
            SettingsGroup(
                t("Saved files"),
                listOf(
                    ChoiceSetting(":ImageSavingConvention", t("Artwork file names"), t("How artwork saved next to the media is named"), labelOf(names, convention), convention, withCurrent(names, convention)),
                    sw(":SaveMetadataHidden", c, t("Hide saved files"), t("Marks artwork and NFO files Jellyfin writes as hidden")),
                ),
            ),
        )
    }

    fun nfo(n: JsonObject): List<SettingsGroup> {
        val options = listOf(Choice("", t("Nobody"))) + users.map { Choice(normId(it.id), it.name) }
        val user = normId(n.str("UserId")).takeIf { u -> options.any { it.value == u } } ?: ""
        return listOf(
            SettingsGroup(
                t("NFO files"),
                listOf(
                    ChoiceSetting("xbmcmetadata:UserId", t("Save watched state for"), t("Writes which titles this person has watched into the files"), labelOf(options, user), user, options),
                    TextSetting("xbmcmetadata:ReleaseDateFormat", t("Release date format"), t("As Kodi expects it, for example yyyy-MM-dd"), n.str("ReleaseDateFormat").orEmpty(), "yyyy-MM-dd", mono = true),
                    sw("xbmcmetadata:SaveImagePathsInNfo", n, t("Save artwork paths"), t("Lists the artwork files inside each NFO")),
                    sw("xbmcmetadata:EnablePathSubstitution", n, t("Use path substitution"), t("Rewrites those paths with the rules under Settings › Expert")),
                    sw("xbmcmetadata:EnableExtraThumbsDuplication", n, t("Copy extra fanart to extrathumbs"), t("For older Kodi skins that look there")),
                ),
            ),
        )
    }
}
