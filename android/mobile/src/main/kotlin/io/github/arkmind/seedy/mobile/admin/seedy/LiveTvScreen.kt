package io.github.arkmind.seedy.mobile.admin.seedy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.admin.seedy.ListingsProvider
import io.github.arkmind.seedy.core.admin.seedy.LiveTvAdminActions
import io.github.arkmind.seedy.core.admin.seedy.LiveTvAdminState
import io.github.arkmind.seedy.core.admin.seedy.RecSettings
import io.github.arkmind.seedy.core.admin.seedy.SourceRow
import io.github.arkmind.seedy.core.admin.seedy.TunerHost
import io.github.arkmind.seedy.core.admin.seedy.TunerRow
import io.github.arkmind.seedy.core.admin.seedy.guideDaysLabel
import io.github.arkmind.seedy.core.admin.seedy.hostOf
import io.github.arkmind.seedy.core.admin.seedy.newTuner
import io.github.arkmind.seedy.core.admin.seedy.sameTuner
import io.github.arkmind.seedy.core.admin.seedy.tunerName
import io.github.arkmind.seedy.core.admin.seedy.tunerTypeLabel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.mobile.admin.ConfirmSheet
import io.github.arkmind.seedy.mobile.admin.Dot
import io.github.arkmind.seedy.mobile.admin.ErrorCard
import io.github.arkmind.seedy.mobile.admin.FieldBlock
import io.github.arkmind.seedy.mobile.admin.LeaveGuard
import io.github.arkmind.seedy.mobile.admin.LoadingCards
import io.github.arkmind.seedy.mobile.admin.MenuItem
import io.github.arkmind.seedy.mobile.admin.MenuSheet
import io.github.arkmind.seedy.mobile.admin.NameTag
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.admin.PillRow
import io.github.arkmind.seedy.mobile.admin.PillStyle
import io.github.arkmind.seedy.mobile.admin.SaveBar
import io.github.arkmind.seedy.mobile.admin.SaveBarRoom
import io.github.arkmind.seedy.mobile.admin.SheetFoot
import io.github.arkmind.seedy.mobile.admin.TextSheet
import io.github.arkmind.seedy.mobile.browse.BottomSheet
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.settings.Card
import io.github.arkmind.seedy.mobile.settings.PickRow
import io.github.arkmind.seedy.mobile.settings.RowLabel
import io.github.arkmind.seedy.mobile.settings.RowLine
import io.github.arkmind.seedy.mobile.settings.Section
import io.github.arkmind.seedy.mobile.settings.SectionTitle
import io.github.arkmind.seedy.mobile.settings.SettingsPage
import io.github.arkmind.seedy.mobile.settings.SheetHead
import io.github.arkmind.seedy.mobile.settings.SheetHost
import io.github.arkmind.seedy.mobile.settings.SheetState
import io.github.arkmind.seedy.mobile.settings.SwitchRow
import io.github.arkmind.seedy.mobile.settings.rememberSheet
import io.github.arkmind.seedy.mobile.settings.settingsRow
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/** A page opened from Live TV: a tuner's editor, a guide source's, or its channel mapping. */
internal sealed interface LiveSub {
    data class Tuner(val draft: TunerHost, val isNew: Boolean) : LiveSub
    data class Source(val provider: ListingsProvider?) : LiveSub
    data class Mapping(val provider: ListingsProvider) : LiveSub
}

private val GuideDays = listOf(0, 1, 3, 7, 14)
private val PaddingMinutes = listOf(0, 1, 2, 3, 5, 10, 15, 20, 30, 45, 60, 90, 120)

/** Which recording setting a sheet edits. */
private enum class RecSheet { Folder, Movies, Series, Padding, Post }

/** Server › Live TV (no phone board: the server boards' kit with the web board ServerLiveTV's content). */
@Composable
fun LiveTvAdminScreen(state: LiveTvAdminState, actions: LiveTvAdminActions, onBack: () -> Unit) {
    var page by remember { mutableStateOf<LiveSub?>(null) }
    when (val p = page) {
        is LiveSub.Tuner -> {
            BackHandler { page = null }
            TunerEditor(p.draft, p.isNew, state, actions, onClose = { page = null }, onChange = { page = p.copy(draft = it) })
        }
        is LiveSub.Source -> {
            BackHandler { page = null; actions.reload() }
            SourceEditor(p.provider, state.tuners, actions, onClose = { page = null; actions.reload() })
        }
        is LiveSub.Mapping -> {
            BackHandler { page = null }
            MapChannels(p.provider, actions) { page = null }
        }
        null -> LiveTvMain(state, actions, onBack) { page = it }
    }
}

@Composable
private fun LiveTvMain(state: LiveTvAdminState, actions: LiveTvAdminActions, onBack: () -> Unit, open: (LiveSub) -> Unit) {
    val sheet = rememberSheet()
    var tunerMenu by remember { mutableStateOf<TunerRow?>(null) }
    var sourceMenu by remember { mutableStateOf<SourceRow?>(null) }
    var removeTuner by remember { mutableStateOf<TunerRow?>(null) }
    var removeSource by remember { mutableStateOf<SourceRow?>(null) }
    var finding by remember { mutableStateOf(false) }
    var recSheet by remember { mutableStateOf<RecSheet?>(null) }
    var browse by remember { mutableStateOf<RecSheet?>(null) }
    val changes = state.changes
    LeaveGuard(changes > 0, t("Your changes to Live TV haven’t been saved yet. If you leave now, they’re lost."), onBack)
    SettingsPage(
        t("Live TV"),
        t("Tuners, guide data and recordings."),
        onBack,
        overlay = {
            SheetHost(sheet)
            tunerMenu?.let { r ->
                MenuSheet(r.name, listOf(
                    MenuItem(t("Edit")) { state.tuners.firstOrNull { it.id == r.id }?.let { open(LiveSub.Tuner(it, false)) } },
                    MenuItem(t("Remove tuner"), danger = true) { removeTuner = r },
                ), { tunerMenu = null })
            }
            removeTuner?.let { r ->
                ConfirmSheet(
                    t("Remove {name}?", "name" to r.name), t("Its channels and their listings leave Live TV. Recordings already made are kept."),
                    t("Remove tuner"), { actions.removeTuner(r.id) }, { removeTuner = null }, danger = true,
                )
            }
            sourceMenu?.let { r ->
                val p = state.providers.firstOrNull { it.id == r.id }
                MenuSheet(r.name, listOf(
                    MenuItem(t("Map channels")) { p?.let { open(LiveSub.Mapping(it)) } },
                    MenuItem(t("Edit")) { p?.let { open(LiveSub.Source(it)) } },
                    MenuItem(t("Remove guide source"), danger = true) { removeSource = r },
                ), { sourceMenu = null })
            }
            removeSource?.let { r ->
                ConfirmSheet(
                    t("Remove {name}?", "name" to r.name), t("Channels it filled in lose their listings until another guide source covers them."),
                    t("Remove guide source"), { actions.removeProvider(r.id) }, { removeSource = null }, danger = true,
                )
            }
            if (finding) FindTuners(state.tuners, actions, { found -> open(LiveSub.Tuner(found.copy(id = null), true)) }) { finding = false }
            state.recValue?.let { r -> RecSheets(recSheet, r, actions, { recSheet = null }, { which -> recSheet = null; browse = which }) }
            browse?.let { which ->
                val r = state.recValue
                if (r != null) {
                    ServerFolderSheet(
                        t("Choose a folder"), recPath(r, which), false, actions::folders,
                        { v -> actions.editRec { setRecPath(it, which, v) } }, { browse = null },
                    )
                }
            }
            if (changes > 0) SaveBar(tn(changes, "{n} unsaved change", "{n} unsaved changes"), actions::discardRec, actions::saveRec, saving = state.saving)
            Toast(state.message, actions::messageShown, Modifier.align(Alignment.BottomCenter).padding(bottom = if (changes > 0) 90.dp else 0.dp))
        },
    ) {
        PillRow {
            PillButton(
                when {
                    !state.refreshing -> t("Refresh guide data")
                    state.refreshPct != null -> t("Refreshing guide · {pct}%", "pct" to state.refreshPct)
                    else -> t("Refreshing guide…")
                },
                actions::refreshGuide, enabled = !state.refreshing && state.loaded,
                leading = { SIcon(WebIcons.Refresh, size = 16.dp, color = SeedyColors.White) },
            )
        }
        when {
            !state.loaded && state.loadError != null -> ErrorCard(t("Couldn’t load the Live TV settings"), actions::retry, Modifier.padding(top = 16.dp), detail = state.loadError.orEmpty())
            !state.loaded -> LoadingCards(3)
            else -> {
                SectionTitle(t("Tuners"))
                Card(Modifier.padding(horizontal = 16.dp)) {
                    if (state.tunerRows.isEmpty()) {
                        EmptyLine(WebIcons.Tv, t("No tuners yet"), t("Add an HDHomeRun or an M3U playlist, or let the server look for tuners on your network."))
                    }
                    state.tunerRows.forEachIndexed { i, r ->
                        if (i > 0) RowLine()
                        TunerLine(r) { tunerMenu = r }
                    }
                }
                PillRow(top = 10.dp) {
                    PillButton(t("Add tuner"), { open(LiveSub.Tuner(newTuner("m3u"), true)) }, style = PillStyle.Light, leading = { SIcon(WebIcons.Plus, size = 15.dp, color = SeedyColors.InkInverse) })
                    PillButton(t("Find tuners on my network"), { finding = true })
                }

                SectionTitle(t("Guide data"))
                Card(Modifier.padding(horizontal = 16.dp)) {
                    if (state.sourceRows.isEmpty()) {
                        EmptyLine(WebIcons.List, t("No guide data yet"), t("Add an XMLTV file or a Schedules Direct account so channels show what’s on."))
                    }
                    state.sourceRows.forEachIndexed { i, r ->
                        if (i > 0) RowLine()
                        SourceLine(r) { sourceMenu = r }
                    }
                }
                PillRow(top = 10.dp) {
                    PillButton(t("Add guide source"), { open(LiveSub.Source(null)) }, leading = { SIcon(WebIcons.Plus, size = 15.dp, color = SeedyColors.White) })
                }
                state.recValue?.let { r -> Recordings(r, actions, sheet, open = { recSheet = it }) }
            }
        }
        if (changes > 0) SaveBarRoom()
    }
}

private fun recPath(r: RecSettings, which: RecSheet): String = when (which) {
    RecSheet.Movies -> r.movieRecordingPath
    RecSheet.Series -> r.seriesRecordingPath
    else -> r.recordingPath
}

private fun setRecPath(r: RecSettings, which: RecSheet, v: String): RecSettings = when (which) {
    RecSheet.Movies -> r.copy(movieRecordingPath = v.trim())
    RecSheet.Series -> r.copy(seriesRecordingPath = v.trim())
    else -> r.copy(recordingPath = v.trim())
}

@Composable
private fun Recordings(r: RecSettings, actions: LiveTvAdminActions, sheet: SheetState, open: (RecSheet) -> Unit) {
    Section(null) {
        row {
            val options = withValue(GuideDays, r.guideDays)
            PickRow(t("Days of guide to download"), t("More days lets people schedule further ahead, but updates take longer"), guideDaysLabel(r.guideDays)) {
                sheet.open(t("Days of guide to download"), choices(options, ::guideDaysLabel), r.guideDays) { v -> actions.editRec { it.copy(guideDays = v) } }
            }
        }
    }
    Section(t("Recordings")) {
        row { PickRow(t("Recordings folder"), t("Empty uses the server data folder"), r.recordingPath.ifEmpty { t("Server data folder") }) { open(RecSheet.Folder) } }
        row { PickRow(t("Movie recordings"), t("Optional, overrides the folder above"), r.movieRecordingPath.ifEmpty { t("Same as recordings folder") }) { open(RecSheet.Movies) } }
        row { PickRow(t("Series recordings"), t("Optional, overrides the folder above"), r.seriesRecordingPath.ifEmpty { t("Same as recordings folder") }) { open(RecSheet.Series) } }
        row {
            val before = r.prePaddingSeconds / 60
            val after = r.postPaddingSeconds / 60
            PickRow(t("Padding"), t("Start early and stop late when the tuner allows"), "$before ${t("min before")} · $after ${t("min after")}") { open(RecSheet.Padding) }
        }
        row { PickRow(t("After recording, run"), t("Optional. Use {path} for the recording file"), r.recordingPostProcessor.ifEmpty { t("None") }) { open(RecSheet.Post) } }
        row { SwitchRow(t("Save guide info as NFO"), t("Store listing details next to each recording"), r.saveRecordingNfo) { v -> actions.editRec { it.copy(saveRecordingNfo = v) } } }
        row { SwitchRow(t("Save guide images"), t("Store listing artwork next to each recording"), r.saveRecordingImages) { v -> actions.editRec { it.copy(saveRecordingImages = v) } } }
    }
}

/** The recording settings' sheets: a folder (typed, or browsed on the server), the padding, the program to run. */
@Composable
private fun RecSheets(which: RecSheet?, r: RecSettings, actions: LiveTvAdminActions, onDismiss: () -> Unit, browse: (RecSheet) -> Unit) {
    when (which) {
        null -> {}
        RecSheet.Folder, RecSheet.Movies, RecSheet.Series -> {
            val (title, placeholder) = when (which) {
                RecSheet.Movies -> t("Movie recordings") to t("Same as recordings folder")
                RecSheet.Series -> t("Series recordings") to t("Same as recordings folder")
                else -> t("Recordings folder") to t("Server data folder")
            }
            TextSheet(
                title, if (which == RecSheet.Folder) t("Empty uses the server data folder") else t("Optional, overrides the folder above"),
                t("Save"), { v -> actions.editRec { setRecPath(it, which, v) } }, onDismiss,
                initial = recPath(r, which), placeholder = placeholder, keyboard = KeyboardType.Uri, required = false,
                extra = { PillButton(t("Browse"), { browse(which) }, leading = { SIcon(WebIcons.Folder, size = 15.dp, color = SeedyColors.White) }) },
            )
        }
        RecSheet.Padding -> {
            var before by remember { mutableStateOf((r.prePaddingSeconds / 60).toString()) }
            var after by remember { mutableStateOf((r.postPaddingSeconds / 60).toString()) }
            val save = {
                val b = before.toIntOrNull()?.coerceIn(0, 120) ?: 0
                val a = after.toIntOrNull()?.coerceIn(0, 240) ?: 0
                actions.editRec { it.copy(prePaddingSeconds = b * 60, postPaddingSeconds = a * 60) }
                onDismiss()
            }
            BottomSheet(onDismiss) {
                SheetHead(t("Padding"), t("Start early and stop late when the tuner allows"))
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FieldBlock(t("Minutes before"), before, { before = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), keyboard = KeyboardType.Number)
                    FieldBlock(t("Minutes after"), after, { after = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), keyboard = KeyboardType.Number, onDone = save)
                }
                SheetFoot {
                    PillButton(t("Cancel"), onDismiss)
                    PillButton(t("Save"), save, style = PillStyle.Light)
                }
            }
        }
        RecSheet.Post -> {
            var program by remember { mutableStateOf(r.recordingPostProcessor) }
            var args by remember { mutableStateOf(r.recordingPostProcessorArguments) }
            val save = {
                actions.editRec { it.copy(recordingPostProcessor = program.trim(), recordingPostProcessorArguments = args) }
                onDismiss()
            }
            BottomSheet(onDismiss) {
                SheetHead(t("After recording, run"), t("Optional. Use {path} for the recording file"))
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    FieldBlock(t("Program"), program, { program = it }, placeholder = t("Program, e.g. /usr/bin/comskip"), keyboard = KeyboardType.Uri)
                    FieldBlock(t("Arguments"), args, { args = it }, onDone = save)
                }
                SheetFoot {
                    PillButton(t("Cancel"), onDismiss)
                    PillButton(t("Save"), save, style = PillStyle.Light)
                }
            }
        }
    }
}

@Composable
private fun EmptyLine(icon: io.github.arkmind.seedy.ui.icons.IconDef, title: String, text: String) {
    Row(Modifier.settingsRow(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(icon)
        RowLabel(title, text, Modifier.weight(1f))
    }
}

@Composable
private fun TunerLine(r: TunerRow, onClick: () -> Unit) {
    Row(Modifier.tap(onClick = onClick).settingsRow(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(if (r.m3u) AppIcons.Playlist else WebIcons.Tv)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SText(r.name, Modifier.weight(1f, fill = false), size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                r.online?.let { on -> NameTag(if (on) t("Online") else t("Not found"), dot = if (on) SeedyColors.Ok else SeedyColors.Danger, tint = 0.08f) }
            }
            SText(r.sub, size = 12.sp, lineHeight = 1.4f, color = SeedyColors.Text3)
        }
        SIcon(AppIcons.Chevron, size = 16.dp, color = SeedyColors.Text3)
    }
}

@Composable
private fun SourceLine(r: SourceRow, onClick: () -> Unit) {
    Row(Modifier.tap(onClick = onClick).settingsRow(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SText(r.name, Modifier.weight(1f, fill = false), size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                NameTag(r.kind, tint = 0.08f)
            }
            if (r.detail.isNotEmpty()) SText(r.detail, size = 12.sp, lineHeight = 1.4f, color = SeedyColors.Text3)
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Dot(toneColor(r.tone), 6.dp)
                SText(r.status, size = 12.sp, weight = 600, color = SeedyColors.Text2)
            }
        }
        SIcon(AppIcons.Chevron, size = 16.dp, color = SeedyColors.Text3)
    }
}

/** HDHomeRun tuners the server finds on its network; Add opens the new tuner's editor with what it found. */
@Composable
private fun FindTuners(configured: List<TunerHost>, actions: LiveTvAdminActions, onAdd: (TunerHost) -> Unit, onDismiss: () -> Unit) {
    var list by remember { mutableStateOf<List<TunerHost>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var run by remember { mutableStateOf(0) }
    LaunchedEffect(run) {
        list = null
        error = null
        actions.discover().onSuccess { list = it }.onFailure { list = emptyList(); error = it.message }
    }
    BottomSheet(onDismiss) {
        SheetHead(t("Tuners on your network"), t("HDHomeRun tuners on the same network as the server show up here."))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val l = list
            when {
                l == null -> SText(t("Looking for tuners…"), size = 13.sp, color = SeedyColors.Text3)
                l.isEmpty() -> SText(error ?: t("No tuners answered. Check that the tuner is on and on the same network, or add it by its address."), size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Text2)
                else -> for (x in l) {
                    val known = configured.any { sameTuner(it, x) }
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconTile(WebIcons.Tv)
                        RowLabel(
                            x.friendlyName?.takeIf { it.isNotEmpty() } ?: tunerTypeLabel(x.type),
                            listOf(hostOf(x.url), if (x.tunerCount > 0) tn(x.tunerCount, "{n} stream", "{n} streams") else "").filter { it.isNotEmpty() }.joinToString(" · "),
                            Modifier.weight(1f),
                        )
                        if (known) SText(t("Added"), size = 12.sp, color = SeedyColors.Text3)
                        else PillButton(t("Add"), { onDismiss(); onAdd(x) }, height = 36.dp)
                    }
                }
            }
        }
        SheetFoot {
            PillButton(t("Search again"), { run++ }, enabled = list != null)
            PillButton(t("Done"), onDismiss, style = PillStyle.Light)
        }
    }
}

/** For sub-pages: the line under the title ("M3U tuner · 214 channels · editing"). */
internal fun tunerSub(draft: TunerHost, isNew: Boolean, channels: Int?): String =
    listOf(tunerTypeLabel(draft.type), channels?.let { tn(it, "{n} channel", "{n} channels") }.orEmpty(), if (isNew) t("adding") else t("editing"))
        .filter { it.isNotEmpty() }.joinToString(" · ")

/** The tuner's display name for a sub-page title. */
internal fun tunerTitle(draft: TunerHost, isNew: Boolean): String = if (isNew) t("New tuner") else tunerName(draft)

/** A 40 px icon tile (tuners, guide sources). */
@Composable
private fun IconTile(icon: IconDef, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(11.dp)).background(SeedyColors.Surface), contentAlignment = Alignment.Center) {
        SIcon(icon, size = 20.dp, color = SeedyColors.Text)
    }
}
