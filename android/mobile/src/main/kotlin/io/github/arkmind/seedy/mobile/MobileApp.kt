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
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

/** The phone app: follows the session from "Add your server" through sign-in to the signed-in tabs. */
@Composable
fun MobileApp(core: SeedyCore) {
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
                is SessionState.SignedIn -> SignedIn(core, s.session)
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
private data class DetailsKey(val id: UUID)

@Composable
private fun SignedIn(core: SeedyCore, session: UserSession) {
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
    val account = AccountActions(
        onSwitchProfile = { scope.launch { core.sessions.switchProfile() } },
        onSignOut = { scope.launch { core.sessions.signOut() } },
    )
    val stack = remember(session) { mutableStateListOf<Any>(TabsKey) }
    val open: (BaseItemDto) -> Unit = { stack.add(DetailsKey(it.seriesId ?: it.id)) }
    CompositionLocalProvider(LocalViewer provides viewer) {
        NavDisplay(
            backStack = stack,
            onBack = { stack.removeLastOrNull() },
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
            entryProvider = entryProvider {
                entry<TabsKey> {
                    val tabs = if (viewer.requests) Tab.entries else Tab.entries - Tab.Requests
                    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
                    Box(Modifier.fillMaxSize()) {
                        when (tab) {
                            Tab.Home -> {
                                val vm = viewModel { HomeViewModel(session) }
                                val state by vm.state.collectAsStateWithLifecycle()
                                HomeScreen(state, account, onOpen = open, onPlay = open, onSearch = {}, onSeeAll = {}, bottomPadding = tabBarHeight())
                            }
                            else -> NotYet(t(tab.label))
                        }
                        TabBar(tabs, tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
                    }
                }
                entry<DetailsKey> { NotYet(t("Details")) }
            },
        )
    }
}

/** A place the phone app doesn't have yet. */
@Composable
private fun NotYet(title: String) {
    Box(Modifier.fillMaxSize().background(SeedyColors.Bg), contentAlignment = Alignment.Center) {
        SText(title, size = 22.sp, weight = 900, color = SeedyColors.White)
    }
}
