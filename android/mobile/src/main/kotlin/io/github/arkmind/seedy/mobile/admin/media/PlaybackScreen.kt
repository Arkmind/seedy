package io.github.arkmind.seedy.mobile.admin.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.admin.media.PlaybackActions
import io.github.arkmind.seedy.core.admin.media.PlaybackPages
import io.github.arkmind.seedy.core.admin.media.PlaybackState
import io.github.arkmind.seedy.core.admin.media.PlaybackViewModel
import io.github.arkmind.seedy.core.admin.media.SettingActions
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.admin.ConfirmSheet
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.admin.PillRow
import io.github.arkmind.seedy.mobile.admin.RadioRow
import io.github.arkmind.seedy.mobile.admin.RowLine
import io.github.arkmind.seedy.mobile.admin.SaveBar
import io.github.arkmind.seedy.mobile.admin.SaveBarRoom
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.settings.SectionFoot
import io.github.arkmind.seedy.mobile.settings.SettingsPage
import io.github.arkmind.seedy.mobile.settings.cssBox
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession

/** Board "Android · Server · Playback", and its Resume, Streaming and Seek previews pages. */
@Composable
fun PlaybackRoute() {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val vm = viewModel { PlaybackViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(Unit) { vm.live() }
    val back = {
        when {
            page != null -> page = null
            state.changes > 0 -> leaving = true
            else -> nav.back()
        }
    }
    BackHandler(enabled = page != null || state.changes > 0) { back() }
    PlaybackScreen(state, vm, page, onBack = back, openPage = { page = it })
    if (leaving) {
        ConfirmSheet(
            t("Leave without saving?"),
            t("Your changes to the playback settings haven’t been saved yet. If you leave now, they’re lost."),
            t("Leave without saving"), { vm.discard(); nav.back() }, { leaving = false }, danger = true,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaybackScreen(state: PlaybackState, actions: PlaybackActions, page: String?, onBack: () -> Unit, openPage: (String) -> Unit) {
    val sheets = rememberMediaSheets()
    // Rows that open another page stay on this screen.
    val rows = object : SettingActions by actions {
        override fun open(key: String) = if (key.startsWith("page:")) openPage(key.removePrefix("page:")) else actions.open(key)
    }
    val (title, lede) = when (page) {
        PlaybackPages.Resume -> t("Resume") to t("When a video keeps its place, and when it counts as watched.")
        PlaybackPages.Streaming -> t("Streaming") to t("Limits outside the home network, audio, deinterlacing and subtitles.")
        PlaybackPages.Previews -> t("Seek previews") to t("The pictures over the progress bar while seeking.")
        else -> t("Playback") to t("How the server converts video for devices that can’t play the original.")
    }
    SettingsPage(
        title,
        lede,
        onBack = onBack,
        overlay = {
            MediaSheetHost(sheets, actions::setNumber)
            state.picker?.let { FolderPickerSheet(it, actions) }
            if (state.changes > 0) SaveBar(state.unsaved, actions::discard, actions::save, saving = state.saving)
            Toast(state.message, actions::messageShown, Modifier.align(Alignment.BottomCenter).padding(bottom = if (state.changes > 0) 82.dp else 0.dp))
        },
    ) {
        when {
            !state.loaded && state.loadError != null -> {
                SText(state.loadError.orEmpty(), Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), size = 13.sp, color = SeedyColors.Danger)
                PillRow { PillButton(t("Try again"), actions::retry) }
            }
            !state.loaded -> SText("…", Modifier.padding(20.dp), size = 13.sp, color = SeedyColors.Text3)
            page == PlaybackPages.Resume -> SettingCards(state.resume, rows, sheets)
            page == PlaybackPages.Streaming -> SettingCards(state.streaming, rows, sheets)
            page == PlaybackPages.Previews -> SettingCards(state.previews, rows, sheets)
            else -> Transcoding(state, actions, rows, sheets)
        }
        SaveBarRoom()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Transcoding(state: PlaybackState, actions: PlaybackActions, rows: SettingActions, sheets: MediaSheets) {
    state.ffmpeg?.let { line ->
        val ink = if (state.ffmpegFound) SeedyColors.Ok else SeedyColors.Danger
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(999.dp)).background(ink.copy(alpha = 0.08f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(7.dp).background(ink, CircleShape))
            SText(line, Modifier.cssBox(12, io.github.arkmind.seedy.ui.theme.NotoLineRatio, up = true), size = 12.sp, weight = 600, color = SeedyColors.TextHero)
        }
    } ?: Box(Modifier.padding(top = 12.dp).height(36.dp))

    MediaSectionTitle(t("Hardware acceleration"))
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SeedyColors.Panel)) {
        state.accels.forEachIndexed { i, a ->
            if (i > 0) RowLine()
            RadioRow(a.name, a.sub, a.on, { actions.setAccel(a.id) }, subColor = if (a.detected) SeedyColors.Ok else SeedyColors.Text3)
        }
    }
    MediaSectionFoot(t("Uses the GPU for converting video. Much faster than the CPU, especially for HDR."))

    if (state.codecs.isNotEmpty()) {
        MediaSectionTitle(t("Decode on the GPU"))
        FlowRow(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SeedyColors.Panel).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (c in state.codecs) Chip(c.name, c.on, { actions.setCodec(c.key, !c.on) })
        }
        MediaSectionFoot(t("Formats your GPU can read. Turned-off ones fall back to the CPU."))
    }

    SettingCards(state.groups, rows, sheets)
}
