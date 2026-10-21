package io.github.arkmind.seedy.core.seedy

import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.get
import org.jellyfin.sdk.api.client.extensions.post

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

    companion object {
        /**
         * Asks a server whether it runs Seedy: its public client info, or null for plain Jellyfin (404), for a plugin
         * whose API this app can't read, and when the answer doesn't come.
         */
        suspend fun probe(api: ApiClient): SeedyClient? = try {
            api.get<SeedyClient>("/Seedy/Client").content.takeIf { it.api in SupportedSeedyApi }
        } catch (_: ApiClientException) {
            null
        }
    }
}
