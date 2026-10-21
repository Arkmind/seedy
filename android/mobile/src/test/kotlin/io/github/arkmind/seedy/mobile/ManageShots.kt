package io.github.arkmind.seedy.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import io.github.arkmind.seedy.ui.components.LocalOverlayHost
import io.github.arkmind.seedy.ui.components.OverlayHost
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.arkmind.seedy.core.manage.AddTarget
import io.github.arkmind.seedy.core.manage.AddToActions
import io.github.arkmind.seedy.core.manage.AddToState
import io.github.arkmind.seedy.core.manage.Audience
import io.github.arkmind.seedy.core.manage.DeleteActions
import io.github.arkmind.seedy.core.manage.DeleteGroup
import io.github.arkmind.seedy.core.manage.DeleteItem
import io.github.arkmind.seedy.core.manage.DeletePreview
import io.github.arkmind.seedy.core.manage.DeleteViewModel
import io.github.arkmind.seedy.core.manage.IdentifyActions
import io.github.arkmind.seedy.core.manage.IdentifyState
import io.github.arkmind.seedy.core.manage.ImageSlot
import io.github.arkmind.seedy.core.manage.ImagesActions
import io.github.arkmind.seedy.core.manage.ImagesState
import io.github.arkmind.seedy.core.manage.ListKind
import io.github.arkmind.seedy.core.manage.ListRow
import io.github.arkmind.seedy.core.manage.Match
import io.github.arkmind.seedy.core.manage.MetaPerson
import io.github.arkmind.seedy.core.manage.MetadataActions
import io.github.arkmind.seedy.core.manage.MetadataState
import io.github.arkmind.seedy.core.manage.NewPlaylistActions
import io.github.arkmind.seedy.core.manage.NewPlaylistState
import io.github.arkmind.seedy.core.manage.OfferedImage
import io.github.arkmind.seedy.core.manage.ProviderField
import io.github.arkmind.seedy.core.manage.SeerrMedia
import io.github.arkmind.seedy.core.manage.SharePerson
import io.github.arkmind.seedy.core.manage.SharedWith
import io.github.arkmind.seedy.core.manage.SlotImage
import io.github.arkmind.seedy.mobile.details.DetailsActions
import io.github.arkmind.seedy.mobile.details.DetailsScreen
import io.github.arkmind.seedy.mobile.library.LibraryActions
import io.github.arkmind.seedy.mobile.library.LibraryScreen
import io.github.arkmind.seedy.mobile.manage.AddToContent
import io.github.arkmind.seedy.mobile.manage.DeleteContent
import io.github.arkmind.seedy.mobile.manage.IdentifyScreen
import io.github.arkmind.seedy.mobile.manage.ImagesScreen
import io.github.arkmind.seedy.mobile.manage.ItemMenuSheet
import io.github.arkmind.seedy.mobile.manage.ManageIcons
import io.github.arkmind.seedy.mobile.manage.MenuEntry
import io.github.arkmind.seedy.mobile.manage.MetadataScreen
import io.github.arkmind.seedy.mobile.manage.NewPlaylistContent
import io.github.arkmind.seedy.mobile.manage.SelectionActions
import io.github.arkmind.seedy.mobile.manage.SelectionBar
import io.github.arkmind.seedy.mobile.parts.Tab
import io.github.arkmind.seedy.mobile.parts.TabBar
import io.github.arkmind.seedy.mobile.parts.tabBarHeight
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.SeedyTheme
import io.github.arkmind.seedy.ui.theme.Viewer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * No Android boards: the phone's item management (the web boards DetailsMore, ItemDelete, PlaylistNew, LibrarySelect,
 * ItemEditMetadata, ItemIdentify and ItemImages) built from the phone kit, at the phone boards' size.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-mdpi", sdk = [35])
class ManageShots {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, tab: Tab? = null, content: @Composable () -> Unit) {
        compose.setContent {
            SeedyTheme {
                CompositionLocalProvider(LocalViewer provides Viewer(name = "Arky", requests = true)) {
                    // Sheets cover the tab bar, as the app's overlay host draws them.
                    val overlay = remember { OverlayHost() }
                    CompositionLocalProvider(LocalOverlayHost provides overlay) {
                        Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
                            content()
                            if (tab != null) TabBar(Tab.entries, tab, {}, Modifier.align(Alignment.BottomCenter))
                            overlay.content?.invoke()
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Composable
    private fun details() = DetailsScreen(BrowseSamples.details, DetailsActions(onMore = {}), canRequest = true, bottomPadding = tabBarHeight())

    private val target = AddTarget(listOf("n"), "Northbound", "Series")

    @Test
    fun menu() = shot("AndroidItemMenu", Tab.Home) {
        details()
        ItemMenuSheet(
            "Northbound",
            listOf(
                MenuEntry(ManageIcons.PlayFromStart, "Play from the first episode") {},
                MenuEntry(WebIcons.Check, "Mark Season 3 watched") {},
                MenuEntry(ManageIcons.PlaylistAdd, "Add to playlist", more = true) {},
                MenuEntry(ManageIcons.Collection, "Add to collection", more = true) {},
                MenuEntry(ManageIcons.Edit, "Edit metadata", group = "Manage", line = true) {},
                MenuEntry(ManageIcons.Identify, "Identify", "Fix a wrong match") {},
                MenuEntry(ManageIcons.Image, "Edit images") {},
                MenuEntry(ManageIcons.Refresh, "Refresh metadata", "New info and artwork from providers") {},
                MenuEntry(ManageIcons.Trash, "Delete series", "2 seasons · 252 GB", danger = true, line = true) {},
            ),
            {},
        )
    }

    @Test
    fun addToPlaylist() = shot("AndroidAddToPlaylist", Tab.Home) {
        details()
        AddToContent(
            AddToState(
                ListKind.Playlist,
                rows = listOf(
                    ListRow("1", "Winter shows", "6 items · only you", added = true),
                    ListRow("2", "Weekend binge", "12 items · only you", added = false),
                    ListRow("3", "Watch with Léa", "4 items · shared with Léa", added = false),
                    ListRow("4", "Family picks", "9 items · everyone on the server", added = false),
                ),
            ),
            object : AddToActions {}, target, {}, {},
        )
    }

    @Test
    fun newPlaylist() = shot("AndroidNewPlaylist", Tab.Home) {
        details()
        NewPlaylistContent(
            NewPlaylistState(
                name = "Winter rewatch", startsWith = "Starts with Northbound", startsSub = "20 episodes on the server, in order", audience = Audience.People,
                everyone = listOf(SharePerson("s", "Sam"), SharePerson("l", "Léa"), SharePerson("k", "Kids")),
                people = listOf(SharedWith(SharePerson("s", "Sam"), canEdit = true), SharedWith(SharePerson("l", "Léa"), canEdit = false)),
            ),
            object : NewPlaylistActions {}, {},
        )
    }

    /** The board's Northbound: two seasons, the artwork around them, Seerr and a Sonarr that still monitors it. */
    val northbound = DeletePreview(
        listOf(
            DeleteItem(
                "n", "Northbound", "Series", true, 252_200_000_000, listOf("/media/shows/Northbound"),
                listOf(
                    DeleteGroup("season", "s1", "Season 1", 1, 10, 10, 214_000_000_000, 2160, "hevc"),
                    DeleteGroup("season", "s3", "Season 3", 3, 2, 2, 38_000_000_000, 2160, "hevc"),
                    DeleteGroup("extras", episodes = 0, files = 46, bytes = 200_000_000),
                ),
                SeerrMedia("tv", 4242, "Sonarr", true),
            ),
        ),
        252_200_000_000,
    )

    @Test
    fun delete() = shot("AndroidDelete", Tab.Home) {
        details()
        DeleteContent(DeleteViewModel.describe(northbound, continuing = true).copy(typed = "Northbound"), object : DeleteActions {}, {})
    }

    @Test
    fun librarySelect() = shot("AndroidLibrarySelect", Tab.Libraries) {
        val movies = BrowseSamples.moviesLibrary
        val picked = setOf(movies.items[1].id, movies.items[2].id, movies.items[6].id, movies.items[10].id)
        Box(Modifier.fillMaxSize()) {
            LibraryScreen(movies, LibraryActions(onPick = {}), bottomPadding = tabBarHeight(), selection = picked)
            SelectionBar(
                4, 248,
                SelectionActions(onSelectAll = {}, onPlaylist = {}, onWatched = {}, onRefresh = {}, onDelete = {}, onCollection = {}),
                Modifier.align(Alignment.TopCenter),
            )
        }
    }

    private val meta = MetadataState(
        loading = false, name = "Northbound", facts = "Series · /media/shows/Northbound · last refreshed 18 Oct", type = "Series",
        title = "Northbound", originalTitle = "Northbound", sortTitle = "Northbound", premiere = LocalDate.of(2024, 3, 1), status = "Continuing",
        rating = "TV-MA", overview = BrowseSamples.northbound.overview.orEmpty(), genres = listOf("Drama", "Thriller", "Mystery"),
        tags = listOf("Winter", "Small town"), studios = listOf("Halden"), community = "8.4",
        people = listOf(MetaPerson("Petra Lind", "Kit Halvorsen", "Actor"), MetaPerson("Tomas Reyes", "Oren Vale", "Actor"), MetaPerson("Mara Ellison", "", "Creator")),
        providers = listOf(ProviderField("Tmdb", "TMDb", "219408"), ProviderField("Tvdb", "TVDb", "402117"), ProviderField("Imdb", "IMDb", "")),
        locked = setOf("Name", "Overview"), dirty = true,
    )

    @Test
    @Config(qualifiers = "w390dp-h1500dp-mdpi")
    fun editMetadata() = shot("AndroidEditMetadata") {
        MetadataScreen(meta, object : MetadataActions {}, null, {})
    }

    @Test
    @Config(qualifiers = "w390dp-h1300dp-mdpi")
    fun identify() = shot("AndroidIdentify") {
        val r = kotlinx.serialization.json.JsonObject(emptyMap())
        IdentifyScreen(
            IdentifyState(
                loading = false, type = "Series", name = "Northbound", year = "2024",
                ids = listOf(ProviderField("Tmdb", "TMDb", ""), ProviderField("Tvdb", "TVDb", ""), ProviderField("Imdb", "IMDb", "")),
                sources = listOf("TMDb", "TVDb"),
                matches = listOf(
                    Match("Northbound", "2024 · TMDb", "TMDb 219408 · TVDb 402117", null, false, r),
                    Match("Northbound", "2011 · TVDb", "TVDb 241077", null, true, r),
                    Match("North Bound", "2019 · TMDb", "TMDb 90214", null, false, r),
                    Match("Northbound Train", "2022 · TMDb", "TMDb 131009", null, false, r),
                ),
                picked = 0,
            ),
            object : IdentifyActions {}, {},
        )
    }

    @Test
    @Config(qualifiers = "w390dp-h1300dp-mdpi")
    fun images() = shot("AndroidImages") {
        ImagesScreen(
            ImagesState(
                loading = false, name = "Northbound", picked = "Backdrop",
                slots = listOf(
                    ImageSlot("Primary", "Poster", "1000 × 1500", listOf(SlotImage(null, "", 1000, 1500)), false),
                    ImageSlot("Backdrop", "Backdrops · 3", "Behind the title", List(3) { SlotImage(it, "", 3840, 2160) }, true),
                    ImageSlot("Logo", "Logo", "Title art", listOf(SlotImage(null, "", 800, 310)), false),
                    ImageSlot("Thumb", "Thumbnail", "16:9 cards · 1920 × 1080", listOf(SlotImage(null, "", 1920, 1080)), false),
                    ImageSlot("Banner", "Banner", "None yet · some TV apps use it", emptyList(), false),
                ),
                offered = listOf(
                    OfferedImage("a", "", "3840 × 2160 · TMDb · no text", true), OfferedImage("b", "", "3840 × 2160 · Fanart · no text", false),
                    OfferedImage("c", "", "1920 × 1080 · TMDb · English", false), OfferedImage("d", "", "3840 × 2160 · TMDb · no text", true),
                    OfferedImage("e", "", "1920 × 1080 · TVDb · no text", false), OfferedImage("f", "", "3840 × 2160 · Fanart · English", false),
                ),
                offeredTotal = 24, providers = listOf("TheMovieDb", "TheTVDB"),
            ),
            object : ImagesActions {}, {},
        )
    }
}
