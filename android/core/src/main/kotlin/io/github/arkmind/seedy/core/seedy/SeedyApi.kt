package io.github.arkmind.seedy.core.seedy

import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.delete
import org.jellyfin.sdk.api.client.extensions.get
import org.jellyfin.sdk.api.client.extensions.post
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.ItemFields
import java.util.UUID

/** The Seedy plugin’s endpoints (under /Seedy), called through the signed-in Jellyfin client. */
class SeedyApi(private val api: ApiClient) {
    suspend fun me(): SeedyMe = api.get<SeedyMe>("/Seedy/Me").content

    /** Saves part of the person's settings (playback and subtitles go whole, custom keys merge); returns them all. */
    suspend fun savePrefs(patch: kotlinx.serialization.json.JsonObject): UserPrefs = api.post<UserPrefs>("/Seedy/Me/Prefs", requestBody = patch).content

    /** When the person's password last changed (only the activity log knows; the plugin reads it for everyone). */
    suspend fun passwordState(): PasswordState = api.get<PasswordState>("/Seedy/Me/Password").content

    /** How many of the person's requests are still open (Settings › My requests). */
    suspend fun openRequests(): Int = api.get<MyRequests>("/Seedy/Requests/Mine").content.open

    suspend fun homeRows(): List<HomeRow> = api.get<HomeRows>("/Seedy/Home").content.rows

    suspend fun featured(): List<FeaturedEntry> = api.get<List<FeaturedEntry>>("/Seedy/Home/Featured").content

    suspend fun top10(): Top10 = api.get<Top10>("/Seedy/Home/Top10").content

    /** Continue watching without the episodes the person moved past (see core/browse/Continue.kt). */
    suspend fun continueWatching(limit: Int, parentId: UUID? = null, fields: List<ItemFields> = emptyList()): List<BaseItemDto> =
        api.get<BaseItemDtoQueryResult>(
            "/Seedy/Home/Continue",
            queryParameters = mapOf("limit" to limit, "parentId" to parentId?.toString(), "fields" to fields.joinToString(",") { it.serialName }),
        ).content.items

    /** Takes a title out of Continue watching (see core/browse/Continue.kt). */
    suspend fun removeFromContinue(id: UUID) {
        api.delete<Unit>("/Seedy/Home/Continue/{id}", mapOf("id" to id))
    }

    companion object {
        /**
         * Asks a server whether it runs Seedy: its public client info, or null for plain Jellyfin (404) and for a
         * plugin whose API this app can't read. When no answer comes, [unanswered] (what was known before).
         */
        suspend fun probe(api: ApiClient, unanswered: SeedyClient? = null): SeedyClient? = try {
            api.get<SeedyClient>("/Seedy/Client").content.takeIf { it.api in SupportedSeedyApi }
        } catch (_: InvalidStatusException) {
            null
        } catch (_: ApiClientException) {
            unanswered
        }
    }
}
