package io.github.arkmind.seedy.core.admin.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.admin.AdminHttp
import io.github.arkmind.seedy.core.admin.ItemsResult
import io.github.arkmind.seedy.core.admin.TaskInfo
import io.github.arkmind.seedy.core.admin.VirtualFolder
import io.github.arkmind.seedy.core.admin.atLabel
import io.github.arkmind.seedy.core.admin.dayDiff
import io.github.arkmind.seedy.core.admin.errorText
import io.github.arkmind.seedy.core.admin.hhmm
import io.github.arkmind.seedy.core.admin.realDate
import io.github.arkmind.seedy.core.format.dayMonth
import io.github.arkmind.seedy.core.i18n.formatNumber
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.ImageType
import java.time.Clock
import java.util.UUID

/*
 * Server › Libraries (board "Android · Server · Libraries", web/src/screens/server/Libraries.tsx): a card per library
 * with what's in it and its scan, scans and metadata refreshes, and adding a library. Folders and scans refresh every
 * few seconds while it's open.
 */

/** English names of the kinds of library (show them with t()). */
private val TypeLabels = mapOf(
    "movies" to "Movies", "tvshows" to "Shows", "music" to "Music", "musicvideos" to "Music videos", "homevideos" to "Home videos",
    "boxsets" to "Collections", "books" to "Books", "photos" to "Photos", "playlists" to "Playlists", "livetv" to "Live TV", "mixed" to "Mixed",
)

fun libraryTypeLabel(type: String?): String = t(TypeLabels[type ?: "mixed"] ?: "Mixed")

/** What a library counts on its card: "248 items", "61 series" ("1 series" has its own {count} key for French). */
private class CountAs(val types: String?, val one: String, val many: String)

private val CountUnits = mapOf(
    "movies" to CountAs("Movie", "{n} item", "{n} items"),
    "tvshows" to CountAs("Series", "{count} series", "{n} series"),
    "music" to CountAs("MusicAlbum", "{n} album", "{n} albums"),
    "books" to CountAs("Book", "{n} book", "{n} books"),
    "musicvideos" to CountAs("MusicVideo", "{n} item", "{n} items"),
    "boxsets" to CountAs("BoxSet", "{n} collection", "{n} collections"),
)

private val DefaultUnit = CountAs(null, "{n} item", "{n} items")

/** "Movies · 248 items · 2 folders". */
fun libraryMeta(folder: VirtualFolder, count: Int?): String {
    val unit = CountUnits[folder.collectionType.orEmpty()] ?: DefaultUnit
    return listOfNotNull(
        libraryTypeLabel(folder.collectionType),
        count?.let { tn(it, unit.one, unit.many, "count" to formatNumber(it)) },
        folder.locations.size.takeIf { it > 0 }?.let { tn(it, "{n} folder", "{n} folders") },
    ).joinToString(" · ")
}

/** How many titles a library holds (GET /Items with limit 0). */
suspend fun countLibrary(http: AdminHttp, folder: VirtualFolder): Int {
    val types = CountUnits[folder.collectionType.orEmpty()]?.types
    val q = mutableMapOf<String, Any?>(
        "parentId" to folder.itemId, "recursive" to true, "limit" to 0, "enableTotalRecordCount" to true, "enableImages" to false, "enableUserData" to false,
    )
    if (types != null) q["includeItemTypes"] = types else q["excludeItemTypes"] = "Folder,CollectionFolder,Season"
    return http.get<ItemsResult<JsonObject>>("/Items", q).totalRecordCount
}

/** "Scanned today at 18:30", "Scanned yesterday", "Scanned on 12 Oct". */
fun scannedLabel(end: String?, clock: Clock): String {
    val d = realDate(end) ?: return t("Not scanned yet")
    return when (dayDiff(d, clock)) {
        0L -> t("Scanned today at {time}", "time" to hhmm(d, clock))
        1L -> t("Scanned yesterday")
        else -> t("Scanned on {date}", "date" to dayMonth(d.atZone(clock.zone).toLocalDate()))
    }
}

/** POST /Items/{id}/Refresh: a scan finds new and changed files; a metadata refresh looks everything up again. */
suspend fun refreshLibrary(http: AdminHttp, id: String, metadata: Boolean) {
    val mode = if (metadata) "FullRefresh" else "Default"
    http.send("/Items/$id/Refresh", query = mapOf("Recursive" to true, "ImageRefreshMode" to mode, "MetadataRefreshMode" to mode, "ReplaceAllImages" to false, "ReplaceAllMetadata" to false))
}

data class LibraryCard(
    val id: String,
    val name: String,
    /** "Movies · 248 items · 2 folders". */
    val meta: String,
    /** "Scanned today at 18:30" or "Scanning · 42%". */
    val state: String,
    val scanning: Boolean,
    val progress: Float,
    val art: String?,
    /** A scan of every library is running and can be stopped from here. */
    val canStop: Boolean = false,
)

/** A kind of library to add: the value Jellyfin takes, its label and the name a new one starts with (English). */
data class LibraryKind(val value: String, val label: String, val name: String)

val NewLibraryKinds = listOf(
    LibraryKind("movies", "Movies", "Movies"),
    LibraryKind("tvshows", "Shows", "Shows"),
    LibraryKind("music", "Music", "Music"),
    LibraryKind("musicvideos", "Music videos", "Music videos"),
    LibraryKind("homevideos", "Home videos and photos", "Home videos"),
    LibraryKind("books", "Books", "Books"),
    LibraryKind("mixed", "Movies and shows, mixed", "Movies and shows"),
)

data class NewLibraryForm(
    val kind: String = "movies",
    val name: String = "",
    val nameTouched: Boolean = false,
    val paths: List<String> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    /** Another library has this name. */
    val taken: Boolean = false,
)

data class LibrariesState(
    /** "4 libraries · last full scan today at 18:30. Open one for…"; null while loading. */
    val lede: String? = null,
    /** Null while loading. */
    val cards: List<LibraryCard>? = null,
    val loadError: String? = null,
    /** Scan all is running. */
    val scanning: Boolean = false,
    val adding: NewLibraryForm? = null,
    val picker: FolderPicker? = null,
    val message: String? = null,
    /** A library was just added: open its page (then [LibrariesActions.opened]). */
    val open: String? = null,
)

interface LibrariesActions : FolderPickerActions {
    fun retry() {}
    fun scanAll() {}
    fun scan(id: String) {}
    fun stopScan() {}
    fun refreshMetadata(id: String) {}
    fun startAdd() {}
    fun closeAdd() {}
    fun setKind(kind: String) {}
    fun setName(name: String) {}
    fun addPath() {}
    fun removePath(path: String) {}
    fun submit() {}
    fun opened() {}
    fun messageShown() {}
}

class LibrariesViewModel(
    private val session: UserSession,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel(), LibrariesActions {
    private val http = AdminHttp(session.api)
    private val _state = MutableStateFlow(LibrariesState())
    val state: StateFlow<LibrariesState> = _state.asStateFlow()
    private val browser = FolderBrowser(http, viewModelScope)

    private var folders: List<VirtualFolder>? = null
    private var tasks: List<TaskInfo>? = null
    private var counts: Map<String, Int> = emptyMap()
    private var countedFor = ""

    init {
        viewModelScope.launch { browser.state.collect { p -> _state.update { it.copy(picker = p) } } }
    }

    fun load() {
        loadFolders()
        loadTasks()
    }

    /** Folders and tasks every 5 s until cancelled. */
    suspend fun live() {
        while (true) {
            delay(5_000)
            load()
        }
    }

    private fun loadFolders() {
        viewModelScope.launch {
            runCatching { http.get<List<VirtualFolder>>("/Library/VirtualFolders") }
                .onSuccess { folders = it; _state.update { s -> s.copy(loadError = null) }; publish(); countAll(it) }
                .onFailure { if (it is CancellationException) throw it; if (folders == null) _state.update { s -> s.copy(loadError = t("Libraries unavailable")) } }
        }
    }

    private fun loadTasks() {
        viewModelScope.launch {
            runCatching { http.get<List<TaskInfo>>("/ScheduledTasks", mapOf("isHidden" to false)) }.onSuccess { tasks = it; publish() }
        }
    }

    private fun countAll(list: List<VirtualFolder>) {
        val key = list.joinToString(",") { it.itemId }
        if (key == countedFor) return
        countedFor = key
        viewModelScope.launch {
            val found = coroutineScope {
                list.map { f -> async { runCatching { f.itemId to countLibrary(http, f) }.getOrNull() } }.awaitAll()
            }
            counts = found.filterNotNull().toMap()
            publish()
        }
    }

    private fun publish() {
        val list = folders ?: return
        val scanTask = tasks?.firstOrNull { it.key == "RefreshLibrary" }
        val running = scanTask?.state == "Running"
        val lastEnd = scanTask?.lastExecutionResult?.endTimeUtc
        val status = when {
            running -> t("scanning now") + (scanTask?.currentProgressPercentage?.let { " · " + percentLabel(it) } ?: "")
            realDate(lastEnd) != null -> t("last full scan {when}", "when" to atLabel(realDate(lastEnd)!!, clock))
            else -> t("not scanned yet")
        }
        val lede = t(
            "{libraries} · {status}. Open one for folders, metadata, subtitles and previews.",
            "libraries" to tn(list.size, "{n} library", "{n} libraries"),
            "status" to status,
        )
        val cards = list.map { f ->
            val progress = f.refreshProgress ?: if (f.refreshStatus == "Active") 0.0 else if (running) scanTask?.currentProgressPercentage ?: 0.0 else null
            LibraryCard(
                id = f.itemId,
                name = f.name,
                meta = libraryMeta(f, counts[f.itemId]),
                state = if (progress != null) t("Scanning · {pct}", "pct" to percentLabel(progress)) else scannedLabel(lastEnd, clock),
                scanning = progress != null,
                progress = ((progress ?: 0.0) / 100).toFloat().coerceIn(0f, 1f),
                art = f.primaryImageItemId?.let { id -> runCatching { session.api.imageApi.getItemImageUrl(UUID.fromString(dashed(id)), ImageType.PRIMARY, maxWidth = 240) }.getOrNull() },
                canStop = progress != null && running,
            )
        }
        _state.update { it.copy(lede = lede, cards = cards, scanning = running) }
        _state.value.adding?.let { form -> _state.update { it.copy(adding = form.copy(taken = taken(form.name))) } }
    }

    private fun say(text: String) = _state.update { it.copy(message = text) }
    override fun messageShown() = _state.update { it.copy(message = null) }
    override fun retry() = load()

    override fun scanAll() {
        viewModelScope.launch {
            runCatching { http.send("/Library/Refresh") }
                .onSuccess { say(t("Scanning all libraries")); delay(600); load() }
                .onFailure { say(t("Couldn’t start the scan. {error}", "error" to errorText(it))) }
        }
    }

    override fun scan(id: String) = refresh(id, metadata = false)
    override fun refreshMetadata(id: String) = refresh(id, metadata = true)

    private fun refresh(id: String, metadata: Boolean) {
        val name = folders?.firstOrNull { it.itemId == id }?.name.orEmpty()
        viewModelScope.launch {
            runCatching { refreshLibrary(http, id, metadata) }
                .onSuccess { say(if (metadata) t("Refreshing metadata for {name}", "name" to name) else t("Scanning {name}", "name" to name)); delay(800); load() }
                .onFailure { say(t("Couldn’t start it. {error}", "error" to errorText(it))) }
        }
    }

    override fun stopScan() {
        val task = tasks?.firstOrNull { it.key == "RefreshLibrary" } ?: return
        viewModelScope.launch {
            runCatching { http.delete("/ScheduledTasks/Running/${task.id}") }
                .onSuccess { say(t("Stopping the scan")); delay(800); load() }
                .onFailure { say(t("Couldn’t stop it. {error}", "error" to errorText(it))) }
        }
    }

    /* ── Add library ── */

    private fun taken(name: String) = folders.orEmpty().any { it.name.equals(name.trim(), ignoreCase = true) }

    private fun form(change: (NewLibraryForm) -> NewLibraryForm) = _state.update { s -> s.adding?.let { s.copy(adding = change(it).let { f -> f.copy(taken = taken(f.name)) }) } ?: s }

    override fun startAdd() = _state.update { it.copy(adding = NewLibraryForm(name = t("Movies"), taken = taken(t("Movies")))) }
    override fun closeAdd() = _state.update { it.copy(adding = null) }

    override fun setKind(kind: String) = form { f ->
        val k = NewLibraryKinds.firstOrNull { it.value == kind }
        f.copy(kind = kind, name = if (f.nameTouched) f.name else k?.let { t(it.name) }.orEmpty())
    }

    override fun setName(name: String) = form { it.copy(name = name.take(80), nameTouched = true) }
    override fun removePath(path: String) = form { f -> f.copy(paths = f.paths - path) }

    override fun addPath() {
        browser.open(t("Add a folder"), t("Add folder"), "/") { p -> form { f -> if (p in f.paths) f else f.copy(paths = f.paths + p) } }
    }

    override fun browse(path: String) = browser.go(path)
    override fun pickFolder(path: String) = browser.pick(path)
    override fun closePicker() = browser.close()

    override fun submit() {
        val f = _state.value.adding ?: return
        val name = f.name.trim()
        if (name.isEmpty() || f.taken || f.paths.isEmpty() || f.busy) return
        form { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val type = f.kind.takeIf { it != "mixed" }
            val result = runCatching {
                val avail = http.get<AvailableOptions>("/Libraries/AvailableOptions", mapOf("libraryContentType" to type, "isNewLibrary" to true))
                val cfg = http.getObject("/System/Configuration")
                val options = newLibraryOptions(avail, cfg.str("PreferredMetadataLanguage"), cfg.str("MetadataCountryCode"), f.paths)
                http.send("/Library/VirtualFolders", buildJsonObject { put("LibraryOptions", options) }, mapOf("name" to name, "collectionType" to type, "refreshLibrary" to true))
            }
            result.onSuccess {
                say(t("{name} was added. Jellyfin is scanning it now.", "name" to name))
                _state.update { it.copy(adding = null) }
                val list = runCatching { http.get<List<VirtualFolder>>("/Library/VirtualFolders") }.getOrNull()
                if (list != null) {
                    folders = list
                    publish()
                    countAll(list)
                    list.firstOrNull { it.name == name }?.let { created -> _state.update { it.copy(open = created.itemId) } }
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                form { it.copy(busy = false, error = failText(e, t("Jellyfin didn’t add the library."))) }
            }
        }
    }

    override fun opened() = _state.update { it.copy(open = null) }
}

/** A new library's options, as the web dashboard starts them: Jellyfin's defaults for that kind, and [paths]. */
fun newLibraryOptions(avail: AvailableOptions, language: String?, country: String?, paths: List<String>): JsonObject = buildJsonObject {
    put("Enabled", true)
    put("EnableRealtimeMonitor", true)
    put("EnableChapterImageExtraction", false)
    put("ExtractChapterImagesDuringLibraryScan", false)
    put("EnableTrickplayImageExtraction", true)
    put("ExtractTrickplayImagesDuringLibraryScan", false)
    put("SaveLocalMetadata", false)
    put("SaveTrickplayWithMedia", false)
    put("AutomaticallyAddToCollection", false)
    put("SkipSubtitlesIfEmbeddedSubtitlesPresent", false)
    put("SkipSubtitlesIfAudioTrackMatches", true)
    put("RequirePerfectSubtitleMatch", true)
    put("SaveSubtitlesWithMedia", true)
    put("AutomaticRefreshIntervalDays", 0)
    put("PreferredMetadataLanguage", json(language))
    put("MetadataCountryCode", json(country))
    put("PathInfos", JsonArray(paths.map { buildJsonObject { put("Path", it) } }))
    put("DisabledSubtitleFetchers", JsonArray(emptyList()))
    put("SubtitleFetcherOrder", json(avail.subtitleFetchers.map { it.name }))
    put("DisabledMediaSegmentProviders", JsonArray(emptyList()))
    put("MediaSegmentProviderOrder", json(avail.mediaSegmentProviders.map { it.name }))
    put("TypeOptions", JsonArray(avail.typeOptions.map { t ->
        buildJsonObject {
            put("Type", t.type)
            put("MetadataFetchers", json(t.metadataFetchers.filter { it.defaultEnabled }.map { it.name }))
            put("MetadataFetcherOrder", json(t.metadataFetchers.map { it.name }))
            put("ImageFetchers", json(t.imageFetchers.filter { it.defaultEnabled }.map { it.name }))
            put("ImageFetcherOrder", json(t.imageFetchers.map { it.name }))
            put("ImageOptions", t.defaultImageOptions ?: JsonArray(emptyList()))
            put("SimilarItemProviders", json(t.similarItemProviders.filter { it.defaultEnabled }.map { it.name }))
            put("SimilarItemProviderOrder", json(t.similarItemProviders.map { it.name }))
        }
    }))
}

/** Jellyfin's ids come without dashes; UUID wants them. */
fun dashed(id: String): String {
    if (id.contains('-') || id.length != 32) return id
    return "${id.substring(0, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}"
}
