package io.github.arkmind.seedy.mobile.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.feature.SettingsViewModel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.ui.components.NotYet
import io.github.arkmind.seedy.ui.theme.LocalCore
import io.github.arkmind.seedy.ui.theme.currentSession

/**
 * One settings page (boards "Android · Settings · …"): [page] is "profile", "home", "playback", "advanced",
 * "subtitles", "display", "gestures" or "about".
 */
@Composable
fun SettingsPageRoute(page: String, appVersion: String) {
    val session = currentSession()
    val core = checkNotNull(LocalCore.current)
    val nav = LocalMobileNav.current
    val context = LocalContext.current
    val vm = viewModel { SettingsViewModel(core, session) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(page) { vm.load(if (page == "home") "homescreen" else page) }
    val to = settingsNav(state, nav, context)
    when (page) {
        "profile" -> ProfilePage(state, vm, to)
        "home" -> HomeScreenPage(state, vm, to)
        "playback" -> PlaybackPage(state, vm, to)
        "advanced" -> AdvancedPage(state, vm, to)
        "subtitles" -> SubtitlesPage(state, vm, to)
        "display" -> DisplayPage(state, vm, to)
        "gestures" -> GesturesPage(state, vm, to)
        "about" -> AboutPage(state, appVersion, to)
        else -> NotYet(t("Settings"))
    }
}
