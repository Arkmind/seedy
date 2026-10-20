package io.github.arkmind.seedy.mobile.settings

import androidx.compose.runtime.Composable
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.ui.components.NotYet

/** One settings page (boards "Android · Settings · …"): [page] is "profile", "home", "playback", "subtitles"… */
@Composable
fun SettingsPageRoute(page: String, appVersion: String) {
    NotYet(t("Settings"))
}
