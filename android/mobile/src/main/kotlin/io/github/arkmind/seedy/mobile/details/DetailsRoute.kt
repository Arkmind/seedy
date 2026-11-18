package io.github.arkmind.seedy.mobile.details

import android.os.Environment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.browse.Downloads
import io.github.arkmind.seedy.core.feature.DetailsViewModel
import io.github.arkmind.seedy.core.format.isVirtual
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.manage.AddTarget
import io.github.arkmind.seedy.core.manage.BulkViewModel
import io.github.arkmind.seedy.core.seedy.canRequest
import io.github.arkmind.seedy.core.session.withApiKey
import io.github.arkmind.seedy.mobile.manage.ItemMenuSheet
import io.github.arkmind.seedy.mobile.manage.ManageIcons
import io.github.arkmind.seedy.mobile.manage.ManagePerms
import io.github.arkmind.seedy.mobile.manage.ManageSheetHost
import io.github.arkmind.seedy.mobile.manage.MenuEntry
import io.github.arkmind.seedy.mobile.manage.playlistable
import io.github.arkmind.seedy.mobile.manage.rememberManageSheets
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.nav.MobileNav
import io.github.arkmind.seedy.mobile.nav.Route
import io.github.arkmind.seedy.mobile.parts.SaveFile
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.parts.downloadFiles
import io.github.arkmind.seedy.mobile.seasons.RequestSeasonsSheet
import io.github.arkmind.seedy.ui.components.openUrl
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.currentSession
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/** Boards "Android · Series details" and "· Request seasons"; the More menu carries the web's DetailsMore. */
@Composable
fun DetailsRoute(id: UUID) {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val context = LocalContext.current
    val images = LocalViewer.current.images
    val vm = viewModel(key = "details:$id") { DetailsViewModel(session, id) }
    val bulk = viewModel(key = "bulk:$id") { BulkViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    val bulkState by bulk.state.collectAsStateWithLifecycle()
    val perms = remember(session) { ManagePerms.of(session) }
    val sheets = rememberManageSheets()
    var menu by remember { mutableStateOf(false) }
    // Back from the player: progress and watched marks may have changed.
    LaunchedEffect(Unit) { vm.refresh() }
    // The seasons the request sheet starts with ticked (empty: every open one); null while it's closed.
    var ask by rememberSaveable { mutableStateOf<List<Int>?>(null) }
    Box(Modifier.fillMaxSize()) {
        val item = state.item
        DetailsScreen(
            state = state,
            actions = DetailsActions(
                onBack = nav::back,
                onPlay = { vm.play(nav::play) },
                onEpisode = nav::play,
                onOpen = nav::open,
                onTrailer = {
                    vm.trailer { trailer ->
                        when (trailer) {
                            is DetailsViewModel.Trailer.Local -> nav.play(trailer.item)
                            is DetailsViewModel.Trailer.Url -> context.openUrl(trailer.url)
                        }
                    }
                },
                onPlayed = vm::togglePlayed,
                onFavorite = vm::toggleFavorite,
                onShuffle = { vm.shuffle(nav::play) },
                onSeason = vm::selectSeason,
                onRequest = { ask = it },
                onMore = if (item != null && item.type != BaseItemKind.PERSON) ({
                    // The Delete line says how much goes ("2 seasons · 252 GB"); a playlist or collection takes no files.
                    if (item.canDelete == true && item.type != BaseItemKind.PLAYLIST && item.type != BaseItemKind.BOX_SET) bulk.loadDeleteSub(listOf(item.id.toString()))
                    menu = true
                }) else null,
            ),
            canRequest = session.canRequest,
            bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        )
        val target = item?.let { vm.seasonsTarget(images?.poster(it, 200), images?.backdrop(it, 780)) }
        if (ask != null && target != null) {
            RequestSeasonsSheet(target, onDismiss = { ask = null }, onDone = vm::markRequested, details = state.seerr, initial = ask)
        }
        if (menu && item != null) {
            val add = AddTarget(listOf(item.id.toString()), item.name.orEmpty(), item.type.serialName, images?.poster(item, 120))
            // Movies under the phone's Movies, episodes in their show's folder there.
            val save = { list: List<BaseItemDto> ->
                downloadFiles(context, list.map { SaveFile(session.api.withApiKey("/Items/${it.id}/Download"), Downloads.path(it), it.name.orEmpty()) }, Environment.DIRECTORY_MOVIES)
            }
            val download = Download(
                item = { bulk.say(if (save(listOf(item)) > 0) t("Downloading {name}", "name" to item.name.orEmpty()) else t("Couldn’t download {name}", "name" to item.name.orEmpty())) },
                season = if (session.user.policy?.enableContentDownloading == true) ({
                    vm.seasonFiles { season, files ->
                        val name = season.name.orEmpty()
                        val n = files?.let(save) ?: 0
                        bulk.say(
                            when {
                                n > 0 -> tn(n, "Downloading {n} episode", "Downloading {n} episodes")
                                files?.isEmpty() == true -> t("Nothing to download in {name}", "name" to name)
                                else -> t("Couldn’t download {name}", "name" to name)
                            },
                        )
                    }
                }) else null,
            )
            ItemMenuSheet(item.name.orEmpty(), menuEntries(state, item, perms, bulkState.deleteSub, nav, vm, bulk, download, onSheet = { sheets.show(it, add, item.status == "Continuing") }), { menu = false })
        }
        ManageSheetHost(sheets, say = { bulk.say(it) }, onDeleted = { _, _ -> nav.back() })
        Toast(bulkState.message, bulk::messageShown, Modifier.align(Alignment.BottomCenter))
    }
}

/** Saving files from the More menu: the page's movie or episode, and the open season's episodes (when this profile may). */
private class Download(val item: () -> Unit, val season: (() -> Unit)?)

/** The More menu's lines for [item], as far as this person may go. */
private fun menuEntries(
    state: DetailsViewModel.State,
    item: BaseItemDto,
    perms: ManagePerms,
    deleteSub: String?,
    nav: MobileNav,
    vm: DetailsViewModel,
    bulk: BulkViewModel,
    download: Download,
    onSheet: (String) -> Unit,
): List<MenuEntry> = buildList {
    val series = item.type == BaseItemKind.SERIES
    if (series) add(MenuEntry(ManageIcons.PlayFromStart, t("Play from the first episode"), run = { bulk.firstEpisode(item.id, nav::play) }))
    state.selectedSeason?.let { season ->
        val eps = state.shownEpisodes.orEmpty().filter { !it.isVirtual }
        val all = eps.isNotEmpty() && eps.all { it.userData?.played == true }
        val name = season.name.orEmpty()
        add(MenuEntry(WebIcons.Check, if (all) t("Mark {name} unwatched", "name" to name) else t("Mark {name} watched", "name" to name), run = vm::toggleSeasonPlayed))
        if (download.season != null && !season.isVirtual) add(MenuEntry(WebIcons.Download, t("Download {name}", "name" to name), run = download.season))
    }
    if (playlistable(item)) add(MenuEntry(ManageIcons.PlaylistAdd, t("Add to playlist"), more = true, run = { onSheet("playlist") }))
    if (perms.collections && item.type != BaseItemKind.BOX_SET && item.type != BaseItemKind.PLAYLIST) {
        add(MenuEntry(ManageIcons.Collection, t("Add to collection"), more = true, run = { onSheet("collection") }))
    }
    if (item.canDownload == true && Downloads.isVideoFile(item)) add(MenuEntry(WebIcons.Download, t("Download"), Downloads.sub(item), run = download.item))
    if (perms.admin) {
        val id = item.id.toString()
        add(MenuEntry(ManageIcons.Edit, t("Edit metadata"), group = t("Manage"), line = isNotEmpty(), run = { nav.go(Route.Admin("metadata", id)) }))
        if (item.type != BaseItemKind.PLAYLIST && item.type != BaseItemKind.BOX_SET) {
            add(MenuEntry(ManageIcons.Identify, t("Identify"), t("Fix a wrong match"), run = { nav.go(Route.Admin("identify", id)) }))
        }
        add(MenuEntry(ManageIcons.Image, t("Edit images"), run = { nav.go(Route.Admin("images", id)) }))
        add(MenuEntry(ManageIcons.Refresh, t("Refresh metadata"), t("New info and artwork from providers"), run = { bulk.refresh(listOf(id)) }))
    }
    if (item.canDelete == true) {
        val label = when (item.type) {
            BaseItemKind.SERIES -> t("Delete series")
            BaseItemKind.MOVIE -> t("Delete movie")
            BaseItemKind.EPISODE -> t("Delete episode")
            BaseItemKind.SEASON -> t("Delete season")
            BaseItemKind.PLAYLIST -> t("Delete playlist")
            BaseItemKind.BOX_SET -> t("Delete collection")
            else -> t("Delete")
        }
        add(MenuEntry(ManageIcons.Trash, label, deleteSub, danger = true, line = isNotEmpty(), run = { onSheet("delete") }))
    }
}
