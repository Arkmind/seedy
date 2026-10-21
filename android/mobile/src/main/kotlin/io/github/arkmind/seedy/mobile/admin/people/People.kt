package io.github.arkmind.seedy.mobile.admin.people

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.admin.people.ApiKeysViewModel
import io.github.arkmind.seedy.core.admin.people.DevicesViewModel
import io.github.arkmind.seedy.core.admin.people.UserEditViewModel
import io.github.arkmind.seedy.core.admin.people.UsersViewModel
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.nav.Route
import io.github.arkmind.seedy.ui.theme.currentSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* The server's people pages: users, a user, devices and API keys. */

@Composable
fun UsersRoute() {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val vm = viewModel { UsersViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    // Each visit reloads: coming back from a user's page shows what changed there.
    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(state.opened) {
        state.opened?.let {
            vm.openedShown()
            nav.go(Route.Admin("user", it))
        }
    }
    UsersScreen(state, vm, onBack = nav::back, open = { nav.go(Route.Admin("user", it)) })
}

@Composable
fun UserRoute(id: String) {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm = viewModel(key = "admin-user-$id") { UserEditViewModel(session, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val picked = withContext(Dispatchers.IO) {
                    runCatching {
                        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { it to mime }
                    }.getOrNull()
                }
                picked?.let { (bytes, mime) -> vm.uploadPhoto(bytes, mime) }
            }
        }
    }
    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(state.deleted) { if (state.deleted) nav.back() }
    UserScreen(
        state, vm,
        onBack = nav::back,
        onPickPhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        open = { nav.go(Route.Admin(it)) },
    )
}

@Composable
fun DevicesRoute() {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val vm = viewModel { DevicesViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(Unit) { vm.live() }
    DevicesScreen(state, vm, onBack = nav::back, open = { nav.go(Route.Admin(it)) })
}

@Composable
fun ApiKeysRoute() {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val vm = viewModel { ApiKeysViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    ApiKeysScreen(state, vm, onBack = nav::back)
}
