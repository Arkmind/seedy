package io.github.arkmind.seedy.core.books

import io.github.arkmind.seedy.core.audio.AudioApi
import io.github.arkmind.seedy.core.audio.AudioPart
import io.github.arkmind.seedy.core.audio.Reading
import io.github.arkmind.seedy.core.audio.authorsOf
import io.github.arkmind.seedy.core.audio.mergePlace
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.delete
import org.jellyfin.sdk.api.client.extensions.post
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.userViewApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads a books library the way the web does (loadLibraryItems): every book and audiobook in one go, an audiobook in
 * several files as one book where its first file was, and this profile's places. The items stay for a minute, so going
 * from the library to a book and back doesn't load them again.
 */
class BooksRepo(private val session: UserSession) {
    private val api get() = session.api
    private val userId get() = session.userId
    private val audio = AudioApi(session)
    val books = BooksApi(session)

    /** A library's items with the files of its audiobooks in several files. */
    data class Library(val items: List<BaseItemDto>, val parts: Map<UUID, List<AudioPart>>)

    suspend fun library(parentId: UUID, force: Boolean = false): Library {
        val key = "${session.server.id}:${session.userId}:$parentId"
        val hit = cache[key]
        if (hit != null && !force && System.currentTimeMillis() - hit.first < 60_000) return hit.second.await()
        val job = CompletableDeferred<Library>()
        cache[key] = System.currentTimeMillis() to job
        return runCatching { load(parentId) }
            .onSuccess { job.complete(it) }
            .onFailure { cache.remove(key); job.completeExceptionally(it) }
            .getOrThrow()
    }

    private suspend fun load(parentId: UUID): Library = coroutineScope {
        val books = async { runCatching { audio.audiobooks(parentId) }.getOrDefault(emptyList()) }
        val items = loadAll(parentId)
        val list = books.await()
        if (list.isEmpty()) return@coroutineScope Library(items, emptyMap())
        val byPart = HashMap<UUID, BaseItemDto>()
        val parts = HashMap<UUID, List<AudioPart>>()
        for (b in list) {
            parts[b.item.id] = b.parts
            for (p in b.parts) uuidOf(p.id)?.let { byPart[it] = b.item }
        }
        val shown = HashSet<UUID>()
        Library(
            items.flatMap { it ->
                val book = byPart[it.id] ?: return@flatMap listOf(it)
                if (!shown.add(book.id)) emptyList() else listOf(book)
            },
            parts,
        )
    }

    private suspend fun loadAll(parentId: UUID): List<BaseItemDto> = withContext(Dispatchers.IO) {
        val out = mutableListOf<BaseItemDto>()
        val page = 500
        var start = 0
        while (start < 20_000) {
            val r = api.libraryApi.getItems(
                userId = userId, parentId = parentId, includeItemTypes = listOf(BaseItemKind.BOOK, BaseItemKind.AUDIO_BOOK), recursive = true,
                startIndex = start, limit = page, fields = BookFields, sortBy = listOf(ItemSortBy.DATE_CREATED, ItemSortBy.SORT_NAME),
                sortOrder = listOf(SortOrder.DESCENDING), enableImageTypes = listOf(ImageType.PRIMARY), enableUserData = true, enableTotalRecordCount = true,
            ).content
            out += r.items
            if (out.size >= r.totalRecordCount || r.items.size < page) break
            start += page
        }
        out
    }

    /** Every place this profile has, finished ones too. */
    suspend fun readings(): List<Reading> = runCatching { audio.readingList(finished = true, limit = 200) }.getOrDefault(emptyList())

    suspend fun reading(id: UUID): Reading? = runCatching { audio.reading(id) }.getOrNull()

    suspend fun saveReading(id: UUID, place: Reading): Reading = audio.saveReading(id, place)

    suspend fun deleteReading(id: UUID) = withContext(Dispatchers.IO) {
        api.delete<Unit>("/Seedy/Reading/$id")
        Unit
    }

    /** Jellyfin looks for new and changed files in a library (admins: "Scan library files"). */
    suspend fun scan(libraryId: UUID) = withContext(Dispatchers.IO) {
        api.post<Unit>(
            "/Items/$libraryId/Refresh",
            queryParameters = mapOf(
                "Recursive" to true, "MetadataRefreshMode" to "Default", "ImageRefreshMode" to "Default", "ReplaceAllMetadata" to false, "ReplaceAllImages" to false,
            ),
        )
        Unit
    }

    /** The library a title is in (its ancestors), else the first books library. */
    suspend fun libraryOf(id: UUID): BaseItemDto? = withContext(Dispatchers.IO) {
        val views = runCatching { api.userViewApi.getUserViews(userId = userId).content.items }.getOrDefault(emptyList())
        val ancestors = runCatching { api.libraryApi.getAncestors(itemId = id, userId = userId).content }.getOrDefault(emptyList())
        ancestors.firstNotNullOfOrNull { a -> views.firstOrNull { it.id == a.id } } ?: views.firstOrNull { it.collectionType == CollectionType.BOOKS }
    }

    suspend fun item(id: UUID): BaseItemDto = withContext(Dispatchers.IO) { api.libraryApi.getItem(itemId = id, userId = userId).content }

    /**
     * A book or an audiobook with everything its page shows. An audiobook in several files is the folder's book (any of
     * its files opens it); one in a single file takes Seedy's place in it.
     */
    suspend fun book(id: UUID): Pair<BaseItemDto, List<AudioPart>?> {
        val multi = runCatching { audio.audiobook(id) }.getOrNull()
        if (multi != null) return multi.item to multi.parts
        val item = withContext(Dispatchers.IO) {
            api.libraryApi.getItems(userId = userId, ids = listOf(id), fields = BookFields, enableUserData = true).content.items.firstOrNull()
        } ?: item(id)
        return (if (isAudioBook(item)) mergePlace(item, reading(id)) else item) to null
    }

    /** The same book as an e-book (the audiobook player's "Read the EPUB from here"): same title, and the same author when both say. */
    suspend fun findEbook(name: String, author: String?): BaseItemDto? = withContext(Dispatchers.IO) {
        val items = runCatching {
            api.libraryApi.getItems(
                userId = userId, includeItemTypes = listOf(BaseItemKind.BOOK), searchTerm = name, recursive = true, limit = 20,
                fields = listOf(ItemFields.PEOPLE, ItemFields.PATH),
            ).content.items
        }.getOrDefault(emptyList())
        val who = fold(author)
        items.firstOrNull { b ->
            if (fold(b.name) != fold(name)) return@firstOrNull false
            val known = authorsOf(b).map { fold(it.first) }.filter { it.isNotEmpty() }
            who.isEmpty() || known.isEmpty() || who in known
        }
    }

    private fun fold(s: String?) = java.text.Normalizer.normalize(s.orEmpty().lowercase(), java.text.Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** A file's size: from its media source, else from the file endpoint (one byte of it, whose range header says the total). */
    suspend fun sizeOf(item: BaseItemDto): Long? {
        fileSize(item)?.let { return it }
        if (isAudioBook(item)) return null
        sizes[item.id]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val c = URL(books.fileUrl(item.id)).openConnection() as HttpURLConnection
                c.setRequestProperty("Range", "bytes=0-0")
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                try {
                    val range = c.getHeaderField("Content-Range").orEmpty()
                    Regex("/(\\d+)$").find(range)?.groupValues?.get(1)?.toLongOrNull() ?: c.contentLengthLong.takeIf { it > 1 }
                } finally {
                    c.disconnect()
                }
            }.getOrNull()?.also { sizes[item.id] = it }
        }
    }

    companion object {
        /** What the books screens need of each item. */
        val BookFields = listOf(
            ItemFields.PRIMARY_IMAGE_ASPECT_RATIO, ItemFields.GENRES, ItemFields.DATE_CREATED, ItemFields.SORT_NAME, ItemFields.PEOPLE, ItemFields.PATH,
            ItemFields.MEDIA_SOURCES, ItemFields.PROVIDER_IDS, ItemFields.STUDIOS, ItemFields.OVERVIEW, ItemFields.CHAPTERS, ItemFields.CAN_DOWNLOAD,
        )

        private val cache = ConcurrentHashMap<String, Pair<Long, Deferred<Library>>>()
        private val sizes = ConcurrentHashMap<UUID, Long>()

        /** Forget the cached library (marked read, a new place…): the next load asks the server. */
        fun invalidate() = cache.clear()
    }
}
