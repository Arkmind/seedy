package io.github.arkmind.seedy.mobile.admin.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.admin.media.LibraryDefaultsActions
import io.github.arkmind.seedy.core.admin.media.LibraryDefaultsState
import io.github.arkmind.seedy.core.admin.media.LibraryDefaultsViewModel
import io.github.arkmind.seedy.core.admin.media.LibraryPages
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.settings.SaveLine
import io.github.arkmind.seedy.mobile.settings.SettingsPage
import io.github.arkmind.seedy.ui.theme.currentSession

/*
 * The media area's server pages: Libraries (and each library, and the settings every library shares), Playback and
 * Settings. AdminRoute opens them by page key.
 */

/** One library, or with [id] "display", "metadata" or "nfo" the settings every library shares. */
@Composable
fun AdminLibraryRoute(id: String) {
    if (id in LibraryPages.all) LibraryDefaultsRoute(id) else LibraryRoute(id)
}

@Composable
private fun LibraryDefaultsRoute(page: String) {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val vm = viewModel(key = "library-defaults-$page") { LibraryDefaultsViewModel(session, page) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(page) { vm.load() }
    LibraryDefaultsScreen(state, vm, onBack = nav::back)
}

/** Libraries › Display, Metadata or NFO files: the settings every library shares. */
@Composable
fun LibraryDefaultsScreen(state: LibraryDefaultsState, actions: LibraryDefaultsActions, onBack: () -> Unit) {
    val sheets = rememberMediaSheets()
    SettingsPage(state.title, state.lede, onBack = onBack, overlay = { MediaSheetHost(sheets, actions::setNumber) }) {
        val groups = state.groups
        val error = state.loadError
        when {
            groups == null && error != null -> PageLoadError(error, actions::retry)
            groups == null -> PageLoading()
            else -> SettingCards(groups, actions, sheets)
        }
        SaveLine(state.saving, state.error)
    }
}
