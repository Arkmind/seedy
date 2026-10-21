package io.github.arkmind.seedy.core.admin.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.admin.AdminHttp
import io.github.arkmind.seedy.core.admin.TaskInfo
import io.github.arkmind.seedy.core.admin.VirtualFolder
import io.github.arkmind.seedy.core.admin.errorText
import io.github.arkmind.seedy.core.admin.sameId
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.session.UserSession
import io.github.arkmind.seedy.core.settings.Choice
import kotlinx.coroutines.CancellationException
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock

/*
 * One library (Server › Libraries › a library; web/src/screens/server/Libraries.tsx LibraryEditor): its folders,
 * metadata language and sources, subtitle downloads, scanning and previews. Options save as they change: Jellyfin
 * replaces the whole LibraryOptions, so a save puts only the options changed here on top of what the server has now,
 * one save at a time.
 */

/* ── Sources: Jellyfin keeps an order per item type and role, Seedy shows one merged list ── */

/** Names in [order] first (as far as [names] has them), then the rest of [names]. */
fun orderByList(names: List<String>, order: List<String>): List<String> = order.filter { it in names } + names.filter { it !in order }

/** The items of [extra] missing from [base], each put just before the next item it precedes, so both orders hold. */
fun mergeOrder(base: List<String>, extra: List<String>): List<String> {
    val out = base.toMutableList()
    extra.forEachIndexed { i, n ->
        if (n in out) return@forEachIndexed
        val next = extra.drop(i + 1).firstOrNull { it in out }
        if (next != null) out.add(out.indexOf(next), n) else out.add(n)
    }
    return out
}

data class Source(val name: String, val meta: Boolean, val image: Boolean, val enabled: Boolean)

private fun JsonObject.typeOptions(type: String): JsonObject? = objects("TypeOptions").firstOrNull { it.str("Type") == type }

fun buildSources(opts: JsonObject, avail: AvailableOptions): List<Source> {
    var order = listOf<String>()
    val meta = mutableSetOf<String>()
    val image = mutableSetOf<String>()
    val enabled = mutableSetOf<String>()
    for (t in avail.typeOptions) {
        val own = opts.typeOptions(t.type)
        val metaNames = t.metadataFetchers.map { it.name }
        val imgNames = t.imageFetchers.map { it.name }
        meta += metaNames
        image += imgNames
        val metaOrder = if (own != null) orderByList(metaNames, own.strings("MetadataFetcherOrder")) else metaNames
        val imgOrder = if (own != null) orderByList(imgNames, own.strings("ImageFetcherOrder")) else imgNames
        order = mergeOrder(order, mergeOrder(imgOrder, metaOrder))
        if (own != null) {
            enabled += own.strings("MetadataFetchers")
            enabled += own.strings("ImageFetchers")
        } else {
            enabled += t.metadataFetchers.filter { it.defaultEnabled }.map { it.name }
            enabled += t.imageFetchers.filter { it.defaultEnabled }.map { it.name }
        }
    }
    return order.map { Source(it, it in meta, it in image, it in enabled) }
}

/** Who feeds "More like this": every provider there is, and the ones chosen, in order. */
fun similarState(opts: JsonObject, avail: AvailableOptions): Pair<List<String>, List<String>> {
    val first = avail.typeOptions.firstOrNull { it.similarItemProviders.isNotEmpty() } ?: return emptyList<String>() to emptyList()
    val all = first.similarItemProviders.map { it.name }
    val own = opts.typeOptions(first.type)
    if (own != null && own["SimilarItemProviders"] is JsonArray) {
        val order = (own.strings("SimilarItemProviderOrder") + all).distinct().filter { it in all }
        val chosen = own.strings("SimilarItemProviders")
        return all to order.filter { it in chosen }
    }
    return all to first.similarItemProviders.filter { it.defaultEnabled }.map { it.name }
}

/** [opts] with every item type's sources set from [sources] (and "More like this" from [similar], when given). */
fun applyTypeOptions(opts: JsonObject, avail: AvailableOptions, sources: List<Source>, similar: List<String>?): JsonObject {
    val order = sources.map { it.name }
    val on = sources.filter { it.enabled }.map { it.name }.toSet()
    val types = avail.typeOptions.map { t ->
        val own = opts.typeOptions(t.type)
        val metaNames = t.metadataFetchers.map { it.name }
        val imgNames = t.imageFetchers.map { it.name }
        val simNames = t.similarItemProviders.map { it.name }
        val sim = similar?.filter { it in simNames }
            ?: own?.takeIf { it["SimilarItemProviders"] is JsonArray }?.strings("SimilarItemProviders")
            ?: t.similarItemProviders.filter { it.defaultEnabled }.map { it.name }
        JsonObject(
            (own ?: JsonObject(emptyMap())) + mapOf(
                "Type" to json(t.type),
                "MetadataFetchers" to json(order.filter { it in metaNames && it in on }),
                "MetadataFetcherOrder" to json(order.filter { it in metaNames }),
                "ImageFetchers" to json(order.filter { it in imgNames && it in on }),
                "ImageFetcherOrder" to json(order.filter { it in imgNames }),
                "ImageOptions" to (own?.get("ImageOptions") ?: t.defaultImageOptions ?: JsonArray(emptyList())),
                "SimilarItemProviders" to json(sim),
                "SimilarItemProviderOrder" to json(sim + simNames.filter { it !in sim }),
            ),
        )
    }
    val others = opts.objects("TypeOptions").filter { o -> avail.typeOptions.none { it.type == o.str("Type") } }
    return opts.with("TypeOptions", JsonArray(types + others))
}

/** English labels for sources whose names say little (shown with t()). */
private val ImageOnlyNames = mapOf(
    "Embedded Image Extractor" to ("Embedded image extractor" to "Artwork from the file"),
    "Screen Grabber" to ("Screen grabber" to "Frame from the video"),
)

private fun similarLabel(n: String) = if (n == "Local Genre/Tag") t("Jellyfin, built in") else n
private fun similarShort(n: String) = when (n) {
    "Local Genre/Tag" -> "Jellyfin"
    "TheMovieDb" -> "TMDb"
    else -> n
}

/** The "Recommendations from" choices, as the web's select offers them. */
fun similarChoices(all: List<String>, chosen: List<String>): List<Choice<List<String>>> {
    val out = all.map { Choice(listOf(it), similarLabel(it)) }.toMutableList()
    if (all.size == 2) {
        out += Choice(listOf(all[0], all[1]), t("Both, {name} first", "name" to similarShort(all[0])))
        out += Choice(listOf(all[1], all[0]), t("Both, {name} first", "name" to similarShort(all[1])))
    } else if (all.size > 2) {
        for (n in all) out += Choice(listOf(n) + all.filter { it != n }, t("All, {name} first", "name" to similarShort(n)))
    }
    out += Choice(emptyList(), t("Nothing"))
    return if (out.any { it.value == chosen }) out else listOf(Choice(chosen, chosen.joinToString(t(", then ")) { similarShort(it) })) + out
}

/* ── The page ── */

data class SourceRow(val name: String, val label: String, val kind: String, val enabled: Boolean)

/** A switch on the page: [key] is what [LibraryActions.setSwitch] takes. */
data class SwitchItem(val key: String, val name: String, val help: String? = null, val on: Boolean)

data class LanguageChip(val code: String, val name: String)

private val RefreshDays = listOf(0, 30, 60, 90, 180, 365)

fun refreshLabel(days: Int): String = when (days) {
    0 -> t("Never")
    365 -> t("Year")
    else -> tn(days, "{n} day", "{n} days")
}

data class LibraryState(
    val title: String = "",
    /** "Movies · 248 items · 2 folders"; null while loading. */
    val lede: String? = null,
    val loadError: String? = null,
    /** "Scanning · 42%" while a scan runs. */
    val scanState: String? = null,
    val scanProgress: Float = 0f,
    val folders: List<String> = emptyList(),
    val language: String = "",
    val languageValue: String = "",
    val languageOptions: List<Choice<String>> = emptyList(),
    val country: String = "",
    val countryValue: String = "",
    val countryOptions: List<Choice<String>> = emptyList(),
    val refresh: String = "",
    val refreshValue: Int = 0,
    val refreshOptions: List<Choice<Int>> = emptyList(),
    /** Null while loading. */
    val sources: List<SourceRow>? = null,
    val sourcesFailed: Boolean = false,
    val similar: String? = null,
    val similarValue: List<String> = emptyList(),
    val similarOptions: List<Choice<List<String>>> = emptyList(),
    val subtitleLanguages: List<LanguageChip> = emptyList(),
    /** Languages that can be added (every culture not chosen yet). */
    val moreLanguages: List<LanguageChip> = emptyList(),
    val subtitleFetchers: List<SwitchItem> = emptyList(),
    val subtitleSwitches: List<SwitchItem> = emptyList(),
    val scanSwitches: List<SwitchItem> = emptyList(),
    /** Null while loading. */
    val segmentProviders: List<SwitchItem>? = null,
    val saving: Boolean = false,
    val error: String? = null,
    val picker: FolderPicker? = null,
    val message: String? = null,
    /** The library was removed: leave the page. */
    val removed: Boolean = false,
)

interface LibraryActions : FolderPickerActions {
    fun retry() {}
    fun scan() {}
    fun refreshMetadata() {}
    fun addFolder() {}
    fun removeFolder(path: String) {}
    fun setLanguage(code: String) {}
    fun setCountry(code: String) {}
    fun setRefresh(days: Int) {}
    fun setSource(name: String, on: Boolean) {}
    fun moveSource(from: Int, to: Int) {}
    fun setSimilar(names: List<String>) {}
    fun addSubtitleLanguage(code: String) {}
    fun removeSubtitleLanguage(code: String) {}
    fun setSwitch(key: String, on: Boolean) {}
    fun removeLibrary() {}
    fun messageShown() {}
}

class LibraryViewModel(
    session: UserSession,
    private val id: String,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel(), LibraryActions {
    private val http = AdminHttp(session.api)
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()
    private val browser = FolderBrowser(http, viewModelScope)

    private var folder: VirtualFolder? = null
    private var opts = JsonObject(emptyMap())
    private var avail: AvailableOptions? = null
    private var availFailed = false
    private var cultures: List<Culture> = emptyList()
    private var countries: List<Country> = emptyList()
    private var count: Int? = null
    private var scanTask: TaskInfo? = null

    /** Options changed here and not saved yet. */
    private val dirty = mutableSetOf<String>()
    private val saveLock = Mutex()
    private var timer: Job? = null
    private var version = 0
    /** Saves finish even after the page is closed. */
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        viewModelScope.launch { browser.state.collect { p -> _state.update { it.copy(picker = p) } } }
    }

    fun load() {
        viewModelScope.launch {
            val list = runCatching { http.get<List<VirtualFolder>>("/Library/VirtualFolders") }
            val f = list.getOrNull()?.firstOrNull { sameId(it.itemId, id) }
            if (f == null) {
                _state.update { it.copy(loadError = if (list.isFailure) t("Couldn’t load the library.") else t("This library isn’t on the server any more.")) }
                return@launch
            }
            folder = f
            opts = f.libraryOptions ?: JsonObject(emptyMap())
            _state.update { it.copy(loadError = null) }
            publish()
            loadAvailable(f)
            viewModelScope.launch { count = runCatching { countLibrary(http, f) }.getOrNull(); publish() }
            viewModelScope.launch {
                val c = async { runCatching { http.get<List<Culture>>("/Localization/Cultures") }.getOrNull() }
                val k = async { runCatching { http.get<List<Country>>("/Localization/Countries") }.getOrNull() }
                cultures = c.await().orEmpty()
                countries = k.await().orEmpty()
                publish()
            }
            loadTask()
        }
    }

    private fun loadAvailable(f: VirtualFolder) {
        viewModelScope.launch {
            availFailed = false
            publish()
            runCatching { http.get<AvailableOptions>("/Libraries/AvailableOptions", mapOf("libraryContentType" to f.collectionType, "isNewLibrary" to false)) }
                .onSuccess { avail = it }
                .onFailure { if (it is CancellationException) throw it; availFailed = true }
            publish()
        }
    }

    private fun loadTask() {
        viewModelScope.launch {
            scanTask = runCatching { http.get<List<TaskInfo>>("/ScheduledTasks", mapOf("isHidden" to false)) }.getOrNull()?.firstOrNull { it.key == "RefreshLibrary" } ?: scanTask
            publish()
        }
    }

    /** The scan's progress and the folders every 5 s until cancelled. */
    suspend fun live() {
        while (true) {
            delay(5_000)
            val f = runCatching { http.get<List<VirtualFolder>>("/Library/VirtualFolders") }.getOrNull()?.firstOrNull { sameId(it.itemId, id) }
            if (f != null) folder = folder?.copy(locations = f.locations, refreshProgress = f.refreshProgress, refreshStatus = f.refreshStatus, name = f.name) ?: f
            loadTask()
        }
    }

    private fun publish() {
        val f = folder ?: return
        val a = avail
        val lang = opts.str("PreferredMetadataLanguage").orEmpty()
        val country = opts.str("MetadataCountryCode").orEmpty()
        val days = opts.int("AutomaticRefreshIntervalDays")
        val langOptions = listOf(Choice("", t("Server default"))) + cultures.filter { it.twoLetterISOLanguageName.isNotEmpty() }.map { Choice(it.twoLetterISOLanguageName, it.displayName) }
        val countryOptions = listOf(Choice("", t("Server default"))) + countries.map { Choice(it.twoLetterISORegionName, it.displayName) }
        val running = scanTask?.state == "Running"
        val progress = f.refreshProgress ?: if (f.refreshStatus == "Active") 0.0 else if (running) scanTask?.currentProgressPercentage ?: 0.0 else null
        val (simAll, simChosen) = if (a != null) similarState(opts, a) else emptyList<String>() to emptyList()
        val simOptions = if (simAll.isNotEmpty()) similarChoices(simAll, simChosen) else emptyList()
        val codes = opts.strings("SubtitleDownloadLanguages")
        fun nameOf(code: String) = cultures.firstOrNull { it.threeLetterISOLanguageName == code || code in it.threeLetterISOLanguageNames }?.displayName ?: code
        val movies = f.collectionType == null || f.collectionType == "movies" || f.collectionType == "mixed"
        val disabledSubs = opts.strings("DisabledSubtitleFetchers")
        val disabledSegments = opts.strings("DisabledMediaSegmentProviders")
        _state.update {
            it.copy(
                title = f.name,
                lede = libraryMeta(f, count),
                scanState = progress?.let { p -> t("Scanning · {pct}", "pct" to percentLabel(p)) },
                scanProgress = ((progress ?: 0.0) / 100).toFloat().coerceIn(0f, 1f),
                folders = f.locations,
                language = langOptions.firstOrNull { o -> o.value == lang }?.label ?: lang,
                languageValue = lang,
                languageOptions = if (langOptions.any { o -> o.value == lang }) langOptions else langOptions + Choice(lang, lang),
                country = countryOptions.firstOrNull { o -> o.value == country }?.label ?: country,
                countryValue = country,
                countryOptions = if (countryOptions.any { o -> o.value == country }) countryOptions else countryOptions + Choice(country, country),
                refresh = refreshLabel(days),
                refreshValue = days,
                refreshOptions = (if (days in RefreshDays) RefreshDays else RefreshDays + days).map { d -> Choice(d, refreshLabel(d)) },
                sources = a?.let { av -> buildSources(opts, av).map(::sourceRow) },
                sourcesFailed = a == null && availFailed,
                similar = simOptions.firstOrNull { o -> o.value == simChosen }?.label,
                similarValue = simChosen,
                similarOptions = simOptions,
                subtitleLanguages = codes.map { c -> LanguageChip(c, nameOf(c)) },
                moreLanguages = cultures.filter { c -> c.threeLetterISOLanguageName.isNotEmpty() && c.threeLetterISOLanguageName !in codes }.map { c -> LanguageChip(c.threeLetterISOLanguageName, c.displayName) },
                subtitleFetchers = a?.subtitleFetchers.orEmpty().map { s -> SwitchItem("fetcher:" + s.name, t("Download from {name}", "name" to s.name), null, s.name !in disabledSubs) },
                subtitleSwitches = listOf(
                    sw("SkipSubtitlesIfEmbeddedSubtitlesPresent", t("Skip if the file has subtitles")),
                    sw("SkipSubtitlesIfAudioTrackMatches", t("Skip if the audio matches")),
                    sw("RequirePerfectSubtitleMatch", t("Only perfect matches")),
                    sw("SaveSubtitlesWithMedia", t("Save next to the media")),
                ),
                scanSwitches = listOfNotNull(
                    sw("EnableRealtimeMonitor", t("Watch folders for changes")),
                    sw("EnableTrickplayImageExtraction", t("Seek previews (trickplay)")),
                    sw("EnableChapterImageExtraction", t("Chapter images")),
                    sw("SaveTrickplayWithMedia", t("Save previews next to the media")),
                    if (movies) sw("AutomaticallyAddToCollection", t("Add to collections automatically")) else null,
                    sw("SaveLocalMetadata", t("Save artwork next to the media")),
                ),
                segmentProviders = a?.let { av ->
                    orderByList(av.mediaSegmentProviders.map { p -> p.name }, opts.strings("MediaSegmentProviderOrder")).map { n -> SwitchItem("segment:$n", n, null, n !in disabledSegments) }
                },
            )
        }
    }

    private fun sw(key: String, name: String) = SwitchItem(key, name, null, opts.bool(key))

    private fun sourceRow(s: Source): SourceRow {
        val special = ImageOnlyNames[s.name]
        val kind = when {
            s.meta && s.image -> t("Details and artwork")
            s.meta -> t("Details")
            special != null -> t(special.second)
            else -> t("Artwork")
        }
        return SourceRow(s.name, special?.let { t(it.first) } ?: s.name, kind, s.enabled)
    }

    /* ── Saving ── */

    private fun edit(change: (JsonObject) -> JsonObject) {
        val before = opts
        val next = change(before)
        for (k in next.keys + before.keys) if (k != "PathInfos" && next[k] != before[k]) dirty += k
        opts = next
        version++
        _state.update { it.copy(saving = true, error = null) }
        publish()
        timer?.cancel()
        timer = saveScope.launch {
            delay(700)
            flush()
        }
    }

    private fun set(key: String, value: JsonElement) = edit { it.with(key, value) }

    /** Saves what changed here on top of the server's current options. */
    private suspend fun flush() {
        timer = null
        saveLock.withLock {
            val v = version
            val keys = dirty.toList()
            if (keys.isEmpty()) return
            dirty.clear()
            val local = opts
            val ok = runCatching {
                val fresh = http.get<List<VirtualFolder>>("/Library/VirtualFolders").firstOrNull { sameId(it.itemId, id) }?.libraryOptions ?: folder?.libraryOptions ?: JsonObject(emptyMap())
                val next = JsonObject(fresh + keys.associateWith { k -> local[k] ?: kotlinx.serialization.json.JsonNull })
                http.send("/Library/VirtualFolders/LibraryOptions", buildJsonObject { put("Id", folder?.itemId ?: id); put("LibraryOptions", next) })
            }
            if (ok.isFailure) dirty += keys
            if (v == version || ok.isFailure) {
                _state.update { it.copy(saving = false, error = if (ok.isFailure) t("Couldn’t save. Check your connection and try again.") else null) }
            }
        }
    }

    private suspend fun flushNow() {
        timer?.cancel()
        flush()
    }

    override fun onCleared() {
        if (timer != null) {
            timer?.cancel()
            saveScope.launch { flush() }
        }
    }

    /* ── Actions ── */

    private fun say(text: String) = _state.update { it.copy(message = text) }
    override fun messageShown() = _state.update { it.copy(message = null) }
    override fun retry() = load()

    override fun scan() = refresh(metadata = false)
    override fun refreshMetadata() = refresh(metadata = true)

    private fun refresh(metadata: Boolean) {
        val f = folder ?: return
        viewModelScope.launch {
            runCatching { refreshLibrary(http, f.itemId, metadata) }
                .onSuccess { say(if (metadata) t("Refreshing metadata for {name}", "name" to f.name) else t("Scanning {name}", "name" to f.name)); delay(800); loadTask() }
                .onFailure { say(t("Couldn’t start it. {error}", "error" to errorText(it))) }
        }
    }

    private suspend fun reloadFolder() {
        val f = runCatching { http.get<List<VirtualFolder>>("/Library/VirtualFolders") }.getOrNull()?.firstOrNull { sameId(it.itemId, id) } ?: return
        folder = folder?.copy(locations = f.locations, name = f.name) ?: f
        publish()
    }

    override fun addFolder() {
        val f = folder ?: return
        browser.open(t("Add a folder to {name}", "name" to f.name), t("Add folder"), f.locations.firstOrNull()?.let(::parentOf).orEmpty()) { path ->
            viewModelScope.launch {
                runCatching {
                    flushNow()
                    http.send("/Library/VirtualFolders/Paths", buildJsonObject { put("Name", f.name); put("PathInfo", buildJsonObject { put("Path", path) }) }, mapOf("refreshLibrary" to false))
                }.onSuccess { say(t("Added {path}", "path" to path)); reloadFolder() }
                    .onFailure { say(t("Couldn’t add the folder. {error}", "error" to errorText(it))) }
            }
        }
    }

    override fun removeFolder(path: String) {
        val f = folder ?: return
        if (f.locations.size <= 1) return
        viewModelScope.launch {
            runCatching {
                flushNow()
                http.delete("/Library/VirtualFolders/Paths", mapOf("name" to f.name, "path" to path, "refreshLibrary" to false))
            }.onSuccess { say(t("Folder removed")); reloadFolder() }
                .onFailure { say(t("Couldn’t remove it. {error}", "error" to errorText(it))) }
        }
    }

    override fun browse(path: String) = browser.go(path)
    override fun pickFolder(path: String) = browser.pick(path)
    override fun closePicker() = browser.close()

    override fun setLanguage(code: String) = set("PreferredMetadataLanguage", json(code.ifEmpty { null }))
    override fun setCountry(code: String) = set("MetadataCountryCode", json(code.ifEmpty { null }))
    override fun setRefresh(days: Int) = set("AutomaticRefreshIntervalDays", json(days))

    private fun setSources(change: (List<Source>) -> List<Source>) {
        val a = avail ?: return
        edit { o -> applyTypeOptions(o, a, change(buildSources(o, a)), null) }
    }

    override fun setSource(name: String, on: Boolean) = setSources { list -> list.map { if (it.name == name) it.copy(enabled = on) else it } }

    override fun moveSource(from: Int, to: Int) {
        if (from == to) return
        setSources { list ->
            if (from !in list.indices || to !in list.indices) list
            else list.toMutableList().apply { add(to, removeAt(from)) }
        }
    }

    override fun setSimilar(names: List<String>) {
        val a = avail ?: return
        edit { o -> applyTypeOptions(o, a, buildSources(o, a), names) }
    }

    override fun addSubtitleLanguage(code: String) {
        val codes = opts.strings("SubtitleDownloadLanguages")
        if (code !in codes) set("SubtitleDownloadLanguages", json(codes + code))
    }

    override fun removeSubtitleLanguage(code: String) = set("SubtitleDownloadLanguages", json(opts.strings("SubtitleDownloadLanguages") - code))

    override fun setSwitch(key: String, on: Boolean) {
        when {
            key.startsWith("fetcher:") -> {
                val name = key.removePrefix("fetcher:")
                val off = opts.strings("DisabledSubtitleFetchers")
                set("DisabledSubtitleFetchers", json(if (on) off - name else off + name))
            }
            key.startsWith("segment:") -> {
                val name = key.removePrefix("segment:")
                val names = avail?.mediaSegmentProviders.orEmpty().map { it.name }
                edit { o ->
                    val off = o.strings("DisabledMediaSegmentProviders")
                    o.with("DisabledMediaSegmentProviders", json(if (on) off - name else off + name))
                        .with("MediaSegmentProviderOrder", json(orderByList(names, o.strings("MediaSegmentProviderOrder"))))
                }
            }
            // Previews and chapter images are made during scans when they're on.
            key == "EnableTrickplayImageExtraction" -> edit { it.with(key, json(on)).with("ExtractTrickplayImagesDuringLibraryScan", json(on)) }
            key == "EnableChapterImageExtraction" -> edit { it.with(key, json(on)).with("ExtractChapterImagesDuringLibraryScan", json(on)) }
            else -> set(key, json(on))
        }
    }

    override fun removeLibrary() {
        val f = folder ?: return
        viewModelScope.launch {
            timer?.cancel()
            timer = null
            dirty.clear()
            runCatching { http.delete("/Library/VirtualFolders", mapOf("name" to f.name, "refreshLibrary" to true)) }
                .onSuccess { _state.update { it.copy(removed = true, message = t("{name} was removed", "name" to f.name)) } }
                .onFailure { say(t("Couldn’t remove it. {error}", "error" to errorText(it))) }
        }
    }
}
