package io.github.arkmind.seedy.tv.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.feature.SettingsViewModel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.tv.parts.TvPressable
import io.github.arkmind.seedy.tv.parts.bp
import io.github.arkmind.seedy.tv.parts.bsp
import io.github.arkmind.seedy.tv.parts.focusRing
import io.github.arkmind.seedy.tv.parts.focusSoon
import io.github.arkmind.seedy.ui.components.ProfileSquare
import io.github.arkmind.seedy.ui.theme.LocalCore
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession

/**
 * Settings on the TV (no board: built from "Player settings"' rows). OK on a row moves to its next choice, so the
 * remote never needs a menu.
 */
@Composable
fun TvSettingsRoute(appVersion: String) {
    val session = currentSession()
    val core = checkNotNull(LocalCore.current)
    val vm = viewModel { SettingsViewModel(core, session) }
    val state by vm.state.collectAsStateWithLifecycle()
    val viewer = LocalViewer.current
    val first = remember { FocusRequester() }
    fun <T> next(list: List<T>, current: T): T = list[(list.indexOf(current) + 1).mod(list.size)]

    Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 176.bp, end = 96.bp, top = 54.bp, bottom = 96.bp),
            verticalArrangement = Arrangement.spacedBy(10.bp),
        ) {
            item {
                Row(Modifier.padding(bottom = 24.bp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.bp)) {
                    ProfileSquare(viewer.name, viewer.imageUrl, 0, size = 96.bp, radius = 26.bp, letterSize = 42.bsp)
                    Column(verticalArrangement = Arrangement.spacedBy(4.bp)) {
                        SText(t("Settings"), size = 44.bsp, weight = 900, color = SeedyColors.White)
                        SText("${viewer.name} · ${session.server.name}", size = 20.bsp, color = SeedyColors.Text2)
                    }
                }
            }
            item { Heading(t("Profile")) }
            item { Row(t("Switch profile"), "", "", Modifier.focusRequester(first), onClick = vm::switchProfile) }
            item { Row(t("Sign out"), t("Ends this profile’s session on this TV"), "", onClick = vm::signOut) }
            item { Heading(t("Display")) }
            item {
                val names = mapOf("auto" to t("Auto"), "en" to "English", "fr" to "Français")
                Row(t("Language"), t("Auto follows this TV"), names[state.language] ?: state.language) { vm.setLanguage(next(listOf("auto", "en", "fr"), state.language)) }
            }
            if (state.canSave) {
                val p = state.playback
                val skips = listOf("button", "auto", "off")
                val skipNames = mapOf("button" to t("Button"), "auto" to t("Automatic"), "off" to t("Off"))
                item { Heading(t("Playback")) }
                item { Row(t("Skip intros"), "", skipNames[p.skipIntro].orEmpty()) { vm.setPlayback(p.copy(skipIntro = next(skips, p.skipIntro))) } }
                item { Row(t("Skip recaps"), "", skipNames[p.skipRecap].orEmpty()) { vm.setPlayback(p.copy(skipRecap = next(skips, p.skipRecap))) } }
                item { Row(t("Skip credits"), "", skipNames[p.skipCredits].orEmpty()) { vm.setPlayback(p.copy(skipCredits = next(skips, p.skipCredits))) } }
                item { Row(t("Play the next episode"), "", if (p.autoPlayNext) t("On") else t("Off")) { vm.setPlayback(p.copy(autoPlayNext = !p.autoPlayNext)) } }
                item {
                    val qualities = listOf("auto", "4k", "1080p", "720p")
                    Row(t("Quality"), t("The most the server sends to this TV"), if (p.maxQuality == "auto") t("Auto") else p.maxQuality.uppercase().replace("P", "p")) {
                        vm.setPlayback(p.copy(maxQuality = next(qualities, p.maxQuality)))
                    }
                }
                val s = state.subtitles
                item { Heading(t("Subtitles")) }
                item { Row(t("Size"), "", s.size) { vm.setSubtitles(s.copy(size = next(listOf("S", "M", "L", "XL"), s.size))) } }
                item { Row(t("Raise subtitles above the controls"), "", if (s.lift) t("On") else t("Off")) { vm.setSubtitles(s.copy(lift = !s.lift)) } }
            }
            item { Heading(t("About")) }
            for ((k, v) in vm.about(appVersion)) item { Row(k, "", v, onClick = {}) }
        }
    }
    LaunchedEffect(Unit) { first.focusSoon() }
}

@Composable
private fun Heading(text: String) {
    SText(text, Modifier.padding(top = 24.bp, bottom = 6.bp, start = 22.bp), size = 17.bsp, weight = 800, tracking = 0.1f, color = SeedyColors.Text3, uppercase = true)
}

/** The player settings' row: label and line under it, the value on the right; light with dark text when focused. */
@Composable
private fun Row(label: String, sub: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TvPressable(onClick, modifier.width(1100.bp)) { f ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 84.bp).focusRing(f, radius = 18.bp, gap = 4.bp, ring = 4.bp)
                .clip(RoundedCornerShape(18.bp)).background(if (f) SeedyColors.Inverse else Color.Transparent).padding(horizontal = 22.bp, vertical = 10.bp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.bp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.bp)) {
                SText(label, size = 21.bsp, weight = 800, color = if (f) SeedyColors.InkInverse else SeedyColors.White, maxLines = 1)
                if (sub.isNotEmpty()) SText(sub, size = 16.bsp, color = if (f) Color(0xFF4A4A4A) else SeedyColors.Text3, maxLines = 1)
            }
            SText(value, size = 19.bsp, weight = 700, color = if (f) SeedyColors.InkInverse else SeedyColors.TextHero, maxLines = 1)
        }
    }
}
