package io.github.arkmind.seedy.mobile

import androidx.compose.ui.test.junit4.createComposeRule
import io.github.arkmind.seedy.core.admin.people.ApiKeysActions
import io.github.arkmind.seedy.core.admin.people.ApiKeysState
import io.github.arkmind.seedy.core.admin.people.DeviceChoice
import io.github.arkmind.seedy.core.admin.people.DeviceKind
import io.github.arkmind.seedy.core.admin.people.DeviceRow
import io.github.arkmind.seedy.core.admin.people.DevicesActions
import io.github.arkmind.seedy.core.admin.people.DevicesState
import io.github.arkmind.seedy.core.admin.people.FolderChoice
import io.github.arkmind.seedy.core.admin.people.KeyRow
import io.github.arkmind.seedy.core.admin.people.ParentalRating
import io.github.arkmind.seedy.core.admin.people.SeedyFlags
import io.github.arkmind.seedy.core.admin.people.UserEditActions
import io.github.arkmind.seedy.core.admin.people.UserEditState
import io.github.arkmind.seedy.core.admin.people.UserRow
import io.github.arkmind.seedy.core.admin.people.UserTab
import io.github.arkmind.seedy.core.admin.people.UsersActions
import io.github.arkmind.seedy.core.admin.people.UsersState
import io.github.arkmind.seedy.mobile.admin.people.ApiKeysScreen
import io.github.arkmind.seedy.mobile.admin.people.DevicesScreen
import io.github.arkmind.seedy.mobile.admin.people.UserScreen
import io.github.arkmind.seedy.mobile.admin.people.UsersScreen
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Boards "Android · Server · Users" and "· Edit user", and the people pages built from the web boards. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AdminPeopleShots {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w390dp-h850dp-mdpi")
    fun users() = compose.adminShot("AndroidAdminUsers") {
        UsersScreen(PeopleSamples.users, object : UsersActions {}, onBack = {}, open = {})
    }

    @Test
    @Config(qualifiers = "w390dp-h2070dp-mdpi")
    fun user() = compose.adminShot("AndroidAdminUser") {
        UserScreen(PeopleSamples.sam, object : UserEditActions {}, onBack = {}, onPickPhoto = {}, open = {})
    }

    @Test
    @Config(qualifiers = "w390dp-h1500dp-mdpi")
    fun userLibraries() = compose.adminShot("AndroidAdminUserLibraries") {
        UserScreen(PeopleSamples.samAccess, object : UserEditActions {}, onBack = {}, onPickPhoto = {}, open = {})
    }

    @Test
    @Config(qualifiers = "w390dp-h2000dp-mdpi")
    fun userParental() = compose.adminShot("AndroidAdminUserParental") {
        UserScreen(PeopleSamples.kidsParental, object : UserEditActions {}, onBack = {}, onPickPhoto = {}, open = {})
    }

    @Test
    @Config(qualifiers = "w390dp-h1000dp-mdpi")
    fun userPassword() = compose.adminShot("AndroidAdminUserPassword") {
        UserScreen(PeopleSamples.sam.copy(tab = UserTab.Password, changes = 0), object : UserEditActions {}, onBack = {}, onPickPhoto = {}, open = {})
    }

    @Test
    @Config(qualifiers = "w390dp-h900dp-mdpi")
    fun userSeedy() = compose.adminShot("AndroidAdminUserSeedy") {
        UserScreen(PeopleSamples.samSeedy, object : UserEditActions {}, onBack = {}, onPickPhoto = {}, open = {})
    }

    @Test
    @Config(qualifiers = "w390dp-h1300dp-mdpi")
    fun devices() = compose.adminShot("AndroidAdminDevices") {
        DevicesScreen(PeopleSamples.devices, object : DevicesActions {}, onBack = {}, open = {})
    }

    @Test
    @Config(qualifiers = "w390dp-h1100dp-mdpi")
    fun apiKeys() = compose.adminShot("AndroidAdminApiKeys") {
        ApiKeysScreen(PeopleSamples.keys, object : ApiKeysActions {}, onBack = {})
    }
}

object PeopleSamples {
    val users = UsersState(
        lede = "5 users · 3 signed in today",
        users = listOf(
            UserRow("a", "Arky", 0, online = true, seen = "Watching now on Living room TV", tags = listOf("Admin"), access = "All libraries · No limits", isAdmin = true, isMe = true),
            UserRow("s", "Sam", 1, online = true, seen = "Watching now on iPhone", access = "All libraries · Remote: 8 Mbps"),
            UserRow("l", "Léa", 2, online = true, seen = "Watching now on Firefox", access = "Movies, Shows · Remote: 8 Mbps"),
            UserRow("k", "Kids", 3, seen = "Last seen yesterday on Living room TV", tags = listOf("TV-PG"), access = "Kids · No remote access · 9:00–20:00"),
            UserRow("g", "Guest", 4, seen = "Last seen 3 months ago", tags = listOf("Disabled", "Hidden"), access = "Movies · Locked after 3 failed sign-ins", disabled = true),
        ),
    )

    private val samPolicy: JsonObject = buildJsonObject {
        put("IsAdministrator", false)
        put("IsHidden", false)
        put("IsDisabled", false)
        put("EnableRemoteAccess", true)
        put("LoginAttemptsBeforeLockout", 0)
        put("MaxActiveSessions", 2)
        put("EnableMediaPlayback", true)
        put("EnableVideoPlaybackTranscoding", true)
        put("EnableAudioPlaybackTranscoding", true)
        put("EnablePlaybackRemuxing", true)
        put("ForceRemoteSourceTranscoding", false)
        put("RemoteClientBitrateLimit", 8_000_000)
        put("SyncPlayAccess", "CreateAndJoinGroups")
        put("EnableContentDownloading", true)
        put("EnableContentDeletion", false)
        put("EnableCollectionManagement", false)
        put("EnableSubtitleManagement", true)
        put("EnableLiveTvAccess", true)
        put("EnableLiveTvManagement", false)
        put("EnableRemoteControlOfOtherUsers", false)
        put("EnableSharedDeviceControl", true)
        put("EnableAllFolders", true)
        put("EnabledFolders", JsonArray(emptyList()))
        put("EnableAllDevices", true)
        put("EnabledDevices", JsonArray(emptyList()))
        put("BlockUnratedItems", JsonArray(emptyList()))
        put("AllowedTags", JsonArray(emptyList()))
        put("BlockedTags", JsonArray(emptyList()))
        put("AccessSchedules", JsonArray(emptyList()))
    }

    private fun JsonObject.with(vararg pairs: Pair<String, Any>): JsonObject = JsonObject(this + pairs.associate { (k, v) ->
        k to when (v) {
            is Boolean -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is String -> JsonPrimitive(v)
            is List<*> -> JsonArray(v.map { if (it is JsonObject) it else JsonPrimitive(it.toString()) })
            else -> error("sample value")
        }
    })

    val sam = UserEditState(
        id = "s",
        loaded = true,
        savedName = "Sam",
        name = "Sam",
        index = 1,
        presence = "Watching now on iPhone",
        online = true,
        meta = "Signed in on 3 devices · created March 2024",
        saved = samPolicy,
        policy = samPolicy.with("ForceRemoteSourceTranscoding" to true),
        seedyOn = true,
        seedyLoaded = true,
        changes = 1,
    )

    private val folders = listOf(
        FolderChoice("m", "Movies", "Movies · /media/Movies"),
        FolderChoice("t", "Shows", "Shows · /media/Shows"),
        FolderChoice("k", "Kids", "Shows · /media/Kids"),
        FolderChoice("c", "Concerts", "Music videos · /media/Concerts"),
    )

    private val choices = listOf(
        DeviceChoice("d1", "Sam’s iPhone", "Seedy · Sam", theirs = true),
        DeviceChoice("d2", "Sam’s iPad", "Swiftfin · Sam", theirs = true),
        DeviceChoice("d3", "Living room TV", "Seedy · Alex", theirs = false),
        DeviceChoice("d4", "Office PC", "Jellyfin Media Player · Alex", theirs = false),
    )

    val samAccess = sam.copy(
        tab = UserTab.Access,
        folders = folders,
        devices = choices,
        saved = samPolicy.with("EnableAllFolders" to false, "EnabledFolders" to listOf("m", "t"), "EnableAllDevices" to false, "EnabledDevices" to listOf("d1", "d2", "d3")),
        policy = samPolicy.with("EnableAllFolders" to false, "EnabledFolders" to listOf("m", "t"), "EnableAllDevices" to false, "EnabledDevices" to listOf("d1", "d3")),
        changes = 1,
    )

    private val ratings = listOf(
        ParentalRating("G", 0, null), ParentalRating("TV-Y", 0, null), ParentalRating("TV-G", 0, null),
        ParentalRating("TV-Y7", 7, null), ParentalRating("PG", 10, null), ParentalRating("TV-PG", 10, null),
        ParentalRating("PG-13", 13, null), ParentalRating("TV-14", 14, null), ParentalRating("R", 17, null), ParentalRating("TV-MA", 17, null),
    )

    private val schedule = buildJsonObject { put("DayOfWeek", "Everyday"); put("StartHour", 9); put("EndHour", 20) }
    private val weekend = buildJsonObject { put("DayOfWeek", "Weekend"); put("StartHour", 8.5); put("EndHour", 21) }

    private val kidsPolicy = samPolicy.with(
        "MaxParentalRating" to 10,
        "EnableRemoteAccess" to false,
        "BlockUnratedItems" to listOf("Movie", "Series"),
        "AllowedTags" to emptyList<String>(),
        "BlockedTags" to listOf("Horror", "Gore"),
        "AccessSchedules" to listOf(schedule, weekend),
    )

    val kidsParental = sam.copy(
        tab = UserTab.Parental,
        savedName = "Kids",
        name = "Kids",
        index = 3,
        online = false,
        presence = "Last seen yesterday on Living room TV",
        meta = "Signed in on 1 device · created March 2024",
        ratings = ratings,
        saved = kidsPolicy,
        policy = kidsPolicy.with("MaxParentalRating" to 13),
        changes = 1,
    )

    val samSeedy = sam.copy(
        tab = UserTab.Seedy,
        linked = true,
        seerrName = "Sam",
        savedSeedy = SeedyFlags(canRequest = true, top10 = true, history = true),
        seedy = SeedyFlags(canRequest = true, top10 = false, history = true),
        policy = samPolicy,
        changes = 1,
    )

    private fun dev(id: String, name: String, kind: DeviceKind, app: String, user: String, index: Int, last: String, playing: String? = null, mine: Boolean = false) =
        DeviceRow(id, name, name, kind, app, id, user, index, null, last, playing, mine)

    val devices = DevicesState(
        devices = listOf(
            dev("1", "Living room TV", DeviceKind.Tv, "Seedy 0.1 · Android TV", "Alex", 0, "Now", "Playing Northbound"),
            dev("2", "Sam’s iPhone", DeviceKind.Phone, "Seedy 0.1 · Safari", "Sam", 1, "Now", "Playing Glass Harbor"),
            dev("3", "Léa’s laptop", DeviceKind.Computer, "Jellyfin Web 12.2 · Firefox", "Léa", 2, "Now", "Playing Low Tide Club"),
            dev("4", "Alex’s Pixel", DeviceKind.Phone, "Seedy 0.1", "Alex", 0, "2 min ago", mine = true),
            dev("5", "Bedroom Fire TV", DeviceKind.Tv, "Jellyfin for Fire TV 0.18", "Kids", 3, "Yesterday, 19:40"),
            dev("6", "Sam’s iPad", DeviceKind.Phone, "Swiftfin 1.6", "Sam", 1, "14 Oct"),
            dev("7", "Office PC", DeviceKind.Computer, "Jellyfin Media Player 2.0", "Alex", 0, "9 Oct"),
            dev("8", "Old Android phone", DeviceKind.Phone, "Findroid 0.15", "Léa", 2, "2 Aug"),
        ),
        people = listOf("1" to "Alex", "2" to "Sam", "3" to "Léa", "4" to "Kids"),
    )

    val keys = ApiKeysState(
        created = "Home Assistant" to "3f9c1e7a5b2d4c8e9a0f6b1d2e3c4a5b",
        keys = listOf(
            KeyRow("a", "Home Assistant", "•••• 4a5b", "Created today", "Never used", stale = false),
            KeyRow("b", "Seerr", "•••• c7d1", "Created 2 Mar 2026", "Last used 3 min ago", stale = false),
            KeyRow("c", "Sonarr", "•••• 91fe", "Created 2 Mar 2026", "Last used 1 hour ago", stale = false),
            KeyRow("d", "Radarr", "•••• 0b3a", "Created 2 Mar 2026", "Last used 1 hour ago", stale = false),
            KeyRow("e", "Old Kodi sync", "•••• e88c", "Created 14 Nov 2024", "Last used 6 months ago · unused", stale = true),
        ),
    )
}
