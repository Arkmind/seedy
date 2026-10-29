package io.github.arkmind.seedy.core.audio

import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.jellyfin.sdk.api.client.extensions.get
import org.jellyfin.sdk.api.client.extensions.instantMixApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.lyricApi
import org.jellyfin.sdk.api.client.extensions.playlistApi
import org.jellyfin.sdk.api.client.extensions.post
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.LyricDto
import org.jellyfin.sdk.model.api.SortOrder
import java.util.UUID

/** One file of an audiobook in several files (Seedy's /Seedy/Audiobooks). Ticks. */
@Serializable
data class AudioPart(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Start") val start: Long = 0,
    @SerialName("RunTimeTicks") val runTimeTicks: Long = 0,
    @SerialName("Size") val size: Long? = null,
)

/** An audiobook in several files: Seedy shows the folder as one AudioBook item (under the folder's id), its files in order. */
@Serializable
data class AudiobookParts(
    @SerialName("Item") val item: BaseItemDto,
    @SerialName("Parts") val parts: List<AudioPart> = emptyList(),
)

/**
 * This profile's place in a book (/Seedy/Reading): an EPUB CFI, a page index (comics, PDFs) or, for audiobooks,
 * seconds into the whole book. Jellyfin keeps no place in a file's first or last five minutes, so audiobooks keep theirs
 * here too.
 */
@Serializable
data class Reading(
    @SerialName("ItemId") val itemId: String = "",
    @SerialName("Locator") val locator: String? = null,
    @SerialName("Percent") val percent: Double = 0.0,
    @SerialName("Chapter") val chapter: String? = null,
    @SerialName("ChapterIndex") val chapterIndex: Int? = null,
    @SerialName("ChapterCount") val chapterCount: Int? = null,
    @SerialName("Finished") val finished: Boolean = false,
    /** The reader's settings for this book (any JSON object; the web reader's are in reader/settings.ts). */
    @SerialName("Settings") val settings: JsonObject? = null,
    @SerialName("UpdatedAt") val updatedAt: String? = null,
)

/**
 * The Jellyfin and Seedy calls behind the audio player and the music and books screens (web/src/audio/api.ts): the
 * songs of an album, playlist, artist or genre, instant mixes, lyrics, audiobooks in several files and places in books.
 */
class AudioApi(private val session: UserSession) {
    private val api get() = session.api
    private val userId get() = session.userId

    suspend fun tracks(ids: List<UUID>): List<BaseItemDto> = io {
        ids.chunked(150).flatMap { chunk ->
            val found = api.libraryApi.getItems(
                userId = userId, ids = chunk, fields = TrackFields, enableImageTypes = listOf(ImageType.PRIMARY), recursive = true, enableUserData = true,
            ).content.items.associateBy { it.id }
            chunk.mapNotNull { found[it] }
        }
    }

    suspend fun albumTracks(albumId: UUID): List<BaseItemDto> = io {
        api.libraryApi.getItems(
            userId = userId, parentId = albumId, includeItemTypes = listOf(BaseItemKind.AUDIO), limit = 1000, fields = TrackFields, enableUserData = true,
            sortBy = listOf(ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME),
        ).content.items
    }

    suspend fun playlistTracks(playlistId: UUID): List<BaseItemDto> = io {
        api.playlistApi.getPlaylistItems(
            playlistId = playlistId, userId = userId, fields = TrackFields, enableImageTypes = listOf(ImageType.PRIMARY), imageTypeLimit = 1, enableUserData = true,
        ).content.items
    }

    /** An artist's songs: their albums newest first, each in disc and track order. */
    suspend fun artistTracks(artistId: UUID, limit: Int = 600): List<BaseItemDto> = io {
        val items = api.libraryApi.getItems(
            userId = userId, artistIds = listOf(artistId), includeItemTypes = listOf(BaseItemKind.AUDIO), recursive = true, limit = limit,
            sortBy = listOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.PRODUCTION_YEAR, ItemSortBy.ALBUM, ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME),
            sortOrder = List(6) { SortOrder.DESCENDING }, fields = TrackFields, enableUserData = true,
        ).content.items
        // Descending reverses the track order too: put each album back in order, keeping albums newest first.
        items.groupBy { it.albumId?.toString() ?: it.album.orEmpty() }.values
            .flatMap { g -> g.sortedWith(compareBy({ it.parentIndexNumber ?: 1 }, { it.indexNumber ?: 0 })) }
    }

    suspend fun genreTracks(genreId: UUID, parentId: UUID? = null, limit: Int = 300): List<BaseItemDto> = io {
        api.libraryApi.getItems(
            userId = userId, genreIds = listOf(genreId), parentId = parentId, includeItemTypes = listOf(BaseItemKind.AUDIO), recursive = true,
            sortBy = listOf(ItemSortBy.RANDOM), limit = limit, fields = TrackFields, enableUserData = true,
        ).content.items
    }

    /** Songs of a library (or of the whole server) in random order: a library's Shuffle. */
    suspend fun randomTracks(parentId: UUID?, limit: Int = 300, favorites: Boolean? = null): List<BaseItemDto> = io {
        api.libraryApi.getItems(
            userId = userId, parentId = parentId, includeItemTypes = listOf(BaseItemKind.AUDIO), recursive = true, isFavorite = favorites,
            sortBy = listOf(ItemSortBy.RANDOM), limit = limit, fields = TrackFields, enableUserData = true, enableTotalRecordCount = false,
        ).content.items
    }

    suspend fun folderTracks(folderId: UUID): List<BaseItemDto> = io {
        api.libraryApi.getItems(
            userId = userId, parentId = folderId, includeItemTypes = listOf(BaseItemKind.AUDIO), recursive = true, limit = 1000, fields = TrackFields,
            sortBy = listOf(ItemSortBy.ALBUM, ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME), enableUserData = true,
        ).content.items
    }

    /** Jellyfin's instant mix for any song, album, artist, playlist or genre. */
    suspend fun instantMix(id: UUID, limit: Int = 120): List<BaseItemDto> = io {
        api.instantMixApi.getInstantMixFromItem(
            itemId = id, userId = userId, limit = limit, fields = TrackFields, enableImageTypes = listOf(ImageType.PRIMARY), imageTypeLimit = 1, enableUserData = true,
        ).content.items
    }

    /** What playing an album, playlist, artist, genre, folder or library means, as a list of songs. */
    suspend fun containerTracks(item: BaseItemDto): List<BaseItemDto> = when (item.type) {
        BaseItemKind.MUSIC_ALBUM -> albumTracks(item.id)
        BaseItemKind.PLAYLIST -> playlistTracks(item.id)
        BaseItemKind.MUSIC_ARTIST -> artistTracks(item.id)
        BaseItemKind.MUSIC_GENRE, BaseItemKind.GENRE -> genreTracks(item.id)
        BaseItemKind.COLLECTION_FOLDER, BaseItemKind.USER_VIEW -> randomTracks(item.id)
        BaseItemKind.AUDIO -> listOf(item)
        else -> folderTracks(item.id)
    }

    /** An audio item with what the players show: chapters, people, streams. */
    suspend fun audioItem(id: UUID): BaseItemDto = io {
        api.libraryApi.getItems(
            userId = userId, ids = listOf(id), enableUserData = true,
            fields = TrackFields + listOf(ItemFields.OVERVIEW, ItemFields.CHAPTERS, ItemFields.PEOPLE, ItemFields.STUDIOS, ItemFields.PROVIDER_IDS, ItemFields.MEDIA_STREAMS, ItemFields.TAGS),
        ).content.items.firstOrNull() ?: api.libraryApi.getItem(itemId = id, userId = userId).content
    }

    /** The book a folder is, or a file is part of; null for a book in one file (the server answers 204). */
    suspend fun audiobook(id: UUID): AudiobookParts? = io {
        runCatching { api.get<AudiobookParts>("/Seedy/Audiobooks/$id").content }.getOrNull()
    }

    /** The audiobooks in several files of a library ([searchTerm]: only those whose name or files' names match). */
    suspend fun audiobooks(parentId: UUID?, searchTerm: String? = null): List<AudiobookParts> = io {
        api.get<List<AudiobookParts>>(
            "/Seedy/Audiobooks",
            queryParameters = buildMap { parentId?.let { put("parentId", it) }; searchTerm?.let { put("searchTerm", it) } },
        ).content
    }

    /** This profile's place in a book; null when it was never opened (204). */
    suspend fun reading(id: UUID): Reading? = io { runCatching { api.get<Reading>("/Seedy/Reading/$id").content }.getOrNull() }

    /** The books this profile is reading, most recent first. */
    suspend fun readingList(finished: Boolean = false, limit: Int = 40): List<Reading> = io {
        api.get<List<Reading>>("/Seedy/Reading", queryParameters = mapOf("finished" to finished, "limit" to limit)).content
    }

    suspend fun saveReading(id: UUID, place: Reading): Reading = io { api.post<Reading>("/Seedy/Reading/$id", requestBody = place).content }

    /** An audiobook in one file with Seedy's place in it (a book in several files has it from the server already). */
    suspend fun withPlace(item: BaseItemDto): BaseItemDto =
        if (item.type != BaseItemKind.AUDIO_BOOK) item else mergePlace(item, reading(item.id))

    suspend fun lyrics(id: UUID): LyricDto? = io { runCatching { api.lyricApi.getLyrics(id).content }.getOrNull() }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        /** What a track row, the queue and the players need. */
        val TrackFields = listOf(
            ItemFields.PARENT_ID, ItemFields.GENRES, ItemFields.DATE_CREATED, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO, ItemFields.MEDIA_SOURCES,
        )
    }
}
