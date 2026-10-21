package io.github.arkmind.seedy.mobile.admin.media

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.admin.media.LibraryDefaultsActions
import io.github.arkmind.seedy.core.admin.media.LibraryDefaultsState
import io.github.arkmind.seedy.core.admin.media.LibraryDefaultsViewModel
import io.github.arkmind.seedy.core.admin.media.LibraryPages
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.admin.PillRow
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.settings.SaveLine
import io.github.arkmind.seedy.mobile.settings.SettingsPage
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession

/*
 * The media area's server pages: Libraries (and each library, and the settings every library shares), Playback and
 * Settings. AdminRoute opens them by page key.
 */

/** Board "Android · Server · Libraries". */
@Composable
fun AdminLibrariesRoute() = LibrariesRoute()

/** One library, or with [id] "display", "metadata" or "nfo" the settings every library shares. */
@Composable
fun AdminLibraryRoute(id: String) {
    if (id in LibraryPages.all) LibraryDefaultsRoute(id) else LibraryRoute(id)
}

/** Board "Android · Server · Playback". */
@Composable
fun ServerPlaybackRoute() = PlaybackRoute()

/** Board "Android · Server · Settings". */
@Composable
fun ServerSettingsRoute() = ServerSettingsPageRoute()

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
            groups == null && error != null -> {
                SText(error, Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), size = 13.sp, color = SeedyColors.Danger)
                PillRow { PillButton(t("Try again"), actions::retry) }
            }
            groups == null -> SText("…", Modifier.padding(20.dp), size = 13.sp, color = SeedyColors.Text3)
            else -> SettingCards(groups, actions, sheets)
        }
        SaveLine(state.saving, state.error)
    }
}
