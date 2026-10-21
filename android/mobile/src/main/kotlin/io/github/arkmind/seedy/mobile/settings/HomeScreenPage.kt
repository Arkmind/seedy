package io.github.arkmind.seedy.mobile.settings

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import io.github.arkmind.seedy.ui.components.boxShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.arkmind.seedy.core.i18n.formatNumber
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.seedy.HomeRowPref
import io.github.arkmind.seedy.core.settings.Choice
import io.github.arkmind.seedy.core.settings.RowView
import io.github.arkmind.seedy.core.settings.SettingsActions
import io.github.arkmind.seedy.core.settings.SettingsState
import io.github.arkmind.seedy.core.settings.SettingsState.Companion.Keys
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.SettingsIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

private val Dragged = Color(0xFF262626)

/** Choices for "Drop a show from Next up after" (days; 0 keeps every show). */
private val NextUpDays = listOf(7, 14, 30, 60, 90, 180, 365, 730, 0)

/**
 * Board "Android · Settings · Home screen": the rows (hold one, then drag it), the libraries Home draws from, and Next
 * up. [dragging] shows a row picked up (screenshot tests).
 */
@Composable
fun HomeScreenPage(state: SettingsState, actions: SettingsActions, nav: SettingsNav, dragging: String? = null) {
    val sheet = rememberSheet()
    SettingsPage(t("Home screen"), t("Which rows you see and in what order. Follows your profile to every device."), nav.back, overlay = { SheetHost(sheet) }) {
        if (state.seedy) {
            SectionTitle(t("Rows"))
            Column(Modifier.padding(horizontal = 16.dp)) {
                val mine = state.prefs.rowOrder == "mine"
                Segmented(listOf(Choice(false, t("Order automatically")), Choice(true, t("My order"))), mine, actions::setRowOrder, height = 38.dp, size = 13)
                SText(
                    t("Press and hold a row, then drag it. Rows you turn off stay hidden."),
                    Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 10.dp).cssBox(12, 1.45f, up = true), size = 12.sp, lineHeight = 1.45f, color = SeedyColors.Text3,
                )
                RowList(state, actions, dragging)
                SText(
                    t("Continue watching always comes right after the featured banner when it has something."),
                    Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp).cssBox(12, 1.45f, up = true), size = 12.sp, lineHeight = 1.45f, color = SeedyColors.Text3,
                )
            }
        }

        val libraries = state.libraries.orEmpty()
        if (libraries.isNotEmpty()) {
            Section(t("Libraries on Home"), t("Turned-off libraries still show in Libraries.")) {
                for (l in libraries) row { SwitchRow(l.name, state.libraryLine(l), state.onHome(l)) { actions.setLibraryOnHome(l.id, it) } }
            }
        }

        Section(t("Next up and Continue watching")) {
            if (state.seedy) {
                row {
                    val days = state.customInt(Keys.NextUpDays, 365)
                    val options = (NextUpDays + days).distinct().sortedWith(compareBy { if (it == 0) Int.MAX_VALUE else it }).map { Choice(it, daysLabel(it)) }
                    PickRow(t("Drop a show from Next up after"), t("Days since you last watched it"), daysLabel(days)) {
                        sheet.open(t("Drop a show from Next up after"), options, days) { actions.setCustom(Keys.NextUpDays, JsonPrimitive(it)) }
                    }
                }
                row {
                    SwitchRow(t("Include shows I’m rewatching"), t("Show the next episode even if you’ve seen it"), state.customBool(Keys.Rewatching, false)) {
                        actions.setCustom(Keys.Rewatching, JsonPrimitive(it))
                    }
                }
                row {
                    SwitchRow(t("Use episode pictures"), t("Instead of the show’s artwork"), state.customBool(Keys.EpisodeImages, true)) {
                        actions.setCustom(Keys.EpisodeImages, if (it) JsonNull else JsonPrimitive(false))
                    }
                }
            }
            row {
                SwitchRow(t("Hide what I’ve watched from Latest"), t("Only new-to-you titles in Latest rows"), state.config?.hidePlayedInLatest != false) { on ->
                    actions.setConfig { it.copy(hidePlayedInLatest = on) }
                }
            }
        }
        SaveLine(state.saving, state.error)
    }
}

private fun daysLabel(n: Int): String = if (n == 0) t("Never") else t("{n} days", "n" to formatNumber(n))

/** The rows, in order: hold one (or drag its grip) to move it; in automatic order a moved row is pinned there. */
@Composable
private fun RowList(state: SettingsState, actions: SettingsActions, preview: String?) {
    val rows = state.rows()
    var order by remember { mutableStateOf<List<String>?>(null) }
    var drag by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Int>() }
    val shown = order?.mapNotNull { k -> rows.firstOrNull { it.kind == k } }?.takeIf { it.size == rows.size } ?: rows

    fun save(list: List<RowView>, moved: String?) {
        val auto = state.prefs.rowOrder != "mine"
        actions.setRows(list.map { HomeRowPref(it.kind, it.enabled, it.count, it.pinned || (auto && it.kind == moved)) })
    }

    fun start(kind: String) {
        order = shown.map { it.kind }
        drag = kind
        offset = 0f
    }

    fun move(dy: Float) {
        val kind = drag ?: return
        val list = order?.toMutableList() ?: return
        offset += dy
        var i = list.indexOf(kind)
        // Past half of the neighbour: swap with it.
        while (i < list.lastIndex && offset > (heights[list[i + 1]] ?: 0) / 2f) {
            offset -= heights[list[i + 1]] ?: 0
            list.add(i + 1, list.removeAt(i)); i++
        }
        while (i > 0 && offset < -(heights[list[i - 1]] ?: 0) / 2f) {
            offset += heights[list[i - 1]] ?: 0
            list.add(i - 1, list.removeAt(i)); i--
        }
        order = list
    }

    fun end() {
        val kind = drag ?: return
        val list = order?.mapNotNull { k -> rows.firstOrNull { it.kind == k } }
        drag = null
        offset = 0f
        if (list != null && list.map { it.kind } != rows.map { it.kind }) save(list, kind)
        order = null
    }

    // Not clipped: the row being moved grows past the list's edges.
    Column(Modifier.fillMaxWidth().background(SeedyColors.Panel, RoundedCornerShape(18.dp))) {
        shown.forEachIndexed { i, r ->
            val lifted = r.kind == (drag ?: preview)
            if (i > 0 && !lifted) Box(Modifier.fillMaxWidth().height(1.dp).background(SeedyColors.Line))
            RowItem(
                r, lifted,
                Modifier.onSizeChanged { heights[r.kind] = it.height }
                    .zIndex(if (lifted) 1f else 0f)
                    .graphicsLayer {
                        if (lifted) {
                            translationY = if (drag != null) offset else -10.dp.toPx()
                            scaleX = 1.02f
                            scaleY = 1.02f
                        }
                    }
                    .pointerInput(r.kind) {
                        detectDragGesturesAfterLongPress(onDragStart = { start(r.kind) }, onDragEnd = ::end, onDragCancel = ::end) { c, d -> c.consume(); move(d.y) }
                    },
                grip = Modifier.pointerInput(r.kind) {
                    detectDragGestures(onDragStart = { start(r.kind) }, onDragEnd = ::end, onDragCancel = ::end) { c, d -> c.consume(); move(d.y) }
                },
            ) { on ->
                actions.setRows(rows.map { HomeRowPref(it.kind, if (it.kind == r.kind) on else it.enabled, it.count, it.pinned) })
            }
        }
    }
}

@Composable
private fun RowItem(r: RowView, lifted: Boolean, modifier: Modifier, grip: Modifier, onToggle: (Boolean) -> Unit) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.fillMaxWidth()
            .then(if (lifted) Modifier.boxShadow(Color.Black.copy(alpha = 0.6f), 30.dp, 14.dp, 16.dp).clip(shape).background(Dragged).border(1.dp, Color.White.copy(alpha = 0.08f), shape) else Modifier)
            .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp).heightIn(min = 58.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(grip.size(38.dp, 44.dp), contentAlignment = Alignment.Center) {
            SIcon(SettingsIcons.Grip, size = 20.dp, color = if (lifted) SeedyColors.White else Quiet)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SText(r.name, Modifier.weight(1f, fill = false), size = 14.sp, weight = 700, color = if (r.enabled) SeedyColors.White else SeedyColors.Text3)
                if (r.pinned) SIcon(SettingsIcons.Pin, size = 14.dp, color = accent)
                r.count?.let { n ->
                    Box(Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.1f)).padding(horizontal = 7.dp)) {
                        SText(formatNumber(n), size = 11.sp, weight = 800, color = SeedyColors.TextHero)
                    }
                }
            }
            SText(r.help, size = 12.sp, color = SeedyColors.Text3)
        }
        BoardSwitch(r.enabled, onToggle)
    }
}
