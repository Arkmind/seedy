package io.github.arkmind.seedy.mobile.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.feature.RequestsViewModel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.mediarequests.MediaRequestsViewModel
import io.github.arkmind.seedy.core.mediarequests.MediaTab
import io.github.arkmind.seedy.core.mediarequests.RequestService
import io.github.arkmind.seedy.core.requests.SeerrRequest
import io.github.arkmind.seedy.core.requests.SeerrTitle
import io.github.arkmind.seedy.core.requests.TitlesKind
import io.github.arkmind.seedy.core.seedy.libraryUuid
import io.github.arkmind.seedy.mobile.browse.topInset
import io.github.arkmind.seedy.mobile.library.Message
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.nav.MobileNav
import io.github.arkmind.seedy.mobile.nav.Route
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.parts.tabBarHeight
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession
import java.time.LocalDate

/** What the Requests tab does; the route wires these to the view model and navigation. */
class RequestsActions(
    val onTab: (RequestsViewModel.Tab) -> Unit = {},
    val onSearch: () -> Unit = {},
    /** Opens Seerr in the browser; null when the server doesn't say where it is. */
    val onOpenSeerr: (() -> Unit)? = null,
    val onTitle: (SeerrTitle) -> Unit = {},
    val onRequest: (SeerrRequest) -> Unit = {},
    val onSeeAll: (TitlesKind) -> Unit = {},
    val onApprove: (SeerrRequest) -> Unit = {},
    val onDecline: (SeerrRequest) -> Unit = {},
    val onRetry: (SeerrRequest) -> Unit = {},
    val onMessageShown: () -> Unit = {},
    /** The next page of My requests or All requests. */
    val onMore: (RequestsViewModel.Tab) -> Unit = {},
)

/**
 * Board "Android · Requests" (the Requests tab): Movies & shows from Seerr, Music from Lidarr and Books from Shelfarr
 * or Librarr, as tabs over the page for the services the server offers (web: requests/frame.tsx).
 */
@Composable
fun RequestsTab() {
    val session = currentSession()
    val me by session.seedyFlow.collectAsStateWithLifecycle()
    val media = me?.media
    val services = buildList {
        if (session.hasRequests) add(RequestService.Movies)
        if (media?.music == true) add(RequestService.Music)
        if (media?.books == true) add(RequestService.Books)
    }
    var picked by rememberSaveable { mutableStateOf<RequestService?>(null) }
    val service = picked?.takeIf { it in services } ?: services.firstOrNull() ?: RequestService.Movies
    val mediaVm = if (media?.music == true || media?.books == true) viewModel { MediaRequestsViewModel(session) } else null
    val tabs = if (services.size > 1) {
        ServiceTabs(services, service) {
            picked = it
            // Another service opens on its Discover, as the web's links do.
            mediaVm?.setTab(it == RequestService.Music, MediaTab.Discover)
        }
    } else null
    if (service == RequestService.Movies || mediaVm == null) MoviesRequests(tabs) else MediaRequestsTab(mediaVm, service == RequestService.Music, tabs)
}

/** Movies & shows: Seerr's Discover and requests. */
@Composable
private fun MoviesRequests(services: ServiceTabs?) {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val context = LocalContext.current
    val vm = viewModel { RequestsViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    val seerr = session.seedy?.requests?.seerrUrl?.trim()?.takeIf { it.isNotEmpty() }
    RequestsScreen(
        state,
        RequestsActions(
            onTab = vm::setTab,
            onSearch = { nav.go(Route.Search) },
            onOpenSeerr = seerr?.let { url -> { context.openUrl(url) } },
            onTitle = { nav.openSeerr(it.mediaType, it.tmdbId, it.jellyfinItemId) },
            onRequest = { nav.openSeerr(it.mediaType, it.tmdbId, it.jellyfinItemId) },
            onSeeAll = { nav.go(Route.SeerrTitles(it.key)) },
            onApprove = vm::approve,
            onDecline = vm::decline,
            onRetry = vm::retry,
            onMessageShown = vm::clearMessage,
            onMore = vm::loadMore,
        ),
        bottomPadding = tabBarHeight(),
        services = services,
    )
}

/** Music or Books: Lidarr's or Shelfarr's Discover, the requests, and the request windows. */
@Composable
private fun MediaRequestsTab(vm: MediaRequestsViewModel, music: Boolean, services: ServiceTabs?) {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(music) { vm.open(music) }
    val open = mediaOpeners { vm.say(it) }
    val media = session.seedy?.media
    val appUrl = if (music) media?.lidarrUrl else media?.shelfarrUrl ?: media?.librarrUrl ?: media?.readarrUrl
    MediaRequestsScreen(
        state, music, services,
        MediaActions(
            onTab = { vm.setTab(music, it) },
            onSearch = { nav.go(Route.MediaRequestsSearch(music)) },
            onOpenApp = appUrl?.takeIf { it.isNotBlank() }?.let { url -> { context.openUrl(url) } },
            onAlbum = open.album,
            onBook = open.book,
            onSeeAll = { nav.go(Route.MediaRequestsRow(music, it)) },
            onShow = vm::setShow,
            onRetry = { vm.open(music) },
            onReady = open.ready,
            onOpenItem = { r -> r.itemId?.let(open.item) },
            onApprove = vm::approve,
            onDecline = vm::decline,
            onSearchNow = vm::searchNow,
            onAcceptLower = vm::acceptLower,
            onRemove = vm::remove,
            appUrl = { r ->
                val url = when (r.service) { "lidarr" -> media?.lidarrUrl; "shelfarr" -> media?.shelfarrUrl; "librarr" -> media?.librarrUrl; else -> media?.readarrUrl }
                url?.takeIf { it.isNotBlank() }?.let { { context.openUrl(it) } }
            },
            cover = vm.api::bookCover,
            onMessageShown = vm::clearMessage,
        ),
        bottomPadding = tabBarHeight(),
    )
}

/** A Seerr title opens its library page when it's on the server, else its Seerr page. */
fun MobileNav.openSeerr(mediaType: String, tmdbId: Int, jellyfinItemId: String?) {
    val local = libraryUuid(jellyfinItemId)
    go(if (local != null) Route.Details(local) else Route.SeerrTitle(mediaType, tmdbId))
}

/**
 * The header with Search Seerr and Open Seerr, the Discover · My requests (· All requests) switch, the requests in
 * progress, and Discover's rows of titles to request; the other tabs list requests.
 */
@Composable
fun RequestsScreen(
    state: RequestsViewModel.State,
    actions: RequestsActions,
    bottomPadding: Dp,
    today: LocalDate = LocalDate.now(),
    services: ServiceTabs? = null,
) {
    val summary = state.summary
    Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding + 24.dp)) {
            item(key = "head") {
                Column(Modifier.padding(start = 20.dp, end = 16.dp, top = topInset()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RequestsTitleRow(actions.onSearch, actions.onOpenSeerr)
                    if (services != null) ServiceTabsRow(services, Modifier.padding(end = 4.dp))
                    Tabs(state, actions, Modifier.padding(end = 4.dp))
                    if (state.tab == RequestsViewModel.Tab.Discover && state.inProgress.isNotEmpty()) {
                        InProgressCard(
                            state.inProgress, state.waiting, state.downloading, state.searching, state.ready,
                            onClick = { actions.onTab(RequestsViewModel.Tab.Mine) }, modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
            }
            when (state.tab) {
                RequestsViewModel.Tab.Discover -> discover(state, actions, today)
                RequestsViewModel.Tab.Mine -> requests(RequestsViewModel.Tab.Mine, everyone = false, canManage = summary?.canManage == true, state, actions, today)
                RequestsViewModel.Tab.All -> requests(RequestsViewModel.Tab.All, everyone = true, canManage = summary?.canManage == true, state, actions, today)
            }
        }
        Toast(state.message, actions.onMessageShown, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding))
    }
}

/** The board's switch; My requests counts the open ones, All the waiting ones. */
@Composable
private fun Tabs(state: RequestsViewModel.State, actions: RequestsActions, modifier: Modifier) {
    val summary = state.summary
    val tabs = buildList {
        add(SwitchItem(RequestsViewModel.Tab.Discover, t("Discover")))
        add(SwitchItem(RequestsViewModel.Tab.Mine, t("My requests"), summary?.open ?: state.open.size))
        if (summary?.canViewAll == true) add(SwitchItem(RequestsViewModel.Tab.All, t("All requests"), summary.pending ?: 0, pending = true))
    }
    RequestsSwitch(tabs, state.tab, actions.onTab, modifier)
}

private fun LazyListScope.discover(state: RequestsViewModel.State, actions: RequestsActions, today: LocalDate) {
    for (row in state.rows) {
        // A row Seerr has nothing for leaves no gap.
        if (row.items?.isEmpty() == true) continue
        item(key = "row:${row.kind.key}") {
            Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SText(t(row.kind.title), Modifier.weight(1f), size = 16.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
                    SText(t("See all"), Modifier.tap { actions.onSeeAll(row.kind) }, size = 13.sp, weight = 600, color = SeedyColors.Text2, maxLines = 1)
                }
                val items = row.items
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (items == null) {
                        items(4) { CardSkeleton() }
                    } else {
                        items(items, key = { "${it.mediaType}:${it.tmdbId}" }) { SeerrTitleCard(it, row.kind.upcoming, { actions.onTitle(it) }, today = today) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardSkeleton() {
    Column(Modifier.width(120.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(120.dp).height(180.dp).clip(RoundedCornerShape(11.dp)).background(SeedyColors.Surface))
        Box(Modifier.width(84.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(SeedyColors.Surface))
    }
}

private fun LazyListScope.requests(
    tab: RequestsViewModel.Tab,
    everyone: Boolean,
    canManage: Boolean,
    state: RequestsViewModel.State,
    actions: RequestsActions,
    today: LocalDate,
) {
    val page = state.page(tab)
    val list = page.items
    if (list == null) {
        items(4) { Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp).fillMaxWidth().heightIn(min = 98.dp).clip(RoundedCornerShape(14.dp)).background(SeedyColors.Panel)) }
        return
    }
    if (list.isEmpty()) {
        item(key = "empty") {
            if (everyone) {
                Message(t("No requests yet"), t("Requests from everyone on the server show up here."))
            } else {
                Message(t("You haven't requested anything yet"), t("Find a movie or show in Discover or Search and ask for it."), t("Discover")) {
                    actions.onTab(RequestsViewModel.Tab.Discover)
                }
            }
        }
        return
    }
    item(key = "gap") { Box(Modifier.height(6.dp)) }
    items(list, key = { "req:${it.requestId}" }) { r ->
        Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp)) {
            RequestRow(
                r, everyone, canManage, busy = r.requestId in state.busy, today = today,
                onOpen = { actions.onRequest(r) },
                onApprove = { actions.onApprove(r) },
                onDecline = { actions.onDecline(r) },
                onRetry = { actions.onRetry(r) },
            )
        }
    }
    if (page.more) {
        // Reaching the end of what's loaded asks for the next page.
        item(key = "more:${list.size}") {
            LaunchedEffect(list.size) { actions.onMore(tab) }
            Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp).fillMaxWidth().heightIn(min = 98.dp).clip(RoundedCornerShape(14.dp)).background(SeedyColors.Panel))
        }
    }
}
