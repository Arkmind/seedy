package io.github.arkmind.seedy.core.photos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.time.LocalDate
import java.util.UUID

/**
 * What the photo viewer pages through: the list of the page that opened it (its tab, a memory, a shuffle), how many
 * there are in all, and how to load more. The page keeps it up to date while the viewer is open; the viewer reports
 * favourites back through [changed].
 */
object PhotoQueue {
    data class Queue(val items: List<BaseItemDto>, val total: Int, val done: Boolean)

    private val _state = MutableStateFlow<Queue?>(null)
    val state: StateFlow<Queue?> = _state.asStateFlow()
    var more: (() -> Unit)? = null
    var changed: ((BaseItemDto) -> Unit)? = null

    fun set(items: List<BaseItemDto>, total: Int = items.size, done: Boolean = true, more: (() -> Unit)? = null, changed: ((BaseItemDto) -> Unit)? = null) {
        _state.value = Queue(items, total, done)
        this.more = more
        this.changed = changed
    }

    fun update(items: List<BaseItemDto>, total: Int, done: Boolean) {
        _state.value = Queue(items, total, done)
    }

    fun patch(item: BaseItemDto) {
        _state.update { q -> q?.copy(items = q.items.map { if (it.id == item.id) item else it }) }
        changed?.invoke(item)
    }
}

fun BaseItemDto.withFavorite(on: Boolean): BaseItemDto =
    copy(userData = (userData ?: UserItemDataDto(playbackPositionTicks = 0, playCount = 0, isFavorite = false, played = false, key = id.toString(), itemId = id)).copy(isFavorite = on))

/** The photos library's views (the web's Timeline board tabs). */
enum class PhotosTab(val label: String) { Timeline("Timeline"), Folders("Folders"), Videos("Videos"), Favorites("Favorites"), Cameras("Cameras") }

/** Tile sizes (Small · Medium · Large): a row's height on the phone, in dp. */
enum class TileSize(val label: String, val row: Int) { Small("Small", 80), Medium("Medium", 112), Large("Large", 168) }

/**
 * A photos library (web PhotosLibrary.tsx): its counts, then a tab. Timeline, Videos and Favorites page through the
 * library newest first, Timeline with "On this day" above; Folders lists its folders; Cameras groups every photo by
 * the camera that took it.
 */
class PhotosViewModel(private val session: UserSession, private val libraryId: UUID, tab: PhotosTab = PhotosTab.Timeline, private val today: LocalDate = LocalDate.now()) : ViewModel() {
    private val photos = PhotosApi(session)

    data class State(
        val name: String = "",
        val photos: Int? = null,
        val videos: Int? = null,
        val tab: PhotosTab = PhotosTab.Timeline,
        val items: List<BaseItemDto> = emptyList(),
        val total: Int = 0,
        val done: Boolean = false,
        val loading: Boolean = true,
        val error: Boolean = false,
        val memories: List<Memory> = emptyList(),
        val folders: List<BaseItemDto>? = null,
        val folderInfo: Map<UUID, FolderInfo> = emptyMap(),
        /** Every photo by camera ("" for none), most used first; null until loaded. */
        val cameras: List<Pair<String, List<BaseItemDto>>>? = null,
        val camera: String? = null,
        val size: TileSize = TileSize.Medium,
    ) {
        val months: List<PhotoMonth> get() = groupByDay(if (tab == PhotosTab.Cameras) cameraItems else items)
        val cameraItems: List<BaseItemDto> get() = cameras?.let { c -> (c.firstOrNull { it.first == camera } ?: c.firstOrNull())?.second }.orEmpty()
        /** What the viewer pages through from here. */
        val list: List<BaseItemDto> get() = if (tab == PhotosTab.Cameras) cameraItems else items
    }

    private val _state = MutableStateFlow(State(tab = tab))
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            runCatching { photos.item(libraryId) }.getOrNull()?.let { v -> _state.update { it.copy(name = v.name.orEmpty()) } }
        }
        counts()
        viewModelScope.launch {
            val m = runCatching { photos.memories(libraryId, today) }.getOrDefault(emptyList())
            _state.update { it.copy(memories = m) }
        }
        open(tab, force = true)
    }

    private fun counts() = viewModelScope.launch {
        val p = async { runCatching { photos.count(libraryId, listOf(BaseItemKind.PHOTO)) }.getOrNull() }
        val v = async { runCatching { photos.count(libraryId, listOf(BaseItemKind.VIDEO)) }.getOrNull() }
        _state.update { it.copy(photos = p.await(), videos = v.await()) }
    }

    fun setSize(s: TileSize) = _state.update { it.copy(size = s) }

    fun setCamera(name: String) = _state.update { it.copy(camera = name) }

    fun open(tab: PhotosTab, force: Boolean = false) {
        if (!force && tab == _state.value.tab) return
        job?.cancel()
        _state.update { it.copy(tab = tab, items = emptyList(), total = 0, done = false, loading = true, error = false) }
        when (tab) {
            PhotosTab.Folders -> job = viewModelScope.launch {
                val r = runCatching { photos.subfolders(libraryId) }
                _state.update { it.copy(folders = r.getOrNull() ?: it.folders, loading = false, error = r.isFailure, done = true) }
                r.getOrNull()?.forEach { f ->
                    launch { runCatching { photos.folderInfo(f) }.getOrNull()?.let { info -> _state.update { s -> s.copy(folderInfo = s.folderInfo + (f.id to info)) } } }
                }
            }
            PhotosTab.Cameras -> job = viewModelScope.launch {
                val all = runCatching {
                    val out = mutableListOf<BaseItemDto>()
                    var start = 0
                    while (true) {
                        val r = photos.media(libraryId, start = start, limit = 500, types = listOf(BaseItemKind.PHOTO))
                        out += r.items
                        if (r.items.size < 500) break
                        start += 500
                    }
                    out
                }
                val groups = all.getOrNull()?.groupBy { cameraName(it) }?.toList()
                    ?.sortedWith(compareBy<Pair<String, List<BaseItemDto>>> { if (it.first.isEmpty()) 1 else 0 }.thenByDescending { it.second.size })
                _state.update { it.copy(cameras = groups ?: it.cameras, loading = false, error = all.isFailure, done = true) }
            }
            else -> loadMore()
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.tab == PhotosTab.Folders || s.tab == PhotosTab.Cameras) return
        if (s.done && s.items.isNotEmpty()) return
        if (job?.isActive == true && s.items.isNotEmpty()) return
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = false) }
            val r = runCatching {
                photos.media(
                    libraryId, start = s.items.size, limit = 150,
                    types = if (s.tab == PhotosTab.Videos) listOf(BaseItemKind.VIDEO) else MediaTypes,
                    favorite = if (s.tab == PhotosTab.Favorites) true else null,
                )
            }
            val page = r.getOrNull()
            _state.update {
                if (page == null) it.copy(loading = false, error = true)
                else {
                    val seen = it.items.map { x -> x.id }.toSet()
                    val fresh = page.items.filter { x -> x.id !in seen }
                    val items = it.items + fresh
                    it.copy(items = items, total = page.totalRecordCount, loading = false, done = items.size >= page.totalRecordCount || fresh.isEmpty())
                }
            }
            PhotoQueue.state.value?.let { q -> if (q.items.firstOrNull()?.id == _state.value.items.firstOrNull()?.id) PhotoQueue.update(_state.value.items, _state.value.total, _state.value.done) }
        }
    }

    fun refresh() {
        counts()
        open(_state.value.tab, force = true)
    }

    /** A photo changed elsewhere (the viewer's heart). */
    fun patch(item: BaseItemDto) = _state.update { s ->
        s.copy(
            items = if (s.tab == PhotosTab.Favorites && item.userData?.isFavorite != true) s.items.filter { it.id != item.id } else s.items.map { if (it.id == item.id) item else it },
            cameras = s.cameras?.map { (k, v) -> k to v.map { if (it.id == item.id) item else it } },
        )
    }

    /** Hands the viewer this page's list (and keeps it loading more). */
    fun share(list: List<BaseItemDto> = _state.value.list, paged: Boolean = true) {
        val s = _state.value
        val paging = paged && s.tab != PhotosTab.Cameras
        PhotoQueue.set(list, if (paging) s.total else list.size, if (paging) s.done else true, more = if (paging) ::loadMore else null, changed = ::patch)
    }
}

/** How a folder lists its photos. */
enum class FolderSort(val label: String) { Oldest("Date taken, oldest first"), Newest("Date taken, newest first"), Name("Name") }
enum class FolderShow(val label: String) { All("All"), Photos("Photos"), Videos("Videos"), Favorites("Favorites") }

/**
 * A folder of photos (web Album.tsx): where it sits (its library and the folders above it), its counts, cover and
 * dates, the folders inside it, then its own photos and videos by day.
 */
class PhotoFolderViewModel(private val session: UserSession, private val folderId: UUID) : ViewModel() {
    private val photos = PhotosApi(session)

    data class State(
        val item: BaseItemDto? = null,
        /** [library, …folders above this one]. */
        val path: List<BaseItemDto> = emptyList(),
        val info: FolderInfo? = null,
        val subfolders: List<BaseItemDto> = emptyList(),
        val subCounts: Map<UUID, Int> = emptyMap(),
        val show: FolderShow = FolderShow.All,
        val sort: FolderSort = FolderSort.Oldest,
        val items: List<BaseItemDto> = emptyList(),
        val total: Int = 0,
        val done: Boolean = false,
        val loading: Boolean = true,
        val error: Boolean = false,
        val size: TileSize = TileSize.Medium,
    ) {
        val library: BaseItemDto? get() = path.firstOrNull()
        val months: List<PhotoMonth> get() = groupByDay(items)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val item = runCatching { photos.item(folderId) }.getOrNull()
            if (item == null) { _state.update { it.copy(loading = false, error = true) }; return@launch }
            _state.update { it.copy(item = item) }
            launch {
                val a = runCatching { photos.ancestors(folderId) }.getOrDefault(emptyList())
                // [library, its folder on disk, …]: the folder on disk isn't one people see.
                _state.update { it.copy(path = listOfNotNull(a.firstOrNull()) + a.drop(2).filter { x -> x.type == BaseItemKind.PHOTO_ALBUM || x.type == BaseItemKind.FOLDER }) }
            }
            launch { runCatching { photos.folderInfo(item, recursive = false) }.getOrNull()?.let { info -> _state.update { it.copy(info = info) } } }
            launch {
                val subs = runCatching { photos.subfolders(folderId) }.getOrDefault(emptyList())
                _state.update { it.copy(subfolders = subs) }
                subs.forEach { f -> launch { runCatching { photos.folderInfo(f) }.getOrNull()?.let { i -> _state.update { s -> s.copy(subCounts = s.subCounts + (f.id to i.count)) } } } }
            }
        }
        reload()
    }

    fun setShow(show: FolderShow) { _state.update { it.copy(show = show) }; reload() }
    fun setSort(sort: FolderSort) { _state.update { it.copy(sort = sort) }; reload() }
    fun setSize(s: TileSize) = _state.update { it.copy(size = s) }

    private fun reload() {
        job?.cancel()
        _state.update { it.copy(items = emptyList(), total = 0, done = false, loading = true, error = false) }
        loadMore()
    }

    fun loadMore() {
        val s = _state.value
        if (s.done && s.items.isNotEmpty()) return
        if (job?.isActive == true && s.items.isNotEmpty()) return
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = false) }
            val r = runCatching {
                photos.media(
                    folderId, start = s.items.size, limit = 200, recursive = false,
                    types = when (s.show) { FolderShow.Photos -> listOf(BaseItemKind.PHOTO); FolderShow.Videos -> listOf(BaseItemKind.VIDEO); else -> MediaTypes },
                    favorite = if (s.show == FolderShow.Favorites) true else null,
                    oldestFirst = s.sort == FolderSort.Oldest, byName = s.sort == FolderSort.Name,
                )
            }
            val page = r.getOrNull()
            _state.update {
                if (page == null) it.copy(loading = false, error = true)
                else {
                    val seen = it.items.map { x -> x.id }.toSet()
                    val fresh = page.items.filter { x -> x.id !in seen }
                    val items = it.items + fresh
                    it.copy(items = items, total = page.totalRecordCount, loading = false, done = items.size >= page.totalRecordCount || fresh.isEmpty())
                }
            }
            PhotoQueue.state.value?.let { q -> if (q.items.firstOrNull()?.id == _state.value.items.firstOrNull()?.id) PhotoQueue.update(_state.value.items, _state.value.total, _state.value.done) }
        }
    }

    fun patch(item: BaseItemDto) = _state.update { s ->
        s.copy(items = if (s.show == FolderShow.Favorites && item.userData?.isFavorite != true) s.items.filter { it.id != item.id } else s.items.map { if (it.id == item.id) item else it })
    }

    fun share() {
        val s = _state.value
        PhotoQueue.set(s.items, s.total, s.done, more = ::loadMore, changed = ::patch)
    }

    /** A shuffled slideshow of the folder's photos. */
    fun shuffled(done: (List<BaseItemDto>) -> Unit) = viewModelScope.launch {
        val r = runCatching { photos.media(folderId, limit = 500, recursive = false, types = listOf(BaseItemKind.PHOTO), random = true).items }.getOrDefault(emptyList())
        if (r.isNotEmpty()) {
            PhotoQueue.set(r, changed = ::patch)
            done(r)
        }
    }

    /** Every photo and video in the folder (for Download). */
    fun all(done: (List<BaseItemDto>) -> Unit) = viewModelScope.launch {
        runCatching { photos.media(folderId, limit = null, recursive = false).items }.getOrNull()?.let(done)
    }
}
