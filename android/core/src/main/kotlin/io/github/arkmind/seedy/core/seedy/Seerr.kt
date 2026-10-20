package io.github.arkmind.seedy.core.seedy

import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.get
import org.jellyfin.sdk.api.client.extensions.post

/*
 * Seerr, through the Seedy plugin (/Seedy/Requests). Statuses are Seerr's words: "none", "pending", "processing",
 * "partial", "available", "declined", "blocklisted".
 */

/** A title Seerr knows (search results, Discover…); [jellyfinItemId] is set when it's on the server. */
@Serializable
data class SeerrItem(
    val tmdbId: Int,
    val mediaType: String = "movie",
    val title: String = "",
    val year: String? = null,
    val rating: Double? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val overview: String? = null,
    val status: String = "none",
    val requestedBy: String? = null,
    val requestedByMe: Boolean = false,
    val jellyfinItemId: String? = null,
)

@Serializable
data class SeerrResults(val results: List<SeerrItem> = emptyList(), val total: Int = 0)

@Serializable
data class SeerrSeason(
    val seasonNumber: Int,
    val name: String = "",
    val episodeCount: Int = 0,
    val airDate: String? = null,
    val status: String = "none",
    val status4k: String = "none",
    val posterPath: String? = null,
    /** Set while the season is airing: how many of its episodes are out. */
    val airedEpisodes: Int? = null,
    val requestedBy: String? = null,
    val requestedByMe: Boolean = false,
    val requestedBy4k: String? = null,
    val requestedByMe4k: Boolean = false,
)

/** GET /Seedy/Requests/Media/{type}/{tmdbId}: what the request dialog and the Seerr title page show. */
@Serializable
data class SeerrDetails(
    val tmdbId: Int,
    val mediaType: String = "tv",
    val title: String = "",
    val year: String? = null,
    val rating: Double? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val overview: String? = null,
    val status: String = "none",
    val status4k: String? = null,
    val requestedBy: String? = null,
    val requestedByMe: Boolean = false,
    val jellyfinItemId: String? = null,
    val tagline: String? = null,
    val genres: List<String> = emptyList(),
    val runtime: Int? = null,
    val releaseDate: String? = null,
    val certification: String? = null,
    val seasons: List<SeerrSeason>? = null,
    val networks: List<String>? = null,
    val numberOfSeasons: Int? = null,
    val trailerUrl: String? = null,
)

/** What the request dialog offers the signed-in person; [advanced] when Seerr lets them pick the server or requester. */
@Serializable
data class RequestOptions(
    val quota: RequestQuota? = null,
    val autoApprove: Boolean = false,
    val autoApprove4k: Boolean = false,
    val can4k: Boolean = false,
    val advanced: RequestAdvanced? = null,
)

@Serializable
data class RequestQuota(val limit: Int = 0, val days: Int = 7, val used: Int = 0, val remaining: Int = 0)

@Serializable
data class RequestAdvanced(val servers: List<RequestServer> = emptyList(), val users: List<SeerrUser> = emptyList(), val me: Int = 0)

@Serializable
data class RequestServer(
    val id: Int,
    val name: String = "",
    val is4k: Boolean = false,
    val isDefault: Boolean = false,
    val profileId: Int? = null,
    val rootFolder: String? = null,
    val profiles: List<RequestProfile> = emptyList(),
    val rootFolders: List<RequestFolder> = emptyList(),
)

@Serializable
data class RequestProfile(val id: Int, val name: String = "")

@Serializable
data class RequestFolder(val path: String, val freeSpace: Long? = null)

@Serializable
data class SeerrUser(val id: Int, val displayName: String = "", val email: String? = null, val jellyfinUserId: String? = null)

/** POST /Seedy/Requests. Unset fields stay out of the body (the SDK's JSON leaves nulls out). */
@Serializable
data class RequestBody(
    val mediaType: String,
    val tmdbId: Int,
    val seasons: List<Int>? = null,
    val is4k: Boolean = false,
    val serverId: Int? = null,
    val profileId: Int? = null,
    val rootFolder: String? = null,
    val userId: Int? = null,
)

@Serializable
data class RequestResult(val status: String = "pending")

/** Seerr's endpoints on the plugin, called through the signed-in Jellyfin client. */
class SeerrApi(private val api: ApiClient) {
    suspend fun details(mediaType: String, tmdbId: Int): SeerrDetails =
        api.get<SeerrDetails>("/Seedy/Requests/Media/{type}/{id}", mapOf("type" to mediaType, "id" to tmdbId)).content

    suspend fun search(query: String, page: Int = 1): SeerrResults =
        api.get<SeerrResults>("/Seedy/Requests/Search", queryParameters = mapOf("query" to query, "page" to page)).content

    suspend fun options(mediaType: String): RequestOptions =
        api.get<RequestOptions>("/Seedy/Requests/Options/{type}", mapOf("type" to mediaType)).content

    suspend fun request(body: RequestBody): RequestResult = api.post<RequestResult>("/Seedy/Requests", requestBody = body).content
}

/** Seerr for this session, when Seedy is installed and connected to it. */
val UserSession.seerr: SeerrApi? get() = if (hasRequests) SeerrApi(api) else null

/** The person may ask Seerr for titles (Seedy · Requests › request buttons, and their own Seerr permission). */
val UserSession.canRequest: Boolean
    get() = seedy?.requests?.let { it.connected && it.canRequest && it.requestButtons } == true

/** Seerr's results show in Search (Seedy · Requests › "Request from search"). */
val UserSession.seerrInSearch: Boolean
    get() = seedy?.requests?.let { it.connected && it.requestInSearch } == true

/** TMDB image addresses (Seerr's poster and backdrop paths). */
fun tmdbImage(path: String?, size: String = "w342"): String? = path?.takeIf { it.isNotBlank() }?.let {
    if (it.startsWith("http://") || it.startsWith("https://")) it else "https://image.tmdb.org/t/p/$size$it"
}
