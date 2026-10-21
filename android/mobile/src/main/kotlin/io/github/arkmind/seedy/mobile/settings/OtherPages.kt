package io.github.arkmind.seedy.mobile.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.feature.SettingsViewModel
import io.github.arkmind.seedy.core.i18n.I18n
import io.github.arkmind.seedy.core.i18n.Lang
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.settings.Choice
import io.github.arkmind.seedy.core.settings.SettingsActions
import io.github.arkmind.seedy.core.settings.SettingsState
import io.github.arkmind.seedy.core.settings.SettingsState.Companion.Keys
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.parseHexColor
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** The accent colours a profile can pick (null: the server's), as on the web's Settings · Display. */
internal val Accents = listOf(
    null to "Server colour", "#9fd3ff" to "Sky", "#b8f59f" to "Mint", "#ffc98a" to "Apricot", "#c9b8ff" to "Lilac", "#f0f0f0" to "White",
)

/** The languages Seedy speaks; Auto follows this device. */
internal fun languageChoices(): List<Choice<String>> {
    val device = if (I18n.deviceLang() == Lang.Fr) "Français" else "English"
    return listOf(Choice("auto", t("Auto · {lang}", "lang" to device)), Choice("en", "English"), Choice("fr", "Français"))
}

/** Settings · Display (no board: the settings boards' rows; the web's MeDisplay). */
@Composable
fun DisplayPage(state: SettingsState, actions: SettingsActions, nav: SettingsNav) {
    val sheet = rememberSheet()
    val serverAccent = LocalAccent.current
    SettingsPage(t("Display"), t("Language, colour and what shows while you browse."), nav.back, overlay = { SheetHost(sheet) }) {
        Section(t("Language and region")) {
            row {
                val options = languageChoices()
                val value = if (state.language.startsWith("fr")) "fr" else if (state.language.startsWith("en")) "en" else "auto"
                PickRow(t("Language"), t("Seedy’s menus and labels"), state.languageLabel(), onClick = {
                    sheet.open(t("Language"), options, value, actions::setLanguage)
                })
            }
        }
        if (state.seedy) {
            Section(t("Accent colour"), t("Counts, focus ring and selections. Starts as your server’s colour.")) {
                row {
                    val current = state.customString(Keys.Accent)?.lowercase()
                    val swatches = Accents.map { (hex, label) -> Swatch(hex ?: "", t(label), (hex?.let(::parseHexColor) ?: serverAccent).let { c -> argb(c) }) }
                    SwatchRow(t("Accent colour"), t(Accents.firstOrNull { it.first == current }?.second ?: "Server colour"), swatches, current ?: "") { v ->
                        actions.setCustom(Keys.Accent, if (v.isEmpty()) JsonNull else JsonPrimitive(v))
                    }
                }
            }
        }
        Section(t("While browsing")) {
            if (state.seedy) {
                row {
                    SwitchRow(t("Backdrops"), t("Artwork behind details pages and the home banner"), state.backdrops) { v ->
                        actions.setCustom(Keys.Backdrops, if (v) JsonNull else JsonPrimitive(false))
                    }
                }
            }
            row {
                SwitchRow(t("Show missing episodes"), t("List episodes you don’t have, greyed out, in each season"), state.config?.displayMissingEpisodes == true) { v ->
                    actions.setConfig { it.copy(displayMissingEpisodes = v) }
                }
            }
        }
        SaveLine(state.saving, state.error)
    }
}

private fun argb(c: androidx.compose.ui.graphics.Color): Long =
    (((c.alpha * 255).toLong() shl 24) or ((c.red * 255).toLong() shl 16) or ((c.green * 255).toLong() shl 8) or (c.blue * 255).toLong())

/** Settings · Gestures (no board): what fingers do in the player. */
@Composable
fun GesturesPage(state: SettingsState, actions: SettingsActions, nav: SettingsNav) {
    SettingsPage(t("Gestures"), t("What your fingers do in the player. These stay on this phone."), nav.back) {
        Section(t("In the player"), t("Skip lengths are in Playback.")) {
            row {
                SwitchRow(
                    t("Double-tap to skip"),
                    t("Left side goes back {back} s, right side forward {forward} s", "back" to state.seekBack, "forward" to state.seekForward),
                    state.device.doubleTapSeek, device = true,
                ) { v -> actions.setDevice { it.copy(doubleTapSeek = v) } }
            }
            row {
                SwitchRow(t("Swipe for brightness and volume"), t("Up and down on the left for brightness, on the right for volume"), state.device.swipeControls, device = true) { v ->
                    actions.setDevice { it.copy(swipeControls = v) }
                }
            }
        }
        SaveLine()
    }
}

/** About Seedy (no board): this app, the server and their versions. */
@Composable
fun AboutPage(state: SettingsState, appVersion: String, nav: SettingsNav) {
    SettingsPage(t("About Seedy"), t("Seedy is a home for your Jellyfin server, with requests when your admin connects Seerr."), nav.back) {
        Section(null) {
            for ((k, v) in SettingsViewModel.aboutRows(state, appVersion)) row { InfoRow(k, v) }
        }
        SText(state.serverAddress, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 40.dp), size = 12.sp, color = Quiet, align = TextAlign.Center)
    }
}

@Composable
private fun InfoRow(name: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp).heightIn(min = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SText(name, Modifier.weight(1f), size = 14.sp, weight = 700, color = SeedyColors.White)
        SText(value, size = 13.sp, weight = 600, color = SeedyColors.TextHero, align = TextAlign.End)
    }
}
