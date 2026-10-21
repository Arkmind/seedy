package io.github.arkmind.seedy.core.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.admin.errorText
import io.github.arkmind.seedy.core.format.dayMonth
import io.github.arkmind.seedy.core.format.fullDate
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.session.UserSession
import io.github.arkmind.seedy.core.settings.Choice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/*
 * Edit metadata (board ItemEditMetadata): the item as Jellyfin sent it (GET /Users/{uid}/Items/{id}), edited as JSON
 * and saved whole (POST /Items/{id}); keys this page doesn't show go back as they came, since Jellyfin clears whatever
 * a save leaves out. Fields Jellyfin can lock show "Locked"; a field you edit locks on save, so a refresh won't undo it.
 */

enum class MetaTab { Details, People, Providers, Display }

data class MetaPerson(val name: String, val role: String, val type: String, val id: String? = null)

data class ProviderField(val key: String, val label: String, val value: String)

data class MetadataState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val name: String = "",
    /** "Series · /media/shows/Northbound · last refreshed 18 Oct". */
    val facts: String = "",
    val type: String = "",
    val tab: MetaTab = MetaTab.Details,
    val title: String = "",
    val originalTitle: String = "",
    val sortTitle: String = "",
    val premiere: LocalDate? = null,
    val ended: LocalDate? = null,
    val status: String = "",
    val rating: String = "",
    val ratings: List<String> = emptyList(),
    val overview: String = "",
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val community: String = "",
    val critic: String = "",
    val people: List<MetaPerson> = emptyList(),
    val providers: List<ProviderField> = emptyList(),
    val language: String = "",
    val country: String = "",
    val languages: List<Choice<String>> = emptyList(),
    val countries: List<Choice<String>> = emptyList(),
    val displayOrder: String = "",
    /** Jellyfin's locked fields ("Name", "Overview", "Genres", "Cast"…). */
    val locked: Set<String> = emptySet(),
    /** Fields whose lock was switched here (they keep showing their chip). */
    val toggled: Set<String> = emptySet(),
    val lockAll: Boolean = false,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
) {
    val isSeries: Boolean get() = type == "Series"
    val dateLabel: String get() = if (type == "Series" || type == "Episode" || type == "Season") t("First aired") else t("Release date")
}

interface MetadataActions {
    fun tab(tab: MetaTab) {}
    fun edit(change: (MetadataState) -> MetadataState) {}
    fun toggleLock(field: String) {}
    fun setLockAll(on: Boolean) {}
    fun retry() {}
    /** Saves; [onDone] gets what to say. */
    fun save(onDone: (String) -> Unit) {}
}

class MetadataViewModel(session: UserSession, private val id: String) : ViewModel(), MetadataActions {
    private val api = ManageApi(session)
    private val _state = MutableStateFlow(MetadataState())
    val state: StateFlow<MetadataState> = _state.asStateFlow()
    private var item: JsonObject = JsonObject(emptyMap())
    private var loaded = MetadataState()

    init {
        load()
    }

    private fun load() {
        _state.update { it.copy(loading = true, failed = false) }
        viewModelScope.launch {
            val result = runCatching {
                kotlinx.coroutines.coroutineScope {
                    val i = async { api.item(id) }
                    val info = async { runCatching { api.editorInfo(id) }.getOrNull() }
                    val refreshed = async { runCatching { api.http.getObject("/Items", mapOf("ids" to id, "fields" to "DateLastRefreshed")) }.getOrNull() }
                    Triple(i.await(), info.await(), refreshed.await())
                }
            }
            result.onSuccess { (i, info, refreshed) ->
                item = i
                val s = read(i, info, refreshed)
                loaded = s
                _state.value = s
            }.onFailure { e ->
                if (e is CancellationException) throw e
                _state.update { it.copy(loading = false, failed = true, error = t("Couldn’t load this title. {error}", "error" to errorText(e))) }
            }
        }
    }

    override fun retry() = load()

    override fun tab(tab: MetaTab) = _state.update { it.copy(tab = tab) }

    override fun edit(change: (MetadataState) -> MetadataState) = _state.update { change(it).copy(dirty = true, error = null) }

    override fun toggleLock(field: String) = _state.update {
        it.copy(locked = if (field in it.locked) it.locked - field else it.locked + field, toggled = it.toggled + field, dirty = true)
    }

    override fun setLockAll(on: Boolean) = _state.update { it.copy(lockAll = on, dirty = true) }

    override fun save(onDone: (String) -> Unit) {
        val s = _state.value
        if (s.saving || s.loading) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val body = write(s)
            val result = runCatching { api.saveItem(id, body) }
            result.onSuccess {
                item = body
                loaded = s.copy(dirty = false, saving = false, locked = locks(s))
                _state.value = loaded
                onDone(t("Saved {name}", "name" to s.title.ifBlank { s.name }))
            }.onFailure { e ->
                if (e is CancellationException) throw e
                _state.update { it.copy(saving = false, error = t("Couldn’t save it. {error}", "error" to errorText(e))) }
            }
        }
    }

    /** The locks to save: the ones set here, plus every lockable field that changed. */
    private fun locks(s: MetadataState): Set<String> {
        val l = loaded
        val changed = buildSet {
            if (s.title != l.title) add("Name")
            if (s.overview != l.overview) add("Overview")
            if (s.genres != l.genres) add("Genres")
            if (s.tags != l.tags) add("Tags")
            if (s.studios != l.studios) add("Studios")
            if (s.rating != l.rating) add("OfficialRating")
            if (s.people != l.people) add("Cast")
        }
        return s.locked + changed
    }

    private fun write(s: MetadataState): JsonObject {
        val out = item.toMutableMap()
        fun str(key: String, v: String) {
            out[key] = if (v.isBlank()) JsonNull else JsonPrimitive(v.trim())
        }
        fun list(key: String, v: List<String>) {
            out[key] = JsonArray(v.map { JsonPrimitive(it) })
        }
        str("Name", s.title)
        str("OriginalTitle", s.originalTitle)
        str("ForcedSortName", s.sortTitle)
        out["PremiereDate"] = s.premiere?.let { JsonPrimitive(isoDay(it)) } ?: JsonNull
        if (s.premiere != loaded.premiere) out["ProductionYear"] = s.premiere?.let { JsonPrimitive(it.year) } ?: JsonNull
        if (s.isSeries) {
            out["EndDate"] = s.ended?.let { JsonPrimitive(isoDay(it)) } ?: JsonNull
            str("Status", s.status)
        }
        str("OfficialRating", s.rating)
        str("Overview", s.overview)
        list("Genres", s.genres)
        list("Tags", s.tags)
        out["Studios"] = JsonArray(s.studios.map { n -> buildJsonObject { put("Name", n) } })
        out["CommunityRating"] = s.community.replace(',', '.').toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonNull
        out["CriticRating"] = s.critic.replace(',', '.').toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonNull
        out["People"] = JsonArray(s.people.map { p ->
            buildJsonObject {
                put("Name", p.name)
                if (p.role.isNotBlank()) put("Role", p.role)
                put("Type", p.type)
                if (p.id != null) put("Id", p.id)
            }
        })
        out["ProviderIds"] = JsonObject(
            ((item["ProviderIds"] as? JsonObject).orEmpty() + s.providers.associate { it.key to (if (it.value.isBlank()) JsonNull else JsonPrimitive(it.value.trim())) })
                .filterValues { it !is JsonNull },
        )
        str("PreferredMetadataLanguage", s.language)
        str("PreferredMetadataCountryCode", s.country)
        if (s.isSeries) str("DisplayOrder", s.displayOrder)
        out["LockedFields"] = JsonArray(locks(s).sorted().map { JsonPrimitive(it) })
        out["LockData"] = JsonPrimitive(s.lockAll)
        return JsonObject(out)
    }

    companion object {
        private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        private fun JsonObject.texts(key: String): List<String> = (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        private fun JsonObject.day(key: String): LocalDate? = text(key).takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.substring(0, 10)) }.getOrNull() }
        private fun JsonObject.number(key: String): String = (this[key] as? JsonPrimitive)?.doubleOrNull?.let { n ->
            if (n % 1.0 == 0.0) n.toLong().toString() else String.format(Locale.US, "%.1f", n)
        }.orEmpty()

        fun isoDay(d: LocalDate): String = d.atStartOfDay(ZoneOffset.UTC).toInstant().toString().replace("Z", ".000Z")

        private val kinds = mapOf("Series" to "Series", "Movie" to "Movie", "Episode" to "Episode", "Season" to "Season", "BoxSet" to "Collection", "MusicAlbum" to "Album", "Audio" to "Song", "Person" to "Person")

        /** The page's state from the item, the editor's options and its last refresh. */
        fun read(i: JsonObject, info: JsonObject?, refreshed: JsonObject?): MetadataState {
            val type = i.text("Type")
            val last = ((refreshed?.get("Items") as? JsonArray)?.firstOrNull() as? JsonObject)?.text("DateLastRefreshed")
                ?.let { runCatching { Instant.parse(it) }.getOrNull() }?.atZone(java.time.ZoneId.systemDefault())?.toLocalDate()
                ?.takeIf { it.year > 2000 }
            val facts = listOfNotNull(
                kinds[type]?.let { t(it) } ?: type.ifEmpty { null },
                i.text("Path").ifEmpty { null },
                last?.let { t("last refreshed {date}", "date" to if (it.year == LocalDate.now().year) dayMonth(it) else fullDate(it)) },
            ).joinToString(" · ")
            val ratings = (info?.get("ParentalRatingOptions") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("Name")?.ifEmpty { null } }
            val current = i.text("OfficialRating")
            val idInfos = (info?.get("ExternalIdInfos") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val ids = (i["ProviderIds"] as? JsonObject).orEmpty()
            val providers = (idInfos.map { it.text("Key") to it.text("Name") } + ids.keys.map { it to it })
                .distinctBy { it.first.lowercase() }
                .map { (key, label) -> ProviderField(key, providerLabel(key, label), (ids[key] as? JsonPrimitive)?.contentOrNull.orEmpty()) }
            val languages = (info?.get("Cultures") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .map { Choice(it.text("TwoLetterISOLanguageName").ifEmpty { it.text("ThreeLetterISOLanguageName") }, it.text("DisplayName")) }
                .filter { it.value.isNotEmpty() }
            val countries = (info?.get("Countries") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .map { Choice(it.text("TwoLetterISORegionName"), it.text("DisplayName")) }.filter { it.value.isNotEmpty() }
            val people = (i["People"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .map { MetaPerson(it.text("Name"), it.text("Role"), it.text("Type").ifEmpty { "Actor" }, it.text("Id").ifEmpty { null }) }
            return MetadataState(
                loading = false,
                name = i.text("Name"),
                facts = facts,
                type = type,
                title = i.text("Name"),
                originalTitle = i.text("OriginalTitle"),
                sortTitle = i.text("ForcedSortName"),
                premiere = i.day("PremiereDate"),
                ended = i.day("EndDate"),
                status = i.text("Status"),
                rating = current,
                ratings = (if (current.isNotEmpty() && current !in ratings) listOf(current) else emptyList<String>()) + ratings,
                overview = i.text("Overview"),
                genres = i.texts("Genres"),
                tags = i.texts("Tags"),
                studios = (i["Studios"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("Name")?.ifEmpty { null } },
                community = i.number("CommunityRating"),
                critic = i.number("CriticRating"),
                people = people,
                providers = providers,
                language = i.text("PreferredMetadataLanguage"),
                country = i.text("PreferredMetadataCountryCode"),
                languages = languages,
                countries = countries,
                displayOrder = i.text("DisplayOrder"),
                locked = i.texts("LockedFields").toSet(),
                lockAll = (i["LockData"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }

        /** "TMDb", "TVDb", "IMDb" as the boards write them. */
        fun providerLabel(key: String, name: String): String = when (key.lowercase()) {
            "tmdb" -> "TMDb"
            "tvdb" -> "TVDb"
            "imdb" -> "IMDb"
            "tmdbcollection" -> t("TMDb collection")
            "zap2it" -> "Zap2It"
            "musicbrainzartist" -> "MusicBrainz"
            else -> name.ifEmpty { key }
        }

        /** Series display orders Jellyfin knows. */
        val displayOrders = listOf("" to "Aired", "absolute" to "Absolute", "dvd" to "DVD", "originalAirDate" to "Original air date", "official" to "Official")
    }
}

private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
