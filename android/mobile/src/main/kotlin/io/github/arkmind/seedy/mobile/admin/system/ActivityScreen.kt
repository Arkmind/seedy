package io.github.arkmind.seedy.mobile.admin.system

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.admin.system.ActivityActions
import io.github.arkmind.seedy.core.admin.system.ActivityLevel
import io.github.arkmind.seedy.core.admin.system.ActivityRow
import io.github.arkmind.seedy.core.admin.system.ActivityState
import io.github.arkmind.seedy.core.admin.system.ActivityTile
import io.github.arkmind.seedy.core.admin.system.LevelFilter
import io.github.arkmind.seedy.core.admin.system.TypeFilter
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.settings.Choice
import io.github.arkmind.seedy.mobile.admin.ConfirmSheet
import io.github.arkmind.seedy.mobile.admin.EmptyCard
import io.github.arkmind.seedy.mobile.admin.ErrorCard
import io.github.arkmind.seedy.mobile.admin.LivePill
import io.github.arkmind.seedy.mobile.admin.MenuPill
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.admin.PillStyle
import io.github.arkmind.seedy.mobile.admin.SkeletonCard
import io.github.arkmind.seedy.mobile.admin.StatTile
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.settings.Card
import io.github.arkmind.seedy.mobile.settings.CssColumn
import io.github.arkmind.seedy.mobile.settings.Faint
import io.github.arkmind.seedy.mobile.settings.FootNote
import io.github.arkmind.seedy.mobile.settings.RowLine
import io.github.arkmind.seedy.mobile.settings.SectionTitle
import io.github.arkmind.seedy.mobile.settings.SettingsPage
import io.github.arkmind.seedy.mobile.settings.SheetHost
import io.github.arkmind.seedy.mobile.settings.cssExcess
import io.github.arkmind.seedy.mobile.settings.rememberSheet
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/** The board's dots: grey for information, green when something finished well, amber for warnings, red for errors. */
private fun levelDot(l: ActivityLevel): Color = when (l) {
    ActivityLevel.Error -> SeedyColors.Danger
    ActivityLevel.Warn -> SeedyColors.Pending
    ActivityLevel.Ok -> SeedyColors.Ok
    ActivityLevel.Info -> Faint
}

private val ErrorTint = Color(0x12FF8A8A)

/** What [lines] lines of [lineHeight] px gain when Compose rounds their box up to whole pixels. */
private fun lineExcess(lines: Int, lineHeight: Float): Float = if (lines <= 0) 0f else kotlin.math.ceil(lines * lineHeight) - lines * lineHeight

/**
 * Board "Android · Server · Activity log": today in four tiles, who and which level to show, Live, then the log by
 * day; failed sign-ins from outside offer Block address, locked-out people Re-enable.
 */
@Composable
fun ActivityScreen(state: ActivityState, actions: ActivityActions, onBack: () -> Unit) {
    val sheet = rememberSheet()
    var confirm by remember { mutableStateOf<ActivityRow?>(null) }
    SettingsPage(
        t("Activity log"),
        lede = state.lede,
        onBack = onBack,
        overlay = {
            SheetHost(sheet)
            confirm?.let { r ->
                val a = r.action!!
                ConfirmSheet(a.confirmTitle, a.confirmText, a.label, { actions.act(r.key) }, { confirm = null }, danger = a.danger)
            }
            Toast(state.message, actions::messageShown, Modifier.align(Alignment.BottomCenter))
        },
    ) {
        Tiles(state.tiles, actions)
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MenuPill(state.personLabel, { sheet.open(t("Person"), state.people, state.person, actions::setPerson) })
            MenuPill(t(state.level.label), { sheet.open(t("Level"), LevelFilter.entries.map { Choice(it, t(it.label)) }, state.level, actions::setLevel) })
            if (state.type != TypeFilter.All) {
                PillButton(t(state.type.label), { actions.setType(TypeFilter.All) }, trailing = { SIcon(WebIcons.Close, size = 13.dp, color = SeedyColors.White, stroke = 2.4f) })
            }
            Spacer(Modifier.weight(1f))
            LivePill(state.live, { actions.setLive(!state.live) })
        }

        val days = state.days
        when {
            days == null && state.error != null -> Box(Modifier.padding(top = 22.dp)) { ErrorCard(state.error!!, actions::retry) }
            days == null -> {
                SectionTitle(" ")
                SkeletonCard(5)
            }
            days.isEmpty() -> Box(Modifier.padding(top = 22.dp)) { EmptyCard(state.emptyTitle.orEmpty(), state.emptyText) }
            else -> for (d in days) {
                SectionTitle(d.label)
                Card(Modifier.padding(horizontal = 16.dp)) {
                    CssColumn {
                        d.rows.forEachIndexed { i, r ->
                            if (i > 0) RowLine()
                            EventRow(r) { confirm = r }
                        }
                    }
                }
            }
        }
        if (state.canLoadMore) FootNote(if (state.loadingMore) t("Loading…") else t("Show earlier"), onClick = if (state.loadingMore) null else actions::loadMore)
        else Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Tiles(tiles: List<ActivityTile>?, actions: ActivityActions) {
    val list = tiles ?: List(4) { ActivityTile("…", "") }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (pair in list.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (tile in pair) {
                    val mod = Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).then(if (tile.alert) Modifier.tap(onClick = actions::toggleFailed) else Modifier)
                    StatTile(tile.n, tile.label, mod, ink = if (tile.alert) SeedyColors.Danger else SeedyColors.White, size = 24.sp)
                }
            }
        }
    }
}

@Composable
private fun EventRow(r: ActivityRow, onAction: () -> Unit) {
    val accent = LocalAccent.current
    val bg by animateColorAsState(
        when {
            r.fresh -> accent.copy(alpha = 0.08f)
            r.level == ActivityLevel.Error -> ErrorTint
            else -> Color.Transparent
        },
        tween(600), label = "row",
    )
    var lines by remember { mutableIntStateOf(1) }
    // The detail's 16.8 px lines come out 17 px each; the card stacks rows by their CSS heights.
    val line = 1.4f * 12f * LocalDensity.current.density
    Row(Modifier.cssExcess(lineExcess(lines, line)).fillMaxWidth().background(bg).padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top = 5.dp).size(10.dp).background(levelDot(r.level), CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SText(r.title, Modifier.weight(1f), size = 14.sp, weight = 700, color = SeedyColors.White)
                SText(r.time, size = 12.sp, weight = 600, color = SeedyColors.Text3, maxLines = 1)
            }
            BasicText(
                listOf(r.tag, r.detail).filter { it.isNotEmpty() }.joinToString(" · "),
                // The boards' browser sets a 1.4 line a pixel higher than Compose does.
                Modifier.offset(y = (-1).dp),
                style = BaseText.copy(fontSize = 12.sp, lineHeight = 1.4.em, color = SeedyColors.Text3),
                onTextLayout = { lines = it.lineCount },
            )
            r.action?.let { a ->
                Box(Modifier.padding(top = 6.dp)) { PillButton(a.label, onAction, style = PillStyle.Light) }
            }
        }
    }
}
