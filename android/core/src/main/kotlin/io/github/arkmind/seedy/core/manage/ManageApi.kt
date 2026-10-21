package io.github.arkmind.seedy.core.manage

import io.github.arkmind.seedy.core.admin.AdminException
import io.github.arkmind.seedy.core.admin.AdminHttp
import io.github.arkmind.seedy.core.admin.ItemsResult
import io.github.arkmind.seedy.core.admin.SeedyJson
import io.github.arkmind.seedy.core.admin.normId
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jellyfin.sdk.api.client.HttpMethod

/*
 * Managing titles (boards DetailsMore, ItemDelete, PlaylistNew, LibrarySelect, ItemEditMetadata, ItemIdentify,
 * ItemImages): Jellyfin's own endpoints, plus Seedy's delete preview, Seerr clean-up and people list when the plugin is
 * there. Everything works without it: sizes then come from the media sources, and playlists are only yours or everyone's.
 */

/** GET /Seedy/Items/DeletePreview. */
@Serializable
data class DeletePreview(val items: List<DeleteItem> = emptyList(), val bytes: Long = 0)

@Serializable
data class DeleteItem(
    val id: String = "",
    val name: String = "",
    val type: String = "",
    val canDelete: Boolean = false,
    val bytes: Long = 0,
    val paths: List<String> = emptyList(),
    val groups: List<DeleteGroup> = emptyList(),
    val seerr: SeerrMedia? = null,
)

/** A season, the video files or everything else ("extras"). */
@Serializable
data class DeleteGroup(
    val kind: String = "media",
    val id: String? = null,
    val name: String? = null,
    val index: Int? = null,
    val episodes: Int = 0,
    val files: Int = 0,
    val bytes: Long = 0,
    val height: Int? = null,
    val codec: String? = null,
)

/** The title in Seerr (only for request managers): which *arr has it and whether it still monitors it. */
@Serializable
data class SeerrMedia(val mediaType: String = "movie", val tmdbId: Int = 0, val arr: String? = null, val monitored: Boolean? = null)

/** GET /Seedy/Items/ShareWith: the other people on the server. */
@Serializable
data class SharePerson(val id: String = "", val name: String = "", val hasImage: Boolean = false)

/** A playlist or collection in the pickers. */
@Serializable
data class ListEntry(val id: String = "", val name: String = "", val childCount: Int? = null, val imageTags: Map<String, String>? = null)

/** GET /Playlists/{id}. */
@Serializable
data class PlaylistInfo(val openAccess: Boolean = false, val shares: List<PlaylistShare> = emptyList(), val itemIds: List<String> = emptyList())

@Serializable
data class PlaylistShare(val userId: String = "", val canEdit: Boolean = false)

@Serializable
data class ItemRef(val id: String = "", val seriesId: String? = null, val seasonId: String? = null, val type: String? = null)

@Serializable
private data class Created(val id: String = "")

/** Raw item fields the fallbacks read (no plugin). */
@Serializable
data class MediaSourceInfo(val path: String? = null, val size: Long? = null, val mediaStreams: List<StreamInfo> = emptyList())

@Serializable
data class StreamInfo(val type: String = "", val codec: String? = null, val height: Int? = null)

@Serializable
data class ItemFacts(
    val id: String = "",
    val name: String = "",
    val type: String = "",
    val canDelete: Boolean = false,
    val path: String? = null,
    val status: String? = null,
    val seasonId: String? = null,
    val seasonName: String? = null,
    val parentIndexNumber: Int? = null,
    val mediaSources: List<MediaSourceInfo> = emptyList(),
)

class ManageApi(private val session: UserSession) {
    val http = AdminHttp(session.api)
    private val uid: String get() = session.userId.toString()

    val isAdmin: Boolean get() = session.user.policy?.isAdministrator == true
    val canManageCollections: Boolean get() = isAdmin || session.user.policy?.enableCollectionManagement == true
    val hasSeedy: Boolean get() = session.seedyApi != null

    /* ── Delete ── */

    /** Seedy's preview, or null without the plugin. */
    suspend fun deletePreview(ids: List<String>): DeletePreview? {
        if (!hasSeedy) return null
        return try {
            http.get<DeletePreview>("/Seedy/Items/DeletePreview", mapOf("ids" to ids.joinToString(",")), json = SeedyJson)
        } catch (e: AdminException) {
            if (e.status == 404) null else throw e
        }
    }

    suspend fun facts(ids: List<String>): List<ItemFacts> =
        http.get<ItemsResult<ItemFacts>>("/Items", mapOf("ids" to ids.joinToString(","), "userId" to uid, "fields" to "CanDelete,Path,MediaSources,MediaStreams")).items

    suspend fun episodes(seriesId: String): List<ItemFacts> =
        http.get<ItemsResult<ItemFacts>>(
            "/Items",
            mapOf("parentId" to seriesId, "userId" to uid, "recursive" to true, "includeItemTypes" to "Episode", "isMissing" to false, "fields" to "Path,MediaSources,MediaStreams", "sortBy" to "ParentIndexNumber,IndexNumber"),
        ).items

    suspend fun delete(ids: List<String>) = http.delete("/Items", mapOf("ids" to ids.joinToString(",")))

    /** Clears the title in Seerr so it can be requested again. */
    suspend fun clearSeerr(media: SeerrMedia) = http.delete("/Seedy/Requests/Media/${media.mediaType}/${media.tmdbId}")

    /* ── Playlists and collections ── */

    suspend fun playlists(): List<ListEntry> =
        http.get<ItemsResult<ListEntry>>("/Users/$uid/Items", mapOf("includeItemTypes" to "Playlist", "recursive" to true, "fields" to "ChildCount", "sortBy" to "SortName")).items

    suspend fun playlist(id: String): PlaylistInfo = http.get("/Playlists/$id")

    suspend fun playlistItems(id: String): List<ItemRef> =
        http.get<ItemsResult<ItemRef>>("/Playlists/$id/Items", mapOf("userId" to uid, "enableImages" to false, "enableUserData" to false)).items

    suspend fun addToPlaylist(playlistId: String, ids: List<String>) = http.send("/Playlists/$playlistId/Items", query = mapOf("ids" to ids.joinToString(","), "userId" to uid))

    /** Creates a playlist; [people] are (user id, can edit). Returns its id. */
    suspend fun createPlaylist(name: String, ids: List<String>, people: List<Pair<String, Boolean>>, public: Boolean, mediaType: String? = null): String {
        val body = buildJsonObject {
            put("Name", name)
            put("Ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
            put("UserId", uid)
            if (mediaType != null) put("MediaType", mediaType)
            put("Users", JsonArray(people.map { (id, edit) -> buildJsonObject { put("UserId", id); put("CanEdit", edit) } }))
            put("IsPublic", public)
        }
        return http.post<Created>("/Playlists", body).id
    }

    suspend fun collections(): List<ListEntry> =
        http.get<ItemsResult<ListEntry>>("/Users/$uid/Items", mapOf("includeItemTypes" to "BoxSet", "recursive" to true, "fields" to "ChildCount", "sortBy" to "SortName")).items

    /** The ids in a collection. */
    suspend fun collectionItems(id: String): List<String> =
        http.get<ItemsResult<ItemRef>>("/Users/$uid/Items", mapOf("parentId" to id, "enableImages" to false, "enableUserData" to false)).items.map { it.id }

    suspend fun addToCollection(collectionId: String, ids: List<String>) = http.send("/Collections/$collectionId/Items", query = mapOf("ids" to ids.joinToString(",")))

    suspend fun createCollection(name: String, ids: List<String>): String =
        http.post<Created>("/Collections", query = mapOf("name" to name, "ids" to ids.joinToString(","))).id

    /** The other people on the server, or null without the plugin (then playlists are only yours or everyone's). */
    suspend fun shareWith(): List<SharePerson>? {
        if (!hasSeedy) return null
        return try {
            http.get<List<SharePerson>>("/Seedy/Items/ShareWith", json = SeedyJson)
        } catch (e: AdminException) {
            if (e.status == 404) null else throw e
        }
    }

    /* ── Watched, refresh ── */

    suspend fun markPlayed(id: String) = http.send("/UserPlayedItems/$id", query = mapOf("userId" to uid))

    suspend fun refresh(id: String) = http.send(
        "/Items/$id/Refresh",
        query = mapOf("metadataRefreshMode" to "FullRefresh", "imageRefreshMode" to "FullRefresh", "replaceAllMetadata" to false, "replaceAllImages" to false),
    )

    /** How many episodes a series or season has on the server. */
    suspend fun episodeCount(parentId: String): Int =
        http.get<ItemsResult<ItemRef>>("/Items", mapOf("parentId" to parentId, "userId" to uid, "recursive" to true, "includeItemTypes" to "Episode", "isMissing" to false, "limit" to 0)).totalRecordCount

    /* ── Metadata, identify, images ── */

    suspend fun item(id: String): JsonObject = http.getObject("/Users/$uid/Items/$id")

    suspend fun editorInfo(id: String): JsonObject = http.getObject("/Items/$id/MetadataEditor")

    suspend fun saveItem(id: String, item: JsonObject) {
        http.raw(HttpMethod.POST, "/Items/$id", body = item)
    }

    suspend fun remoteSearch(kind: String, body: JsonObject): List<JsonObject> =
        (http.element(HttpMethod.POST, "/Items/RemoteSearch/$kind", body = body) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    suspend fun applyMatch(id: String, result: JsonObject, replaceImages: Boolean) {
        http.raw(HttpMethod.POST, "/Items/RemoteSearch/Apply/$id", mapOf("replaceAllImages" to replaceImages), result)
    }

    fun remoteImageUrl(imageUrl: String, provider: String?): String {
        val base = session.api.baseUrl.orEmpty().trimEnd('/')
        val q = "imageUrl=" + java.net.URLEncoder.encode(imageUrl, "UTF-8") + (provider?.let { "&providerName=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
        return "$base/Items/RemoteSearch/Image?$q"
    }

    suspend fun images(id: String): List<JsonObject> =
        (http.element(HttpMethod.GET, "/Items/$id/Images") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    suspend fun remoteImages(id: String, type: String, provider: String?, allLanguages: Boolean): JsonObject =
        http.getObject("/Items/$id/RemoteImages", mapOf("type" to type, "providerName" to provider, "includeAllLanguages" to allLanguages))

    suspend fun downloadImage(id: String, type: String, url: String) = http.send("/Items/$id/RemoteImages/Download", query = mapOf("type" to type, "imageUrl" to url))

    suspend fun deleteImage(id: String, type: String, index: Int?) =
        http.delete(if (index != null) "/Items/$id/Images/$type/$index" else "/Items/$id/Images/$type")

    /** Uploads [bytes] as the item's [type] image (Jellyfin reads it as base64 text). */
    suspend fun uploadImage(id: String, type: String, bytes: ByteArray, mime: String) {
        val body = org.jellyfin.sdk.model.FileInfo(android.util.Base64.encode(bytes, android.util.Base64.NO_WRAP), mime)
        http.raw(HttpMethod.POST, "/Items/$id/Images/$type", body = body)
    }

    fun sameId(a: String?, b: String?): Boolean = !a.isNullOrEmpty() && normId(a) == normId(b)
}
