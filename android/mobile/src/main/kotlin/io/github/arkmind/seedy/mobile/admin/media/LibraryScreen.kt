package io.github.arkmind.seedy.mobile.admin.media

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.admin.media.LibraryActions
import io.github.arkmind.seedy.core.admin.media.LibraryPages
import io.github.arkmind.seedy.core.admin.media.LibraryState
import io.github.arkmind.seedy.core.admin.media.LibraryViewModel
import io.github.arkmind.seedy.core.admin.media.SourceRow
import io.github.arkmind.seedy.core.admin.media.SwitchItem
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.admin.Bar
import io.github.arkmind.seedy.mobile.settings.Card
import io.github.arkmind.seedy.mobile.admin.ConfirmSheet
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.admin.PillRow
import io.github.arkmind.seedy.mobile.admin.PillStyle
import io.github.arkmind.seedy.mobile.settings.RowLine
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.nav.Route
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.settings.BoardSwitch
import io.github.arkmind.seedy.mobile.settings.PickRow
import io.github.arkmind.seedy.mobile.settings.SaveLine
import io.github.arkmind.seedy.mobile.settings.SettingsPage
import io.github.arkmind.seedy.mobile.settings.SheetHost
import io.github.arkmind.seedy.mobile.settings.SwitchRow
import io.github.arkmind.seedy.mobile.settings.rememberSheet
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.IconPart
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession
import kotlin.math.roundToInt
import io.github.arkmind.seedy.mobile.admin.AccentRow
import io.github.arkmind.seedy.mobile.admin.Chip
import io.github.arkmind.seedy.mobile.admin.RoundButton
import io.github.arkmind.seedy.mobile.admin.SearchPickSheet
import io.github.arkmind.seedy.mobile.settings.SectionTitle
import io.github.arkmind.seedy.mobile.settings.SectionFoot
import io.github.arkmind.seedy.mobile.settings.Section
import io.github.arkmind.seedy.core.settings.Choice
import io.github.arkmind.seedy.mobile.admin.MonoText

/** Server › Libraries › one library (web board "Server · Libraries", its editor, on the phone kit). */
@Composable
fun LibraryRoute(id: String) {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val vm = viewModel(key = "library-$id") { LibraryViewModel(session, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(id) { vm.load() }
    LaunchedEffect(id) { vm.live() }
    LaunchedEffect(state.removed) { if (state.removed) nav.back() }
    LibraryScreen(state, vm, onBack = nav::back, open = { nav.go(Route.Admin("library", it)) })
}

private val Grip = IconDef(
    0f,
    listOf(7f, 12f, 17f).flatMap { y -> listOf(9f, 15f).map { x -> IconPart("M${x - 1.5f} ${y}a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0Z", filled = true) } },
)

private enum class Ask { None, RemoveLibrary, Refresh, Language }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(state: LibraryState, actions: LibraryActions, onBack: () -> Unit, open: (String) -> Unit) {
    val sheet = rememberSheet()
    var ask by remember { mutableStateOf(Ask.None) }
    var removing by remember { mutableStateOf<String?>(null) }
    val accent = LocalAccent.current
    SettingsPage(
        state.title.ifEmpty { t("Libraries") },
        lede = state.lede ?: state.loadError ?: "…",
        onBack = onBack,
        overlay = {
            SheetHost(sheet)
            state.picker?.let { FolderPickerSheet(it, actions) }
            when (ask) {
                Ask.RemoveLibrary -> ConfirmSheet(
                    t("Remove {name}?", "name" to state.title),
                    t("The library and everyone’s watch progress in it are removed from Jellyfin. The media files on disk aren’t touched, and you can add the folders again later."),
                    t("Remove library"), actions::removeLibrary, { ask = Ask.None }, danger = true,
                )
                Ask.Refresh -> ConfirmSheet(
                    t("Refresh metadata for {name}?", "name" to state.title),
                    t("Jellyfin looks up details and artwork for every title in this library again. Anything you edited by hand stays. On a big library this takes a while."),
                    t("Refresh metadata"), actions::refreshMetadata, { ask = Ask.None },
                )
                Ask.Language -> SearchPickSheet(
                    t("Add language"), null, state.moreLanguages.map { Choice(it.code, it.name) }, null, actions::addSubtitleLanguage, { ask = Ask.None },
                    searchLabel = t("Find a language"), empty = if (state.moreLanguages.isEmpty()) t("Loading languages…") else t("No language matches"), limit = 120,
                )
                Ask.None -> Unit
            }
            removing?.let { p ->
                ConfirmSheet(
                    t("Remove {path}?", "path" to p),
                    t("{name} stops showing what’s in this folder after the next scan. The files themselves stay where they are.", "name" to state.title),
                    t("Remove folder"), { actions.removeFolder(p) }, { removing = null }, danger = true,
                )
            }
            Toast(state.message, actions::messageShown, Modifier.align(Alignment.BottomCenter))
        },
    ) {
        if (state.lede == null) {
            if (state.loadError != null) PillRow { PillButton(t("Try again"), actions::retry) }
            return@SettingsPage
        }
        PillRow {
            PillButton(t("Scan"), actions::scan, style = PillStyle.Light)
            PillButton(t("Refresh metadata"), { ask = Ask.Refresh })
        }
        state.scanState?.let { s ->
            Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp), padding = 14.dp, gap = 10.dp) {
                SText(s, size = 13.sp, weight = 700, color = accent)
                Bar(state.scanProgress)
            }
        }

        SectionTitle(t("Folders"))
        Card(Modifier.padding(horizontal = 16.dp)) {
            for (p in state.folders) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp).heightIn(min = 32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MonoText(p, Modifier.weight(1f), maxLines = 3)
                    RoundButton(WebIcons.Close, { removing = p }, size = 32.dp, iconSize = 14.dp, tint = SeedyColors.Text2, enabled = state.folders.size > 1, dim = 0.4f)
                }
                RowLine()
            }
            AccentRow(t("Add folder"), actions::addFolder, roomy = true)
        }
        if (state.folders.size == 1) SectionFoot(t("A library needs at least one folder"))

        Section(t("Metadata")) {
            row { PickRow(t("Language"), null, state.language.ifEmpty { "…" }) { sheet.open(t("Language"), state.languageOptions, state.languageValue, actions::setLanguage) } }
            row { PickRow(t("Country"), null, state.country.ifEmpty { "…" }) { sheet.open(t("Country"), state.countryOptions, state.countryValue, actions::setCountry) } }
            row { PickRow(t("Refresh every"), null, state.refresh) { sheet.open(t("Refresh every"), state.refreshOptions, state.refreshValue, actions::setRefresh) } }
        }

        SectionTitle(t("Sources, in order · drag to reorder"))
        Card(Modifier.padding(horizontal = 16.dp)) {
            val sources = state.sources
            when {
                sources == null && state.sourcesFailed -> Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SText(t("Couldn’t load the metadata sources."), Modifier.weight(1f), size = 13.sp, color = SeedyColors.Danger)
                    PillButton(t("Try again"), actions::retry)
                }
                sources == null -> SText("…", Modifier.padding(16.dp), size = 13.sp, color = SeedyColors.Text3)
                sources.isEmpty() -> SText(t("This kind of library has no metadata sources."), Modifier.padding(16.dp), size = 13.sp, color = SeedyColors.Text3)
                else -> SourceList(sources, actions)
            }
        }
        if (state.similarOptions.isNotEmpty()) {
            Section(null) {
                row {
                    PickRow(t("Recommendations from"), t("Feeds More like this and Because you watched"), state.similar.orEmpty()) {
                        sheet.open(t("Recommendations from"), state.similarOptions, state.similarValue, actions::setSimilar)
                    }
                }
            }
        }

        Section(t("Subtitles")) {
            row {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SText(t("Download in"), size = 14.sp, weight = 700, color = SeedyColors.White)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (l in state.subtitleLanguages) {
                            Chip(l.name, on = true, onClick = { actions.removeSubtitleLanguage(l.code) }, tick = false, trailing = { SIcon(WebIcons.Close, size = 11.dp, color = SeedyColors.InkInverse) })
                        }
                        Chip(t("Add language"), on = false, onClick = { ask = Ask.Language }, tick = false)
                    }
                }
            }
            for (f in state.subtitleFetchers) row { Switch(f, actions) }
            for (s in state.subtitleSwitches) row { Switch(s, actions) }
        }

        Section(t("Scanning and previews")) {
            for (s in state.scanSwitches) row { Switch(s, actions) }
        }

        val segments = state.segmentProviders
        Section(t("Skip markers from"), foot = t("Media segment providers, in order")) {
            when {
                segments == null -> row { SText("…", Modifier.padding(16.dp), size = 13.sp, color = SeedyColors.Text3) }
                segments.isEmpty() -> row { SText(t("None installed"), Modifier.padding(16.dp), size = 13.sp, color = SeedyColors.Text3) }
                else -> for (s in segments) row { Switch(s, actions) }
            }
        }

        Section(t("All libraries")) {
            row { PickRow(t("Display"), t("Folders view, specials and collections"), "") { open(LibraryPages.Display) } }
            row { PickRow(t("Metadata"), t("Language, chapters and saved files"), "") { open(LibraryPages.Metadata) } }
            row { PickRow(t("NFO files"), t("Files Kodi and other apps read"), "") { open(LibraryPages.Nfo) } }
        }

        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp)) {
            PillButton(t("Remove library"), { ask = Ask.RemoveLibrary }, style = PillStyle.Danger)
        }
        SaveLine(state.saving, state.error)
    }
}

@Composable
private fun Switch(s: SwitchItem, actions: LibraryActions) = SwitchRow(s.name, s.help, s.on) { actions.setSwitch(s.key, it) }

/** The sources with a grip each: drag a grip up or down to move the source. */
@Composable
private fun SourceList(sources: List<SourceRow>, actions: LibraryActions) {
    var from by remember { mutableStateOf<Int?>(null) }
    var dy by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(1) }
    val target = from?.let { f -> (f + (dy / rowHeight.coerceAtLeast(1)).roundToInt()).coerceIn(0, sources.lastIndex) }
    sources.forEachIndexed { i, s ->
        if (i > 0) RowLine()
        val dragging = from == i
        // Rows between the dragged one and where it would land make room for it.
        val shift = when {
            from == null || target == null || dragging -> 0f
            from!! < i && i <= target -> -rowHeight.toFloat()
            from!! > i && i >= target -> rowHeight.toFloat()
            else -> 0f
        }
        Row(
            Modifier.fillMaxWidth().onSizeChanged { rowHeight = it.height + 1 }.zIndex(if (dragging) 1f else 0f)
                .graphicsLayer { translationY = if (dragging) dy else shift }
                .background(if (dragging) SeedyColors.Row else SeedyColors.Panel)
                .padding(start = 6.dp, end = 14.dp, top = 8.dp, bottom = 8.dp).heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).pointerInput(sources.size, i) {
                    detectDragGestures(
                        onDragStart = { from = i; dy = 0f },
                        onDrag = { change, amount -> change.consume(); dy += amount.y },
                        onDragEnd = {
                            val to = target
                            if (to != null) actions.moveSource(i, to)
                            from = null
                            dy = 0f
                        },
                        onDragCancel = { from = null; dy = 0f },
                    )
                },
                contentAlignment = Alignment.Center,
            ) { SIcon(Grip, size = 16.dp, color = SeedyColors.Text3) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SText(s.label, size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                SText(s.kind, size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
            }
            BoardSwitch(s.enabled, { actions.setSource(s.name, it) })
        }
    }
}
