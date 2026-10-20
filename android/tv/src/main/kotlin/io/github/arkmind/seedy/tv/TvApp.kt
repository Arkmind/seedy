package io.github.arkmind.seedy.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import io.github.arkmind.seedy.tv.home.TvHomeScreen
import io.github.arkmind.seedy.tv.parts.TvButton
import io.github.arkmind.seedy.tv.parts.bp
import io.github.arkmind.seedy.tv.parts.focusSoon
import io.github.arkmind.seedy.tv.parts.bsp
import io.github.arkmind.seedy.tv.shell.RailInset
import io.github.arkmind.seedy.tv.shell.TvPlace
import io.github.arkmind.seedy.tv.shell.TvRail
import io.github.arkmind.seedy.tv.signin.TvAddServerScreen
import io.github.arkmind.seedy.tv.signin.TvPasswordScreen
import io.github.arkmind.seedy.tv.signin.TvProfilesScreen
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

/** The Android TV app: the same journey as the phone (server, profile, home), drawn for the remote. */
@Composable
fun TvApp(core: SeedyCore) {
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
                SessionState.NoServer -> AddServerEntry(core)
                is SessionState.SignedOut -> SignInFlow(core, s.server)
                is SessionState.Unreachable -> Unreachable(core, s.server)
                is SessionState.SignedIn -> SignedIn(core, s.session)
            }
        }
    }
}

@Composable
private fun AddServerEntry(core: SeedyCore) {
    val vm = viewModel { AddServerViewModel(core) }
    val state by vm.state.collectAsStateWithLifecycle()
    TvAddServerScreen(state, onAdd = vm::add, onSearch = vm::search)
}

private data object ProfilesKey
/** [quickConnect]: open straight on Quick Connect's code (the profiles page's button). */
private data class PasswordKey(val profile: ProfilesViewModel.Profile?, val quickConnect: Boolean = false)
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
            entry<AddServerKey> { AddServerEntry(core) }
        },
    )
}

@Composable
private fun Unreachable(core: SeedyCore, server: SavedServer) {
    val scope = rememberCoroutineScope()
    val retry = remember { FocusRequester() }
    Column(
        Modifier.fillMaxSize().padding(96.bp),
        verticalArrangement = Arrangement.spacedBy(28.bp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SText(t("Can’t reach {server}", "server" to server.name), size = 48.bsp, weight = 900, color = SeedyColors.White)
        SText(t("Check that this TV is online and the server is running."), size = 22.bsp, color = SeedyColors.Text2)
        Row(horizontalArrangement = Arrangement.spacedBy(24.bp)) {
            TvButton(t("Try again"), { scope.launch { core.sessions.start() } }, Modifier.focusRequester(retry), primary = true)
            TvButton(t("Switch profile"), { scope.launch { core.sessions.switchProfile() } })
        }
    }
    LaunchedEffect(Unit) { retry.focusSoon() }
}

private data class PlaceKey(val place: TvPlace)
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
    val places = if (viewer.requests) TvPlace.entries else TvPlace.entries - TvPlace.Requests
    var place by rememberSaveable { mutableStateOf(TvPlace.Home) }
    val stack = remember(session) { mutableStateListOf<Any>(PlaceKey(TvPlace.Home)) }
    val open: (BaseItemDto) -> Unit = { stack.add(DetailsKey(it.seriesId ?: it.id)) }
    CompositionLocalProvider(LocalViewer provides viewer) {
        NavDisplay(
            backStack = stack,
            onBack = { stack.removeLastOrNull() },
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
            entryProvider = entryProvider {
                entry<PlaceKey> {
                    Box(Modifier.fillMaxSize()) {
                        when (place) {
                            TvPlace.Home -> {
                                val vm = viewModel { HomeViewModel(session) }
                                val state by vm.state.collectAsStateWithLifecycle()
                                TvHomeScreen(state, onOpen = open)
                            }
                            else -> NotYet(t(place.label))
                        }
                        TvRail(
                            places = places,
                            current = place,
                            onPlace = { place = it },
                            onProfile = { scope.launch { core.sessions.switchProfile() } },
                            onSettings = {},
                        )
                    }
                }
                entry<DetailsKey> { NotYet(t("Details")) }
            },
        )
    }
}

/** A place the TV app doesn't have yet. */
@Composable
private fun NotYet(title: String) {
    Box(Modifier.fillMaxSize().padding(start = RailInset).background(SeedyColors.Bg), contentAlignment = Alignment.Center) {
        SText(title, size = 48.bsp, weight = 900, color = SeedyColors.White)
    }
}
