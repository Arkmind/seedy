package io.github.arkmind.seedy.mobile.admin.system

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.admin.system.LogFileRow
import io.github.arkmind.seedy.core.admin.system.LogLevelFilter
import io.github.arkmind.seedy.core.admin.system.LogLine
import io.github.arkmind.seedy.core.admin.system.LogViewerState
import io.github.arkmind.seedy.core.admin.system.LogsActions
import io.github.arkmind.seedy.core.admin.system.LogsState
import io.github.arkmind.seedy.core.admin.system.isErrorLevel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.settings.Choice
import io.github.arkmind.seedy.mobile.admin.AdminIcons
import io.github.arkmind.seedy.mobile.settings.Card
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.admin.PillRow
import io.github.arkmind.seedy.mobile.settings.RowLine
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.settings.PageHeader
import io.github.arkmind.seedy.mobile.settings.Segmented
import io.github.arkmind.seedy.mobile.settings.SettingsPage
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.mobile.admin.ErrorCard
import io.github.arkmind.seedy.mobile.admin.EmptyCard
import io.github.arkmind.seedy.mobile.admin.SkeletonCard
import io.github.arkmind.seedy.mobile.admin.LivePill
import io.github.arkmind.seedy.mobile.admin.StatusLine
import io.github.arkmind.seedy.mobile.admin.CardNote
import io.github.arkmind.seedy.mobile.settings.FootNote
import io.github.arkmind.seedy.mobile.settings.cssText
import io.github.arkmind.seedy.mobile.admin.Mono

/*
 * Logs, built from the phone boards' kit with the web board "Server · Logs": the files newest first, then a file in
 * the viewer (search, level, follow, copy, download), opened over the list.
 */

@Composable
fun LogsScreen(state: LogsState, actions: LogsActions, onBack: () -> Unit, copy: () -> Unit, download: (String) -> Unit) {
    val viewer = state.viewer
    if (viewer != null) {
        BackHandler(onBack = actions::close)
        LogViewer(viewer, actions, onBack = actions::close, copy = copy, download = { download(viewer.name) }, message = state.message)
        return
    }
    SettingsPage(
        t("Logs"),
        lede = state.lede,
        onBack = onBack,
        overlay = { Toast(state.message, actions::messageShown, Modifier.align(Alignment.BottomCenter)) },
    ) {
        val files = state.files
        Box(Modifier.padding(top = 14.dp)) {
            when {
                files == null && state.error != null -> ErrorCard(state.error!!, actions::retry)
                files == null -> SkeletonCard(4)
                files.isEmpty() -> EmptyCard(t("No log files"), t("The server hasn’t written any logs yet."))
                else -> Card(Modifier.padding(horizontal = 16.dp)) {
                    files.forEachIndexed { i, f ->
                        if (i > 0) RowLine()
                        FileRow(f) { actions.open(f.name) }
                    }
                }
            }
        }
        if (state.hidden > 0) FootNote(t("Show {n} more", "n" to state.hidden), onClick = actions::showAll)
        else Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun FileRow(f: LogFileRow, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().tap(onClick = onClick).padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SText(f.label, Modifier.weight(1f, fill = false), size = 14.sp, weight = 700, color = SeedyColors.White)
                if (f.writing) Writing()
            }
            BasicText(f.sub, style = Mono.copy(fontSize = 12.sp, color = SeedyColors.Text3))
        }
        SIcon(AppIcons.Chevron, size = 16.dp, color = SeedyColors.Text3)
    }
}

/** "● Writing": the file is still being written. */
@Composable
private fun Writing() = StatusLine(t("Writing"), SeedyColors.Ok, size = 11.sp, gap = 5.dp)

/** The viewer: the file's lines in a dark panel filling the page under the search and its buttons. */
@Composable
fun LogViewer(v: LogViewerState, actions: LogsActions, onBack: () -> Unit, copy: () -> Unit, download: () -> Unit, message: String?) {
    val accent = LocalAccent.current
    val list = rememberLazyListState()
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Keep the newest lines in view while following.
    LaunchedEffect(v.lines.size, v.following) {
        if (v.following && v.lines.isNotEmpty()) list.scrollToItem(v.lines.lastIndex)
    }
    // A jump to a match brings that line into view, a few lines down from the top.
    LaunchedEffect(v.jump) {
        val to = v.scrollTo
        if (v.jump > 0 && to != null && to >= 0) list.scrollToItem((to - 3).coerceAtLeast(0))
    }
    // Dragging back up stops following (the page's own scrolls to the end don't).
    val atEnd by remember { derivedStateOf { val info = list.layoutInfo; info.visibleItemsInfo.lastOrNull()?.index == info.totalItemsCount - 1 || info.totalItemsCount == 0 } }
    val dragged by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(list) {
        snapshotFlow { dragged && !atEnd }.collect { up -> if (up) actions.setFollowing(false) }
    }
    Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            PageHeader(v.label, onBack)
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp).cssText(13, 1.5f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BasicText(v.sub, Modifier.weight(1f, fill = false), style = Mono.copy(fontSize = 12.sp, color = SeedyColors.Text2), maxLines = 1)
                if (v.live) Writing()
            }
            SearchBox(
                v.term, actions::setTerm, t("Search this log"),
                Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
                count = v.count,
                onSubmit = { actions.jump(1) },
                trailing = if (v.matches == 0) null else ({
                    Row {
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).tap { actions.jump(-1) }, contentAlignment = Alignment.Center) {
                            SIcon(AdminIcons.Caret, Modifier.rotate(180f), size = 16.dp, color = SeedyColors.Text)
                        }
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).tap { actions.jump(1) }, contentAlignment = Alignment.Center) {
                            SIcon(AdminIcons.Caret, size = 16.dp, color = SeedyColors.Text)
                        }
                    }
                }),
            )
            Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
                if (v.structured) Segmented(LogLevelFilter.entries.map { Choice(it, t(it.label)) }, v.level, actions::setLevel, height = 34.dp, size = 13)
            }
            PillRow(top = 10.dp) {
                LivePill(v.following && v.live, { actions.setFollowing(!v.following) }, onLabel = t("Following"), offLabel = t("Follow"), enabled = v.live)
                PillButton(t("Copy"), copy, enabled = !v.loading)
                PillButton(t("Download"), download, enabled = !v.loading)
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = max(16.dp, nav + 8.dp)).clip(RoundedCornerShape(18.dp)).background(SeedyColors.Panel)) {
                when {
                    v.error != null -> CardNote(v.error!!, SeedyColors.Danger, t("Try again"), actions::retry)
                    v.loading -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (w in listOf(0.72f, 0.58f, 0.86f, 0.64f, 0.5f, 0.78f)) Box(Modifier.fillMaxWidth(w).height(10.dp).clip(RoundedCornerShape(5.dp)).background(SeedyColors.Surface))
                    }
                    v.empty != null -> SText(v.empty!!, Modifier.padding(16.dp), size = 13.sp, color = SeedyColors.Text3)
                    else -> LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(v.lines, key = { it.i }) { line -> Line(line, accent) }
                    }
                }
            }
        }
        Toast(message, actions::messageShown, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun Line(e: LogLine, accent: Color) {
    val err = isErrorLevel(e.level)
    val bg = when {
        e.current -> accent.copy(alpha = 0.22f)
        e.hit -> accent.copy(alpha = 0.10f)
        err -> SeedyColors.Danger.copy(alpha = 0.06f)
        else -> Color.Transparent
    }
    Row(Modifier.fillMaxWidth().background(bg).padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (e.level.isEmpty()) {
            BasicText(e.text.ifEmpty { " " }, style = Mono.copy(fontSize = 11.sp, lineHeight = 17.sp, color = SeedyColors.TextHero))
            return@Row
        }
        BasicText(e.time, style = Mono.copy(fontSize = 11.sp, lineHeight = 17.sp, color = Color(0xFF6F6F6F)), maxLines = 1)
        BasicText(
            e.level, Modifier.width(24.dp),
            style = Mono.copy(
                fontSize = 11.sp, lineHeight = 17.sp, fontWeight = FontWeight(700),
                color = when {
                    err -> SeedyColors.Danger
                    e.level == "WRN" -> SeedyColors.Pending
                    else -> SeedyColors.Text3
                },
            ),
            maxLines = 1,
        )
        BasicText(e.text, Modifier.weight(1f), style = Mono.copy(fontSize = 11.sp, lineHeight = 17.sp, color = if (err) Color(0xFFFFD0D0) else SeedyColors.TextHero))
    }
}
