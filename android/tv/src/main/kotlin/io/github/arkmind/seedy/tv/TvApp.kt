package io.github.arkmind.seedy.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.arkmind.seedy.core.SeedyCore
import io.github.arkmind.seedy.core.data.SavedServer
import io.github.arkmind.seedy.core.feature.AddServerViewModel
import io.github.arkmind.seedy.core.feature.HomeViewModel
import io.github.arkmind.seedy.core.feature.PasswordViewModel
import io.github.arkmind.seedy.core.feature.ProfilesViewModel
import io.github.arkmind.seedy.core.format.Images
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.session.SessionState
import io.github.arkmind.seedy.core.session.UserSession
import io.github.arkmind.seedy.tv.details.TvDetailsRoute
import io.github.arkmind.seedy.tv.favorites.TvFavoritesPlace
import io.github.arkmind.seedy.tv.library.TvLibraryRoute
import io.github.arkmind.seedy.tv.livetv.TvLiveTvPlace
import io.github.arkmind.seedy.tv.nav.LocalTvNav
import io.github.arkmind.seedy.tv.nav.TvNav
import io.github.arkmind.seedy.tv.nav.TvRoute
import io.github.arkmind.seedy.tv.player.TvPlayerRoute
import io.github.arkmind.seedy.tv.requests.TvRequestsPlace
import io.github.arkmind.seedy.tv.requests.openSeerr
import io.github.arkmind.seedy.tv.requests.TvSeerrTitleRoute
import io.github.arkmind.seedy.tv.search.TvSearchPlace
import io.github.arkmind.seedy.tv.settings.TvSettingsRoute
import io.github.arkmind.seedy.tv.shell.tvPlaces
import io.github.arkmind.seedy.ui.theme.LocalCore
import io.github.arkmind.seedy.ui.theme.LocalSession
import io.github.arkmind.seedy.ui.theme.SessionTheme
import org.jellyfin.sdk.model.api.CollectionType
import io.github.arkmind.seedy.tv.home.TvHomeScreen
import io.github.arkmind.seedy.tv.parts.bsp
import io.github.arkmind.seedy.tv.shell.RailInset
import io.github.arkmind.seedy.tv.shell.TvPlace
import io.github.arkmind.seedy.tv.shell.TvRail
import io.github.arkmind.seedy.ui.components.LocalOverlayHost
import io.github.arkmind.seedy.ui.components.OverlayHost
import io.github.arkmind.seedy.tv.signin.TvAddServerScreen
import io.github.arkmind.seedy.tv.signin.TvPasswordScreen
import io.github.arkmind.seedy.tv.signin.TvProfilesScreen
import io.github.arkmind.seedy.tv.signin.TvUnreachableScreen
import io.github.arkmind.seedy.ui.theme.rememberRetryClock
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.rememberViewer
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import androidx.compose.ui.platform.LocalContext
import io.github.arkmind.seedy.tv.update.TvUpdatePrompt
import io.github.arkmind.seedy.ui.update.AppUpdates
import kotlinx.coroutines.launch

/** The Android TV app: the same journey as the phone (server, profile, home), drawn for the remote. */
@Composable
fun TvApp(core: SeedyCore, appVersion: String = "") {
    val state by core.sessions.state.collectAsStateWithLifecycle()
    // The server finder's "Use Quick Connect": the server's sign-in opens on Quick Connect's code.
    var quickConnectNext by rememberSaveable { mutableStateOf(false) }
    val finderQuickConnect = { quickConnectNext = true }
    val context = LocalContext.current
    LaunchedEffect(Unit) { AppUpdates.start(context) }
    SessionTheme(state) {
        when (val s = state) {
            SessionState.Starting -> Unit
            SessionState.NoServer -> AddServerEntry(core, finderQuickConnect)
            is SessionState.SignedOut -> SignInFlow(core, s.server, quickConnectNext, finderQuickConnect) { quickConnectNext = false }
            is SessionState.Unreachable -> Unreachable(core, s.server, finderQuickConnect)
            is SessionState.SignedIn -> SignedIn(core, s.session, appVersion)
        }
    }
}

@Composable
private fun AddServerEntry(core: SeedyCore, onQuickConnect: () -> Unit) {
    val vm = viewModel { AddServerViewModel(core) }
    val state by vm.state.collectAsStateWithLifecycle()
    TvAddServerScreen(state, onAdd = vm::add, onSearch = vm::search, onQuickConnect = { found -> onQuickConnect(); vm.add(found.address) })
}

private data object ProfilesKey
/** [quickConnect]: open straight on Quick Connect's code (the profiles page's button). */
private data class PasswordKey(val profile: ProfilesViewModel.Profile?, val quickConnect: Boolean = false)
private data object AddServerKey

@Composable
private fun SignInFlow(core: SeedyCore, server: SavedServer, quickConnectFirst: Boolean, onFinderQuickConnect: () -> Unit, onQuickConnectOpened: () -> Unit) {
    val stack = remember(server.id) {
        mutableStateListOf<Any>(ProfilesKey).apply { if (quickConnectFirst) add(PasswordKey(null, quickConnect = true)) }
    }
    LaunchedEffect(server.id) { if (quickConnectFirst) onQuickConnectOpened() }
    val brand = server.seedy?.name ?: "Seedy"
    NavDisplay(
        backStack = stack,
        onBack = { stack.removeLastOrNull() },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        entryProvider = entryProvider {
            entry<ProfilesKey> {
                val vm = viewModel { ProfilesViewModel(core, server) }
                val state by vm.state.collectAsStateWithLifecycle()
                TvProfilesScreen(
                    brand = brand,
                    state = state,
                    onProfile = { p -> vm.open(p) { stack.add(PasswordKey(p)) } },
                    onAddAccount = { stack.add(PasswordKey(null)) },
                    onQuickConnect = { stack.add(PasswordKey(null, quickConnect = true)) },
                    onChangeServer = { stack.add(AddServerKey) },
                )
            }
            entry<PasswordKey> { key ->
                val vm = viewModel { PasswordViewModel(core, server, key.profile?.name) }
                val state by vm.state.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { if (key.quickConnect) vm.startQuickConnect() }
                // Back first leaves Quick Connect's code, then the page.
                BackHandler(enabled = state.code != null) { vm.stopQuickConnect() }
                TvPasswordScreen(
                    brand = brand,
                    server = server,
                    profile = key.profile,
                    state = state,
                    onUserName = vm::setUserName,
                    onPassword = vm::setPassword,
                    onSignIn = vm::signIn,
                    onQuickConnect = vm::startQuickConnect,
                )
            }
            entry<AddServerKey> { AddServerEntry(core, onFinderQuickConnect) }
        },
    )
}

/** Board "Android TV · Server unreachable": tries again by itself every 15 s; "Choose another server" opens the finder. */
@Composable
private fun Unreachable(core: SeedyCore, server: SavedServer, onFinderQuickConnect: () -> Unit) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    if (choosing) {
        BackHandler { choosing = false }
        AddServerEntry(core, onFinderQuickConnect)
        return
    }
    val retry = rememberRetryClock { core.sessions.start() }
    TvUnreachableScreen(server, retryIn = if (retry.running) null else retry.left, onRetry = retry::now, onChooseServer = { choosing = true })
}

private data object PlacesKey

@Composable
private fun SignedIn(core: SeedyCore, session: UserSession, appVersion: String) {
    val scope = rememberCoroutineScope()
    val images = remember(session) { Images(session.api) }
    val viewer = rememberViewer(session, images)
    val places = tvPlaces(viewer.requests)
    var place by rememberSaveable { mutableStateOf(TvPlace.Home) }
    val stack = remember(session) { mutableStateListOf<Any>(PlacesKey) }
    val nav = remember(stack) { TvNav(stack) }
    val home = viewModel { HomeViewModel(session) }
    CompositionLocalProvider(LocalViewer provides viewer, LocalSession provides session, LocalCore provides core, LocalTvNav provides nav) {
        Box(Modifier.fillMaxSize()) {
            NavDisplay(
                backStack = stack,
                onBack = { nav.back() },
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
                entryProvider = entryProvider {
                    entry<PlacesKey> {
                        // Back on a place other than Home goes Home first.
                        BackHandler(enabled = place != TvPlace.Home) { place = TvPlace.Home }
                        // A place's dialogs are drawn over the rail too.
                        val overlay = remember { OverlayHost() }
                        Box(Modifier.fillMaxSize()) {
                            CompositionLocalProvider(LocalOverlayHost provides overlay) {
                                when (place) {
                                    TvPlace.Home -> {
                                        val state by home.state.collectAsStateWithLifecycle()
                                        TvHomeScreen(
                                            state, onOpen = nav::open, onPlay = nav::play, onSeerr = { nav.openSeerr(it.mediaType, it.tmdbId, it.jellyfinItemId) },
                                            onFinish = home::finishWatching,
                                        )
                                    }
                                    TvPlace.Search -> TvSearchPlace()
                                    TvPlace.Movies -> LibraryPlace(home, CollectionType.MOVIES)
                                    TvPlace.Shows -> LibraryPlace(home, CollectionType.TVSHOWS)
                                    TvPlace.LiveTv -> TvLiveTvPlace()
                                    TvPlace.Favorites -> TvFavoritesPlace()
                                    TvPlace.Requests -> TvRequestsPlace(onSearch = { place = TvPlace.Search })
                                }
                            }
                            TvRail(
                                places = places,
                                current = place,
                                onPlace = { place = it },
                                onProfile = { scope.launch { core.sessions.switchProfile() } },
                                onSettings = { nav.go(TvRoute.Settings) },
                            )
                            overlay.content?.invoke()
                        }
                    }
                    entry<TvRoute.Details> { TvDetailsRoute(it.id) }
                    entry<TvRoute.Library> { TvLibraryRoute(it.id) }
                    entry<TvRoute.Player> { TvPlayerRoute(it.id) }
                    entry<TvRoute.Settings> { TvSettingsRoute(appVersion, onPlace = { place = it; nav.back() }) }
                    entry<TvRoute.SeerrTitle> { TvSeerrTitleRoute(it.mediaType, it.tmdbId) }
                },
            )
            // Board "Android TV · Update ready": a newer Seedy, offered over the places (never over a page or the player).
            TvUpdatePrompt(visible = stack.size == 1, brand = session.server.seedy?.name ?: "Seedy")
        }
    }
}

/** The rail's Movies and Shows: the first library of that kind. */
@Composable
private fun LibraryPlace(home: HomeViewModel, type: CollectionType) {
    val state by home.state.collectAsStateWithLifecycle()
    val library = state.libraries.firstOrNull { it.collectionType == type }
    if (library != null) {
        key(library.id) { TvLibraryRoute(library.id) }
    } else {
        Box(Modifier.fillMaxSize().padding(start = RailInset).background(SeedyColors.Bg), contentAlignment = Alignment.Center) {
            SText(if (state.loading) "" else t("No library of this kind on this server"), size = 24.bsp, color = SeedyColors.Text2)
        }
    }
}
