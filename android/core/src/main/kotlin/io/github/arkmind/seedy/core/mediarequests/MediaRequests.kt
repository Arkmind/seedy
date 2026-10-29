package io.github.arkmind.seedy.core.mediarequests

import kotlinx.serialization.Serializable
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.delete
import org.jellyfin.sdk.api.client.extensions.get
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.post
import org.jellyfin.sdk.model.api.ImageType

/*
 * Music and book requests (web: api/media.ts; Jellyfin.Plugin.Seedy · MediaRequestsController). Seedy keeps its own
 * queue for these: people ask, admins approve, and Seedy sends approved requests to Lidarr (music), Shelfarr, Librarr or
 * a Readarr-API app (books), then follows them there. Paths live under /Seedy/MediaRequests.
 */

/** GET /Seedy/Me · media: which of music and books the server can request, and where book formats go. */
@Serializable
data class MediaFeatures(
    val music: Boolean = false,
    val books: Boolean = false,
    val formats: List<String> = emptyList(),
    val notifyOnReady: Boolean = false,
    val bookRoutes: Map<String, String> = emptyMap(),
    /** "Open in …" addresses, for admins. */
    val lidarrUrl: String? = null,
    val shelfarrUrl: String? = null,
    val librarrUrl: String? = null,
    val readarrUrl: String? = null,
)

@Serializable
data class AlbumRef(val mbid: String, val title: String = "", val type: String? = null, val year: Int? = null)

/** The line under a request, worded by the page (code: albums, noCopy, found, notFound, purchase, service, unreachable, future, scanning, gone). */
@Serializable
data class MediaNote(
    val code: String = "",
    val n: Int? = null,
    val total: Int? = null,
    val days: Int? = null,
    val name: String? = null,
    val fraction: Double? = null,
    val text: String? = null,
)

@Serializable
data class MediaPart(val format: String = "", val service: String = "", val status: String = "", val progress: Double? = null, val itemId: String? = null)

/**
 * A music or book request. [kind] is "music" or "book"; [service] lidarr, shelfarr, librarr or readarr; [mode] artist
 * (whole discography), albums (picked albums), future (new releases only) or book; [status] pending, approved,
 * searching, downloading, ready, failed, declined or cancelled.
 */
@Serializable
data class MediaRequest(
    val id: String,
    val kind: String = "music",
    val service: String = "lidarr",
    val mode: String = "albums",
    val foreignId: String = "",
    val title: String = "",
    val subtitle: String? = null,
    val coverUrl: String? = null,
    val albums: List<AlbumRef> = emptyList(),
    val formats: List<String> = emptyList(),
    val content: String? = null,
    val series: String? = null,
    val seriesIndex: Double? = null,
    val language: String? = null,
    val keepNew: Boolean = false,
    val units: Int = 0,
    val requestedBy: String = "",
    val requestedByMe: Boolean = false,
    val requestedAt: String = "",
    val updatedAt: String = "",
    val decidedAt: String? = null,
    val readyAt: String? = null,
    val status: String = "pending",
    val attention: Boolean = false,
    val note: MediaNote? = null,
    val progress: Double? = null,
    val parts: List<MediaPart> = emptyList(),
    val itemId: String? = null,
    val lowerQuality: String? = null,
    val canSearch: Boolean = false,
    val canCancel: Boolean = false,
    val canDecide: Boolean = false,
) {
    val music: Boolean get() = kind == "music"
}

@Serializable
data class MediaRequestList(val results: List<MediaRequest> = emptyList(), val total: Int = 0)

@Serializable
data class MediaKindSummary(val enabled: Boolean = false, val open: Int = 0, val pending: Int = 0)

/** Counts for the Requests tabs. */
@Serializable
data class MediaSummary(val music: MediaKindSummary = MediaKindSummary(), val books: MediaKindSummary = MediaKindSummary(), val canManage: Boolean = false)

@Serializable
data class MediaFormatRoute(val format: String = "", val service: String = "", val library: String? = null)

/** What the request window offers: the person's limit (-1 none, 0 can't request), approval, quality, formats and where they land. */
@Serializable
data class MediaOptions(
    val kind: String = "music",
    val canRequest: Boolean = false,
    val limit: Int = -1,
    val used: Int = 0,
    val period: String = "week",
    val autoApprove: Boolean = false,
    val quality: String? = null,
    val formats: List<MediaFormatRoute> = emptyList(),
    val scanOnReady: Boolean = false,
)

/** An album, EP or single. [status]: none, server, requested, waiting or downloading. */
@Serializable
data class MusicRelease(
    val mbid: String,
    val artistMbid: String? = null,
    val title: String = "",
    val artist: String? = null,
    val type: String = "album",
    val date: String? = null,
    val coverUrl: String? = null,
    val upcoming: Boolean = false,
    val status: String = "none",
    val requestedBy: String? = null,
    val itemId: String? = null,
    val have: Int? = null,
    val of: Int? = null,
    val tracks: Int? = null,
    val lengthMs: Long? = null,
    /** MusicBrainz secondary types: live, soundtrack… */
    val secondary: List<String> = emptyList(),
)

@Serializable
data class MusicArtistRef(
    val mbid: String,
    val name: String = "",
    val disambiguation: String? = null,
    val type: String? = null,
    val area: String? = null,
    val imageUrl: String? = null,
    val itemId: String? = null,
)

@Serializable
data class MusicArtist(val artist: MusicArtistRef, val releases: List<MusicRelease> = emptyList(), val albumsOnServer: Int = 0, val watching: Boolean = false)

@Serializable
data class MusicRowResult(val results: List<MusicRelease> = emptyList(), val source: String? = null)

@Serializable
data class MusicSearchResult(val artists: List<MusicArtistRef> = emptyList(), val albums: List<MusicRelease> = emptyList())

/** A book to request. [content]: book or comic; [formats]: what can be asked for (ebook, audiobook, comic). */
@Serializable
data class BookCard(
    val workId: String,
    val title: String = "",
    val author: String? = null,
    val year: Int? = null,
    val releaseDate: String? = null,
    val coverUrl: String? = null,
    /** A library item whose cover stands in when there is no cover URL. */
    val coverItemId: String? = null,
    val series: String? = null,
    val seriesIndex: Double? = null,
    val content: String = "book",
    val formats: List<String> = emptyList(),
    val upcoming: Boolean = false,
    val rating: Double? = null,
    val pages: Int? = null,
    val description: String? = null,
    val languages: List<String> = emptyList(),
    val status: String = "none",
    val requestedBy: String? = null,
    val itemId: String? = null,
    val onServer: List<String> = emptyList(),
    val readFrom: Double? = null,
    val readTo: Double? = null,
)

@Serializable
data class BookRowResult(val results: List<BookCard> = emptyList(), val source: String? = null)

@Serializable
data class MediaCreate(
    val kind: String,
    val mode: String,
    val foreignId: String,
    val title: String,
    val keepNew: Boolean,
    val subtitle: String? = null,
    val coverUrl: String? = null,
    val albums: List<AlbumRef>? = null,
    val albumCount: Int? = null,
    val formats: List<String>? = null,
    val content: String? = null,
    val series: String? = null,
    val seriesIndex: Double? = null,
    val year: Int? = null,
    val language: String? = null,
)

/** The Discover rows, in the web's order. */
enum class MusicRow(val key: String, val title: String) {
    New("new", "New from artists you play"),
    Missing("missing", "Missing from artists you have"),
    Trending("trending", "Trending this week"),
    Soon("soon", "Coming soon"),
}

enum class BookRow(val key: String, val title: String) {
    Next("next", "Next in series you’re reading"),
    Audiobooks("audiobooks", "Audiobooks of books you’ve read"),
    Trending("trending", "Trending"),
}

/** Seedy's music and book request endpoints, called as the person. */
class MediaRequestsApi(private val api: ApiClient) {
    suspend fun summary(): MediaSummary = api.get<MediaSummary>("$M/Summary").content

    suspend fun list(scope: String, kind: String? = null): MediaRequestList =
        api.get<MediaRequestList>(M, queryParameters = mapOf("scope" to scope, "kind" to kind)).content

    suspend fun options(kind: String): MediaOptions = api.get<MediaOptions>("$M/Options/{kind}", mapOf("kind" to kind)).content

    suspend fun create(body: MediaCreate): MediaRequest = api.post<MediaRequest>(M, requestBody = body).content

    suspend fun approve(id: String): MediaRequest = api.post<MediaRequest>("$M/{id}/Approve", mapOf("id" to id)).content

    suspend fun decline(id: String): MediaRequest = api.post<MediaRequest>("$M/{id}/Decline", mapOf("id" to id)).content

    suspend fun searchNow(id: String): MediaRequest = api.post<MediaRequest>("$M/{id}/Search", mapOf("id" to id)).content

    suspend fun acceptLower(id: String): MediaRequest = api.post<MediaRequest>("$M/{id}/AcceptLower", mapOf("id" to id)).content

    suspend fun remove(id: String) {
        api.delete<Unit>("$M/{id}", mapOf("id" to id))
    }

    suspend fun musicRow(row: MusicRow): MusicRowResult = api.get<MusicRowResult>("$M/Music/Rows/{row}", mapOf("row" to row.key)).content

    suspend fun musicSearch(query: String): MusicSearchResult =
        api.get<MusicSearchResult>("$M/Music/Search", queryParameters = mapOf("query" to query)).content

    suspend fun musicArtist(mbid: String): MusicArtist = api.get<MusicArtist>("$M/Music/Artists/{mbid}", mapOf("mbid" to mbid)).content

    suspend fun bookRow(row: BookRow): BookRowResult = api.get<BookRowResult>("$M/Books/Rows/{row}", mapOf("row" to row.key)).content

    suspend fun bookSearch(query: String): BookRowResult =
        api.get<BookRowResult>("$M/Books/Search", queryParameters = mapOf("query" to query)).content

    suspend fun bookDetails(card: BookCard): BookCard = api.post<BookCard>("$M/Books/Details", requestBody = card).content

    /** A book card's cover: its own, else the cover of the edition on the server. */
    fun bookCover(b: BookCard): String? = b.coverUrl ?: io.github.arkmind.seedy.core.seedy.libraryUuid(b.coverItemId)?.let {
        api.imageApi.getItemImageUrl(itemId = it, imageType = ImageType.PRIMARY, maxWidth = 400)
    }

    private companion object {
        const val M = "/Seedy/MediaRequests"
    }
}
