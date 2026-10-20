package io.github.arkmind.seedy.mobile.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.feature.SettingsViewModel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.parts.HeaderButton
import io.github.arkmind.seedy.mobile.parts.PrimaryButton
import io.github.arkmind.seedy.mobile.parts.SeedyField
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.components.ProfileSquare
import io.github.arkmind.seedy.ui.components.SeedySwitch
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalCore
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession

/** Settings (no Android board: built from the phone boards' parts and the web's settings pages). */
@Composable
fun SettingsRoute(appVersion: String) {
    val session = currentSession()
    val core = checkNotNull(LocalCore.current)
    val nav = LocalMobileNav.current
    val vm = viewModel { SettingsViewModel(core, session) }
    val state by vm.state.collectAsStateWithLifecycle()
    val viewer = LocalViewer.current
    val top = max(36.dp, WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp)
    val bottom = max(28.dp, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp)

    Column(Modifier.fillMaxSize().background(SeedyColors.Bg).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = top), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            HeaderButton(WebIcons.ArrowLeft, { nav.back() }, stroke = 2.2f)
            SText(t("Settings"), Modifier.weight(1f).padding(start = 6.dp), size = 22.sp, weight = 900, color = SeedyColors.White)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SeedyColors.Panel).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ProfileSquare(viewer.name, viewer.imageUrl, 0, size = 56.dp, radius = 16.dp, letterSize = 24.sp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SText(viewer.name, size = 18.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
                    SText(session.server.name, size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallButton(t("Switch profile"), Modifier.weight(1f), vm::switchProfile)
                SmallButton(t("Sign out"), Modifier.weight(1f), vm::signOut)
            }

            Section(t("Display"))
            Label(t("Language"))
            Segmented(listOf("auto" to t("Auto"), "en" to "English", "fr" to "Français"), state.language, vm::setLanguage)

            if (state.canSave) {
                Section(t("Playback"))
                val p = state.playback
                val skips = listOf("button" to t("Button"), "auto" to t("Automatic"), "off" to t("Off"))
                Label(t("Skip intros"))
                Segmented(skips, p.skipIntro) { vm.setPlayback(p.copy(skipIntro = it)) }
                Label(t("Skip recaps"))
                Segmented(skips, p.skipRecap) { vm.setPlayback(p.copy(skipRecap = it)) }
                Label(t("Skip credits"))
                Segmented(skips, p.skipCredits) { vm.setPlayback(p.copy(skipCredits = it)) }
                SwitchRow(t("Play the next episode"), p.autoPlayNext) { vm.setPlayback(p.copy(autoPlayNext = it)) }
                Label(t("Quality"))
                Segmented(listOf("auto" to t("Auto"), "4k" to "4K", "1080p" to "1080p", "720p" to "720p"), p.maxQuality) { vm.setPlayback(p.copy(maxQuality = it)) }

                Section(t("Subtitles"))
                val s = state.subtitles
                Label(t("Size"))
                Segmented(listOf("S" to "S", "M" to "M", "L" to "L", "XL" to "XL"), s.size) { vm.setSubtitles(s.copy(size = it)) }
                SwitchRow(t("Raise subtitles above the controls"), s.lift) { vm.setSubtitles(s.copy(lift = it)) }
                state.error?.let { SText(it, size = 13.sp, weight = 600, color = SeedyColors.Danger) }
            }

            Section(t("Quick Connect"))
            SText(t("Type the code another device shows to sign it in as you."), size = 13.sp, color = SeedyColors.Text2, lineHeight = 1.45f)
            SeedyField(state.code, vm::setCode, placeholder = "123456", keyboard = KeyboardType.Number, onDone = vm::approve)
            PrimaryButton(if (state.approving) t("Approving…") else t("Approve"), vm::approve, enabled = state.code.length == 6 && !state.approving)
            state.approved?.let { SText(it, size = 13.sp, weight = 600, color = SeedyColors.Text2) }

            Section(t("About"))
            for ((k, v) in vm.about(appVersion)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    SText(k, size = 13.sp, color = SeedyColors.Text3)
                    SText(v, size = 13.sp, weight = 700, color = SeedyColors.Text)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    SText(title, Modifier.padding(top = 14.dp), size = 16.sp, weight = 800, color = SeedyColors.White)
}

@Composable
private fun Label(text: String) {
    SText(text, size = 13.sp, weight = 700, color = SeedyColors.Text)
}

@Composable
private fun SmallButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Surface).tap(onClick = onClick), contentAlignment = Alignment.Center) {
        SText(label, size = 13.sp, weight = 700, color = SeedyColors.White)
    }
}

/** The speed panel's segmented row, for any short list of choices. */
@Composable
private fun Segmented(options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(SeedyColors.Surface).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((key, label) in options) {
            val on = key.equals(value, ignoreCase = true)
            Box(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(11.dp)).background(if (on) SeedyColors.Inverse else Color.Transparent).tap { onChange(key) },
                contentAlignment = Alignment.Center,
            ) { SText(label, size = 13.sp, weight = if (on) 800 else 600, color = if (on) SeedyColors.InkInverse else SeedyColors.Text, maxLines = 1) }
        }
    }
}

@Composable
private fun SwitchRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SText(label, Modifier.weight(1f), size = 14.sp, weight = 600, color = SeedyColors.Text)
        SeedySwitch(on, onChange)
    }
}
