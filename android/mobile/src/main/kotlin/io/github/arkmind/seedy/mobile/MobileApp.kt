package io.github.arkmind.seedy.mobile

import io.github.arkmind.seedy.core.remote.RemoteControl
import io.github.arkmind.seedy.mobile.remote.ControlScreenPicker
import io.github.arkmind.seedy.mobile.remote.RemoteRoute
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.arkmind.seedy.core.SeedyCore
import io.github.arkmind.seedy.core.browse.Browse
import io.github.arkmind.seedy.core.data.SavedServer
import io.github.arkmind.seedy.core.feature.AddServerViewModel
import io.github.arkmind.seedy.core.feature.HomeViewModel
import io.github.arkmind.seedy.core.feature.PasswordViewModel
import io.github.arkmind.seedy.core.feature.ProfilesViewModel
import io.github.arkmind.seedy.core.format.Images
import io.github.arkmind.seedy.core.format.uuidOf
import io.github.arkmind.seedy.core.requests.TitlesKind
import io.github.arkmind.seedy.core.session.SessionState
import io.github.arkmind.seedy.core.session.UserSession
import io.github.arkmind.seedy.mobile.admin.AdminRoute
import io.github.arkmind.seedy.mobile.audio.AudioMiniBar
import io.github.arkmind.seedy.mobile.audio.AudioScope
import io.github.arkmind.seedy.mobile.audio.LocalAudio
import io.github.arkmind.seedy.mobile.audio.miniBarSpace
import io.github.arkmind.seedy.mobile.books.AudiobookPlayerRoute
import io.github.arkmind.seedy.mobile.books.BookRoute
import io.github.arkmind.seedy.mobile.books.BooksRoute
import io.github.arkmind.seedy.mobile.books.reader.ReaderRoute
import io.github.arkmind.seedy.mobile.details.DetailsRoute
import io.github.arkmind.seedy.mobile.favorites.FavoritesTab
import io.github.arkmind.seedy.mobile.history.HistoryRoute
import io.github.arkmind.seedy.mobile.home.AccountActions
import io.github.arkmind.seedy.mobile.home.HomeScreen
import io.github.arkmind.seedy.mobile.library.LibrariesTab
import io.github.arkmind.seedy.mobile.library.LibraryRoute
import io.github.arkmind.seedy.mobile.livetv.LiveTvRoute
import io.github.arkmind.seedy.mobile.music.AlbumRoute
import io.github.arkmind.seedy.mobile.music.ArtistRoute
import io.github.arkmind.seedy.mobile.music.MusicRoute
import io.github.arkmind.seedy.mobile.music.NowPlayingRoute
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.nav.MobileNav
import io.github.arkmind.seedy.mobile.nav.Route
import io.github.arkmind.seedy.mobile.notify.RequestLink
import io.github.arkmind.seedy.mobile.notify.RequestLinks
import io.github.arkmind.seedy.mobile.parts.Tab
import io.github.arkmind.seedy.mobile.parts.TabBar
import io.github.arkmind.seedy.mobile.parts.barHeight
import io.github.arkmind.seedy.mobile.parts.tabBarHeight
import io.github.arkmind.seedy.mobile.photos.PhotoFolderRoute
import io.github.arkmind.seedy.mobile.photos.PhotoViewerRoute
import io.github.arkmind.seedy.mobile.photos.PhotosRoute
import io.github.arkmind.seedy.mobile.player.MobileDataSheet
import io.github.arkmind.seedy.mobile.player.PlayerRoute
import io.github.arkmind.seedy.mobile.requests.MediaRowRoute
import io.github.arkmind.seedy.mobile.requests.MediaSearchRoute
import io.github.arkmind.seedy.mobile.requests.RequestsTab
import io.github.arkmind.seedy.mobile.requests.SeerrTitleRoute
import io.github.arkmind.seedy.mobile.requests.SeerrTitlesRoute
import io.github.arkmind.seedy.mobile.requests.openSeerr
import io.github.arkmind.seedy.mobile.search.SearchRoute
import io.github.arkmind.seedy.mobile.settings.SettingsPageRoute
import io.github.arkmind.seedy.mobile.settings.SettingsRoute
import io.github.arkmind.seedy.mobile.signin.AddServerScreen
import io.github.arkmind.seedy.mobile.signin.ForgotSheet
import io.github.arkmind.seedy.mobile.signin.PasswordScreen
import io.github.arkmind.seedy.mobile.signin.ProfilesScreen
import io.github.arkmind.seedy.mobile.signin.QuickConnectSheet
import io.github.arkmind.seedy.mobile.signin.SignInFor
import io.github.arkmind.seedy.mobile.signin.UnreachableScreen
import io.github.arkmind.seedy.mobile.update.UpdatedNotice
import io.github.arkmind.seedy.mobile.update.UpdatePrompt
import io.github.arkmind.seedy.player.Network
import io.github.arkmind.seedy.player.audio.AudioEngine
import io.github.arkmind.seedy.ui.components.LocalOverlayHost
import io.github.arkmind.seedy.ui.components.OverlayHost
import io.github.arkmind.seedy.ui.theme.LocalCore
import io.github.arkmind.seedy.ui.theme.LocalSession
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.SessionTheme
import io.github.arkmind.seedy.ui.theme.rememberRetryClock
import io.github.arkmind.seedy.ui.theme.rememberViewer
import io.github.arkmind.seedy.ui.update.AppUpdates
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** The phone app: follows the session from "Add your server" through sign-in to the signed-in tabs. */
@Composable
fun MobileApp(core: SeedyCore, appVersion: String = "") {
    val state by core.sessions.state.collectAsStateWithLifecycle()
    // The server finder's "Sign in with Quick Connect": the server's sign-in opens on Quick Connect's code.
    var quickConnectNext by rememberSaveable { mutableStateOf(false) }
    val finderQuickConnect = { quickConnectNext = true }
    val context = LocalContext.current
    LaunchedEffect(Unit) { UpdatedNotice.clear(context) }
    // Each time Seedy comes to the front: Android may keep it running in the background for days.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { AppUpdates.start(context) }
    SessionTheme(state) {
        when (val s = state) {
            SessionState.Starting -> Unit
            SessionState.NoServer -> AddServerEntry(core, onBack = null, onQuickConnect = finderQuickConnect)
            is SessionState.SignedOut -> SignInFlow(core, s.server, quickConnectNext, finderQuickConnect) { quickConnectNext = false }
            is SessionState.Unreachable -> Unreachable(core, s.server, finderQuickConnect)
            is SessionState.SignedIn -> SignedIn(core, s.session, appVersion)
        }
    }
}

@Composable
private fun AddServerEntry(core: SeedyCore, onBack: (() -> Unit)?, onQuickConnect: () -> Unit) {
    val vm = viewModel { AddServerViewModel(core) }
    val state by vm.state.collectAsStateWithLifecycle()
    AddServerScreen(state, onAdd = vm::add, onSearch = vm::search, onBack = onBack, onQuickConnect = { found -> onQuickConnect(); vm.add(found.address) })
}

private data object ProfilesKey
private data class PasswordKey(val profile: ProfilesViewModel.Profile?)
private data object AddServerKey

@Composable
private fun SignInFlow(core: SeedyCore, server: SavedServer, quickConnectFirst: Boolean, onFinderQuickConnect: () -> Unit, onQuickConnectOpened: () -> Unit) {
    val stack = remember(server.id) { mutableStateListOf<Any>(ProfilesKey) }
    val brand = server.seedy?.name ?: "Seedy"
    NavDisplay(
        backStack = stack,
        onBack = { stack.removeLastOrNull() },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        entryProvider = entryProvider {
            entry<ProfilesKey> {
                val vm = viewModel { ProfilesViewModel(core, server) }
                val state by vm.state.collectAsStateWithLifecycle()
                var quickConnect by remember { mutableStateOf(quickConnectFirst) }
                LaunchedEffect(Unit) { if (quickConnectFirst) onQuickConnectOpened() }
                ProfilesScreen(
                    brand = brand,
                    server = server,
                    state = state,
                    onProfile = { p -> vm.open(p) { stack.add(PasswordKey(p)) } },
                    onAddAccount = { stack.add(PasswordKey(null)) },
                    onQuickConnect = { quickConnect = true },
                    onServer = vm::useServer,
                    onAddServer = { stack.add(AddServerKey) },
                )
                if (quickConnect) QuickConnect(core, server) { quickConnect = false }
            }
            entry<PasswordKey> { key ->
                val p = key.profile
                val vm = viewModel { PasswordViewModel(core, server, p?.name) }
                val state by vm.state.collectAsStateWithLifecycle()
                var quickConnect by remember { mutableStateOf(false) }
                PasswordScreen(
                    server = server,
                    profile = p?.let { SignInFor(it.name, it.imageUrl, it.colour) },
                    state = state,
                    onBack = { stack.removeLastOrNull() },
                    onUserName = vm::setUserName,
                    onPassword = vm::setPassword,
                    onRemember = vm::setRemember,
                    onSignIn = vm::signIn,
                    onForgot = vm::forgot,
                    onQuickConnect = { quickConnect = true },
                )
                state.forgot?.let { f -> ForgotSheet(f, vm::setPin, vm::redeemPin, vm::closeForgot) }
                if (quickConnect) QuickConnect(core, server) { quickConnect = false }
            }
            entry<AddServerKey> { AddServerEntry(core, onBack = { stack.removeLastOrNull() }, onQuickConnect = onFinderQuickConnect) }
        },
    )
}

/** Quick Connect's code sheet, with its own short-lived sign-in state (the code stops being polled when it closes). */
@Composable
private fun QuickConnect(core: SeedyCore, server: SavedServer, onClose: () -> Unit) {
    val vm = viewModel(key = "qc-${server.id}") { PasswordViewModel(core, server, null) }
    val state by vm.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        vm.startQuickConnect()
        onDispose { vm.stopQuickConnect() }
    }
    QuickConnectSheet(state.code, state.quickConnectError, onRetry = vm::startQuickConnect, onDismiss = onClose)
}

/** Board "Android · Server unreachable": tries again by itself every 15 s; "Choose another server" opens the finder. */
@Composable
private fun Unreachable(core: SeedyCore, server: SavedServer, onFinderQuickConnect: () -> Unit) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    if (choosing) {
        BackHandler { choosing = false }
        AddServerEntry(core, onBack = { choosing = false }, onQuickConnect = onFinderQuickConnect)
        return
    }
    // The server answers again: the session waits to open while the page says so and Medu waves.
    var answered by remember { mutableStateOf(false) }
    val welcomed = remember { CompletableDeferred<Unit>() }
    val retry = rememberRetryClock {
        core.sessions.start(answered = {
            answered = true
            welcomed.await()
        })
    }
    UnreachableScreen(
        server, retryIn = if (retry.running) null else retry.left, onRetry = retry::now, onChooseServer = { choosing = true },
        answered = answered, onWelcomed = { welcomed.complete(Unit) },
    )
}

private data object TabsKey

@Composable
private fun SignedIn(core: SeedyCore, session: UserSession, appVersion: String) {
    val scope = rememberCoroutineScope()
    val images = remember(session) { Images(session.api) }
    // The phone's Requests tab, as the web's: Seerr, or music or book requests (TV's Requests stays Seerr's).
    val me by session.seedyFlow.collectAsState()
    val viewer = rememberViewer(session, images).let { v -> if (me?.media?.let { it.music || it.books } == true) v.copy(requests = true) else v }
    val stack = remember(session) { mutableStateListOf<Any>(TabsKey) }
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val nav = remember(stack) { MobileNav(stack) { tab = it } }
    val context = LocalContext.current
    val audio = remember { AudioEngine.get(context, core) }
    nav.audio = audio
    val tabs = if (viewer.requests) Tab.entries else Tab.entries - Tab.Requests
    // Settings · Playback › Ask before using mobile data: a video asks for its quality first (board "Android · Mobile data prompt").
    var asking by remember { mutableStateOf<Route.Player?>(null) }
    // "Browse here, watch there": while the phone is a remote, a video starts on the screen it controls.
    val remote = remember(session) { RemoteControl.of(session) }
    val remoteState by remote.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    nav.beforePlay = { r ->
        val there = remote.state.value.takeIf { it.controlling }?.activeScreen?.let { remote.play(listOf(r.id), r.startTicks) }
        if (there != null) {
            nav.showRemote()
            true
        } else {
            val ask = r.maxBitrate == null && core.device.current.askMobileData && Network.onMobileData(context)
            if (ask) asking = r
            ask
        }
    }
    val account = AccountActions(
        onSwitchProfile = { scope.launch { core.sessions.switchProfile() } },
        onSignOut = { scope.launch { core.sessions.signOut() } },
        onSettings = { nav.go(Route.Settings) },
    )
    // A request notification was tapped (board "Android · Notification shade"): its title, its episode, or Requests.
    val requestsTab by rememberUpdatedState(viewer.requests)
    LaunchedEffect(session) {
        RequestLinks.pending.filterNotNull().collect { l ->
            RequestLinks.clear()
            if (l.server != session.server.id || l.user != session.account.userId) return@collect
            val item = uuidOf(l.itemId)?.let { id -> runCatching { Browse(session).item(id) }.getOrNull() }
            when (l.action) {
                RequestLink.Requests -> if (requestsTab) nav.toTab(Tab.Requests)
                RequestLink.Play -> item?.let(nav::play)
                else -> when {
                    item != null -> nav.open(item)
                    l.tmdbId != null -> nav.go(Route.SeerrTitle(l.mediaType ?: "movie", l.tmdbId))
                }
            }
        }
    }
    CompositionLocalProvider(LocalViewer provides viewer, LocalSession provides session, LocalCore provides core, LocalMobileNav provides nav, LocalAudio provides audio) {
        AudioScope {
            Box(Modifier.fillMaxSize()) {
                NavDisplay(
                    backStack = stack,
                    onBack = { nav.back() },
                    entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
                    entryProvider = entryProvider {
                        entry<TabsKey> {
                            val overlay = remember { OverlayHost() }
                            Box(Modifier.fillMaxSize()) {
                                CompositionLocalProvider(LocalOverlayHost provides overlay) {
                                    when (tab) {
                                        Tab.Home -> {
                                            val vm = viewModel { HomeViewModel(session) }
                                            val state by vm.state.collectAsStateWithLifecycle()
                                            HomeScreen(
                                                state, account,
                                                onOpen = nav::open, onPlay = nav::play, onSearch = { nav.go(Route.Search) },
                                                onSeeAll = { row ->
                                                    when (row.row.kind) {
                                                        "discover" -> tab = Tab.Requests
                                                        "upcomingMovies" -> nav.go(Route.SeerrTitles(TitlesKind.UpcomingMovies.key))
                                                        else -> uuidOf(row.row.libraryId)?.let { nav.go(Route.Library(it)) }
                                                    }
                                                },
                                                onSeerr = { nav.openSeerr(it.mediaType, it.tmdbId, it.jellyfinItemId) },
                                                bottomPadding = tabBarHeight(),
                                                onFinish = vm::finishWatching,
                                                onRefresh = vm::refresh,
                                                onCast = { if (remoteState.controlling) nav.showRemote() else picking = true },
                                                controlling = remoteState.controlling,
                                            )
                                            if (picking) ControlScreenPicker(onControl = { picking = false; nav.showRemote() }, onDismiss = { picking = false })
                                        }
                                        Tab.Libraries -> LibrariesTab()
                                        Tab.Favorites -> FavoritesTab()
                                        Tab.Requests -> RequestsTab()
                                    }
                                }
                                AudioMiniBar(Modifier.align(Alignment.BottomCenter).padding(bottom = barHeight() + 8.dp))
                                TabBar(tabs, tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
                                overlay.content?.invoke()
                            }
                        }
                        // The boards keep the tab bar under these pages, lit on the tab they were opened from.
                        entry<Route.Details> { WithTabBar(tabs, tab, nav, reserve = true) { DetailsRoute(it.id) } }
                        entry<Route.Library> { WithTabBar(tabs, tab, nav, reserve = true) { LibraryRoute(it.id) } }
                        entry<Route.Music> { WithTabBar(tabs, tab, nav) { MusicRoute(it.id) } }
                        entry<Route.Album> { WithTabBar(tabs, tab, nav) { AlbumRoute(it.id) } }
                        entry<Route.Artist> { WithTabBar(tabs, tab, nav) { ArtistRoute(it.id) } }
                        entry<Route.NowPlaying> { WithOverlay { NowPlayingRoute() } }
                        entry<Route.Books> { WithTabBar(tabs, tab, nav) { BooksRoute(it.id, it.view, it.focus) } }
                        entry<Route.Book> { WithTabBar(tabs, tab, nav) { BookRoute(it.id) } }
                        entry<Route.Photos> { WithTabBar(tabs, tab, nav) { PhotosRoute(it.id) } }
                        entry<Route.PhotoFolder> { WithTabBar(tabs, tab, nav) { PhotoFolderRoute(it.id, it.photo) } }
                        entry<Route.PhotoViewer> { WithOverlay { PhotoViewerRoute(it.id, it.play) } }
                        entry<Route.Reader> { WithOverlay { ReaderRoute(it.id, it.percent) } }
                        entry<Route.AudiobookPlayer> { WithOverlay { AudiobookPlayerRoute() } }
                        entry<Route.Search> { SearchRoute() }
                        entry<Route.LiveTv> { LiveTvRoute() }
                        entry<Route.Settings> { WithTabBar(tabs, tab, nav, reserve = true) { SettingsRoute(appVersion) } }
                        entry<Route.SettingsPage> { SettingsPageRoute(it.page, appVersion) }
                        entry<Route.History> { WithTabBar(tabs, tab, nav, reserve = true) { HistoryRoute() } }
                        entry<Route.Player> { PlayerRoute(it.id, it.startTicks, it.maxBitrate) }
                        entry<Route.SeerrTitle> { WithTabBar(tabs, tab, nav, reserve = true) { SeerrTitleRoute(it.mediaType, it.tmdbId) } }
                        entry<Route.SeerrTitles> { WithTabBar(tabs, tab, nav, reserve = true) { SeerrTitlesRoute(it.kind) } }
                        entry<Route.MediaRequestsSearch> { WithTabBar(tabs, tab, nav, reserve = true) { MediaSearchRoute(it.music) } }
                        entry<Route.MediaRequestsRow> { WithTabBar(tabs, tab, nav, reserve = true) { MediaRowRoute(it.music, it.row) } }
                        entry<Route.Admin> { WithOverlay { AdminRoute(it.page, it.id) } }
                        entry<Route.Remote> { RemoteRoute() }
                    },
                )
                asking?.let { r ->
                    val device by core.device.flow.collectAsStateWithLifecycle()
                    MobileDataSheet(
                        r.id, device,
                        onPlay = { choice, never ->
                            if (never) core.device.update { it.copy(askMobileData = false, qualityMobile = choice.bitrate) }
                            asking = null
                            nav.go(r.copy(maxBitrate = choice.bitrate))
                        },
                        onWait = { asking = null },
                    )
                }
                // Board "Android · Update ready": a newer Seedy, offered from the top of the tabs.
                UpdatePrompt(visible = stack.size == 1)
            }
        }
    }
}

/** A page without the tab bar whose sheets still go over everything. */
@Composable
private fun WithOverlay(content: @Composable () -> Unit) {
    val overlay = remember { OverlayHost() }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalOverlayHost provides overlay, content = content)
        overlay.content?.invoke()
    }
}

/**
 * A page with the tab bar under it; a tab goes back to the tabs on that tab. The page's sheets cover the bar. The mini
 * player floats over the page's bottom (the page pads its list by [miniBarSpace]), or takes its own strip when
 * [reserve] (pages that don't know about it).
 */
@Composable
private fun WithTabBar(tabs: List<Tab>, current: Tab, nav: MobileNav, reserve: Boolean = false, content: @Composable () -> Unit) {
    val overlay = remember { OverlayHost() }
    val strip = if (reserve) miniBarSpace() else 0.dp
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
            Box(Modifier.weight(1f)) {
                Box(Modifier.fillMaxSize().padding(bottom = strip)) { CompositionLocalProvider(LocalOverlayHost provides overlay, content = content) }
                AudioMiniBar(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
            }
            TabBar(tabs, current, nav::toTab)
        }
        overlay.content?.invoke()
    }
}
