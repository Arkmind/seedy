package io.github.arkmind.seedy.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import io.github.arkmind.seedy.mobile.details.DetailsRoute
import io.github.arkmind.seedy.mobile.favorites.FavoritesTab
import io.github.arkmind.seedy.mobile.library.LibrariesTab
import io.github.arkmind.seedy.mobile.library.LibraryRoute
import io.github.arkmind.seedy.mobile.livetv.LiveTvRoute
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.nav.MobileNav
import io.github.arkmind.seedy.mobile.nav.Route
import io.github.arkmind.seedy.mobile.player.PlayerRoute
import io.github.arkmind.seedy.mobile.requests.RequestsTab
import io.github.arkmind.seedy.mobile.requests.SeerrTitleRoute
import io.github.arkmind.seedy.mobile.search.SearchRoute
import io.github.arkmind.seedy.mobile.settings.SettingsPageRoute
import io.github.arkmind.seedy.mobile.settings.SettingsRoute
import io.github.arkmind.seedy.ui.theme.LocalCore
import io.github.arkmind.seedy.ui.theme.LocalSession
import io.github.arkmind.seedy.mobile.home.AccountActions
import io.github.arkmind.seedy.mobile.home.HomeScreen
import io.github.arkmind.seedy.mobile.parts.PrimaryButton
import io.github.arkmind.seedy.mobile.parts.Tab
import io.github.arkmind.seedy.mobile.parts.TabBar
import io.github.arkmind.seedy.mobile.parts.pagePadding
import io.github.arkmind.seedy.mobile.parts.tabBarHeight
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.signin.AddServerScreen
import io.github.arkmind.seedy.mobile.signin.PasswordScreen
import io.github.arkmind.seedy.mobile.signin.ProfilesScreen
import io.github.arkmind.seedy.mobile.signin.QuickConnectSheet
import io.github.arkmind.seedy.mobile.signin.SignInFor
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.SeedyTheme
import io.github.arkmind.seedy.ui.theme.Viewer
import io.github.arkmind.seedy.ui.theme.parseHexColor
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/** The phone app: follows the session from "Add your server" through sign-in to the signed-in tabs. */
@Composable
fun MobileApp(core: SeedyCore, appVersion: String = "") {
    val state by core.sessions.state.collectAsStateWithLifecycle()
    val accent = when (val s = state) {
        is SessionState.SignedIn -> parseHexColor(s.session.seedy?.branding?.accent ?: s.session.server.seedy?.accent)
        is SessionState.SignedOut -> parseHexColor(s.server.seedy?.accent)
        else -> null
    }
    SeedyTheme(accent = accent ?: SeedyColors.Accent) {
        Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
            when (val s = state) {
                SessionState.Starting -> Unit
                SessionState.NoServer -> AddServerEntry(core, onBack = null)
                is SessionState.SignedOut -> SignInFlow(core, s.server)
                is SessionState.Unreachable -> Unreachable(core, s.server)
                is SessionState.SignedIn -> SignedIn(core, s.session, appVersion)
            }
        }
    }
}

@Composable
private fun AddServerEntry(core: SeedyCore, onBack: (() -> Unit)?) {
    val vm = viewModel { AddServerViewModel(core) }
    val state by vm.state.collectAsStateWithLifecycle()
    AddServerScreen(state, onAdd = vm::add, onSearch = vm::search, onBack = onBack)
}

private data object ProfilesKey
private data class PasswordKey(val profile: ProfilesViewModel.Profile?)
private data object AddServerKey

@Composable
private fun SignInFlow(core: SeedyCore, server: SavedServer) {
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
                var quickConnect by remember { mutableStateOf(false) }
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
                    onForgot = {},
                    onQuickConnect = { quickConnect = true },
                )
                if (quickConnect) QuickConnect(core, server) { quickConnect = false }
            }
            entry<AddServerKey> { AddServerEntry(core, onBack = { stack.removeLastOrNull() }) }
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

@Composable
private fun Unreachable(core: SeedyCore, server: SavedServer) {
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().padding(pagePadding()),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SText(t("Can’t reach {server}", "server" to server.name), size = 22.sp, weight = 900, color = SeedyColors.White, align = TextAlign.Center)
        SText(t("Check that this phone is online and the server is running."), size = 14.sp, color = SeedyColors.Text2, align = TextAlign.Center)
        PrimaryButton(t("Try again"), { scope.launch { core.sessions.start() } })
        SText(t("Switch profile"), Modifier.tap { scope.launch { core.sessions.switchProfile() } }.padding(10.dp), size = 13.sp, weight = 600, color = SeedyColors.Text2)
    }
}

private data object TabsKey

@Composable
private fun SignedIn(core: SeedyCore, session: UserSession, appVersion: String) {
    val scope = rememberCoroutineScope()
    val images = remember(session) { Images(session.api) }
    val viewer = remember(session) {
        Viewer(
            name = session.user.name.orEmpty(),
            imageUrl = images.user(session.userId, session.user.primaryImageTag),
            brand = session.seedy?.branding?.name ?: "Seedy",
            episodeImages = session.seedy?.prefs?.custom?.get("home.episodeImages")?.jsonPrimitive?.booleanOrNull ?: true,
            requests = session.hasRequests,
            images = images,
        )
    }
    val stack = remember(session) { mutableStateListOf<Any>(TabsKey) }
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val nav = remember(stack) { MobileNav(stack) { tab = it } }
    val tabs = if (viewer.requests) Tab.entries else Tab.entries - Tab.Requests
    val account = AccountActions(
        onSwitchProfile = { scope.launch { core.sessions.switchProfile() } },
        onSignOut = { scope.launch { core.sessions.signOut() } },
        onSettings = { nav.go(Route.Settings) },
    )
    CompositionLocalProvider(LocalViewer provides viewer, LocalSession provides session, LocalCore provides core, LocalMobileNav provides nav) {
        NavDisplay(
            backStack = stack,
            onBack = { nav.back() },
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
            entryProvider = entryProvider {
                entry<TabsKey> {
                    Box(Modifier.fillMaxSize()) {
                        when (tab) {
                            Tab.Home -> {
                                val vm = viewModel { HomeViewModel(session) }
                                val state by vm.state.collectAsStateWithLifecycle()
                                HomeScreen(
                                    state, account,
                                    onOpen = nav::open, onPlay = nav::play, onSearch = { nav.go(Route.Search) },
                                    onSeeAll = { row -> row.row.libraryId?.let { nav.go(Route.Library(UUID.fromString(it))) } },
                                    bottomPadding = tabBarHeight(),
                                )
                            }
                            Tab.Libraries -> LibrariesTab()
                            Tab.Favorites -> FavoritesTab()
                            Tab.Requests -> RequestsTab()
                        }
                        TabBar(tabs, tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
                    }
                }
                // The boards keep the tab bar under these pages, lit on the tab they were opened from.
                entry<Route.Details> { WithTabBar(tabs, tab, nav) { DetailsRoute(it.id) } }
                entry<Route.Library> { WithTabBar(tabs, tab, nav) { LibraryRoute(it.id) } }
                entry<Route.Search> { SearchRoute() }
                entry<Route.LiveTv> { LiveTvRoute() }
                entry<Route.Settings> { WithTabBar(tabs, tab, nav) { SettingsRoute(appVersion) } }
                entry<Route.SettingsPage> { SettingsPageRoute(it.page, appVersion) }
                entry<Route.Player> { PlayerRoute(it.id) }
                entry<Route.SeerrTitle> { SeerrTitleRoute(it.mediaType, it.tmdbId) }
            },
        )
    }
}

/** A page with the tab bar under it; a tab goes back to the tabs on that tab. */
@Composable
private fun WithTabBar(tabs: List<Tab>, current: Tab, nav: MobileNav, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
        Box(Modifier.weight(1f)) { content() }
        TabBar(tabs, current, nav::toTab)
    }
}
