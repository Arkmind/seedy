package io.github.arkmind.seedy.core.seedy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.jellyfin.sdk.model.api.BaseItemDto

/** The /Seedy API levels this app can read (the plugin's ConfigMapper.ApiLevel). */
val SupportedSeedyApi = 1..1

/** GET /Seedy/Client (anonymous): branding, the plugin version and its API level. */
@Serializable
data class SeedyClient(
    val name: String = "Seedy",
    val accent: String = "#ff9fff",
    val hasLogo: Boolean = false,
    val logoVersion: String = "0",
    val signInBackground: String = "backdrops",
    val version: String? = null,
    val api: Int = 0,
)

/** GET /Seedy/Me: what this person can use, their profile settings and the admin's home rows. */
@Serializable
data class SeedyMe(
    val isAdmin: Boolean = false,
    val branding: SeedyClient = SeedyClient(),
    val prefs: UserPrefs = UserPrefs(),
    val requests: RequestsFeatures = RequestsFeatures(),
    val historyEnabled: Boolean = false,
    val homeRows: List<HomeRowSetting> = emptyList(),
    val pluginVersion: String = "",
)

@Serializable
data class UserPrefs(
    val adaptRowOrder: Boolean = true,
    val hiddenRows: List<String> = emptyList(),
    val hiddenSeeds: List<String> = emptyList(),
    val rowOrder: String = "auto",
    /** Namespaced settings shared with the web app, e.g. "display.language", "home.episodeImages". */
    val custom: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class RequestsFeatures(
    val connected: Boolean = false,
    val discover: Boolean = false,
    val upcomingMovies: Boolean = false,
    val requestButtons: Boolean = false,
    val requestInSearch: Boolean = false,
    val canRequest: Boolean = false,
    val allow4k: Boolean = false,
    val seerrUrl: String? = null,
)

@Serializable
data class HomeRowSetting(val kind: String, val enabled: Boolean = true, val count: Int? = null)

/** One row of the home screen, as Seedy orders it for this person. The app loads its titles from Jellyfin. */
@Serializable
data class HomeRow(
    val id: String,
    val kind: String,
    val title: String? = null,
    val seedId: String? = null,
    val seedName: String? = null,
    val genre: String? = null,
    val libraryId: String? = null,
    val libraryName: String? = null,
    val collectionType: String? = null,
    val collectionId: String? = null,
)

@Serializable
internal data class HomeRows(val rows: List<HomeRow> = emptyList())

/** A title for the hero: [reason] is why it's there ("continue", "new-episode"…), [play] what Play starts. */
@Serializable
data class FeaturedEntry(val item: BaseItemDto, val reason: String = "", val play: BaseItemDto? = null)

@Serializable
data class Top10(val items: List<Top10Entry> = emptyList(), val quiet: Boolean = false)

@Serializable
data class Top10Entry(val item: BaseItemDto, val profiles: Int = 0, val plays: Int = 0)
