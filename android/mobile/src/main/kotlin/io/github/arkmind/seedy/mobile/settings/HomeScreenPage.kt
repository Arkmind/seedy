package io.github.arkmind.seedy.mobile.settings

import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import io.github.arkmind.seedy.core.settings.LibraryInfo
import java.util.UUID

/** Choices for "Drop a show from Next up after" (days; 0 keeps every show). */
private val NextUpDays = listOf(7, 14, 30, 60, 90, 180, 365, 730, 0)

/**
 * Board "Android · Settings · Home screen": the rows (hold one, then drag it), the libraries (the same: their order is
 * the Libraries tab's, and turning one off hides it), and Next up. [dragging] shows a row picked up (screenshot
 * tests); [focus] "libraries" opens the page on them (the Libraries tab's "Order and hide libraries").
 */
@Composable
fun HomeScreenPage(state: SettingsState, actions: SettingsActions, nav: SettingsNav, dragging: String? = null, focus: String? = null) {
    val sheet = rememberSheet()
    val scroll = rememberScrollState()
    var librariesTop by remember { mutableStateOf<Float?>(null) }
    val topRoom = with(LocalDensity.current) { (WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp).toPx() }
    LaunchedEffect(focus, librariesTop != null) {
        val top = librariesTop
        if (focus == "libraries" && top != null) scroll.animateScrollTo((top - topRoom).toInt().coerceAtLeast(0))
    }
    SettingsPage(t("Home screen"), t("Which rows you see and in what order. Follows your profile to every device."), nav.back, overlay = { SheetHost(sheet) }, scroll = scroll) {
        if (state.seedy) {
            SectionTitle(t("Rows"))
            CssColumn(Modifier.padding(horizontal = 16.dp)) {
                val mine = state.prefs.rowOrder == "mine"
                Segmented(listOf(Choice(false, t("Order automatically")), Choice(true, t("My order"))), mine, actions::setRowOrder, height = 38.dp, size = 13)
                SText(
                    t("Press and hold a row, then drag it. Rows you turn off stay hidden."),
                    Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 10.dp).cssText(12, 1.45f), size = 12.sp, lineHeight = 1.45f, color = SeedyColors.Text3,
                )
                RowList(state, actions, dragging)
                SText(
                    t("Continue watching always comes right after the featured banner when it has something."),
                    Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp).cssText(12, 1.45f), size = 12.sp, lineHeight = 1.45f, color = SeedyColors.Text3,
                )
            }
        }

        val libraries = state.libraries.orEmpty()
        if (libraries.isNotEmpty()) {
            SectionTitle(t("Libraries on Home"), Modifier.onGloballyPositioned { librariesTop = it.positionInParent().y })
            LibraryList(state, actions, libraries)
            SectionFoot(t("Turned-off libraries also leave Libraries."))
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

/**
 * The libraries, in their order (the Libraries tab's and My media's): hold one, then drag it. Turning one off leaves it
 * out of Home and of the Libraries tab (Jellyfin hides what's left out of My media).
 */
@Composable
private fun LibraryList(state: SettingsState, actions: SettingsActions, libraries: List<LibraryInfo>) {
    val reorder = rememberReorder<UUID>()
    val shown = reorder.shown(libraries) { it.id }

    // Not clipped: the library being moved grows past the card's edges.
    CssColumn(Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(SeedyColors.Panel, RoundedCornerShape(18.dp))) {
        // Keyed, so a row keeps its gesture while it moves past the others.
        shown.forEachIndexed { i, l -> key(l.id) {
            val lifted = l.id == reorder.drag
            if (i > 0 && !lifted) RowLine()
            SwitchRow(
                l.name, state.libraryLine(l), state.onHome(l),
                modifier = Modifier.onSizeChanged { reorder.heights[l.id] = it.height }
                    .reorderLift(lifted) { reorder.offset }
                    .liftedCard(lifted)
                    .reorderDrag(reorder, l.id, { shown.map { it.id } }) { drop ->
                        if (drop.order != libraries.map { it.id }) actions.setLibraryOrder(drop.order)
                    },
            ) { actions.setLibraryOnHome(l.id, it) }
        } }
    }
}

/** The rows, in order: hold one (or drag its grip) to move it; in automatic order a moved row is pinned there. */
@Composable
private fun RowList(state: SettingsState, actions: SettingsActions, preview: String?) {
    val rows = state.rows()
    val reorder = rememberReorder<String>()
    val shown = reorder.shown(rows) { it.kind }

    fun end(drop: ReorderState.Drop<String>) {
        val list = drop.order.mapNotNull { k -> rows.firstOrNull { it.kind == k } }
        if (list.map { it.kind } == rows.map { it.kind }) return
        // In automatic order a moved row is pinned there.
        val auto = state.prefs.rowOrder != "mine"
        actions.setRows(list.map { HomeRowPref(it.kind, it.enabled, it.count, it.pinned || (auto && it.kind == drop.moved)) })
    }

    // Not clipped: the row being moved grows past the list's edges.
    Column(Modifier.fillMaxWidth().background(SeedyColors.Panel, RoundedCornerShape(18.dp))) {
        shown.forEachIndexed { i, r -> key(r.kind) {
            val lifted = r.kind == (reorder.drag ?: preview)
            if (i > 0 && !lifted) RowLine()
            val keys = { shown.map { it.kind } }
            RowItem(
                r, lifted,
                Modifier.onSizeChanged { reorder.heights[r.kind] = it.height }
                    .reorderLift(lifted) { if (reorder.drag != null) reorder.offset else -10.dp.toPx() }
                    .reorderDrag(reorder, r.kind, keys, onEnd = ::end),
                grip = Modifier.reorderDrag(reorder, r.kind, keys, longPress = false, onEnd = ::end),
            ) { on ->
                actions.setRows(rows.map { HomeRowPref(it.kind, if (it.kind == r.kind) on else it.enabled, it.count, it.pinned) })
            }
        } }
    }
}

@Composable
private fun RowItem(r: RowView, lifted: Boolean, modifier: Modifier, grip: Modifier, onToggle: (Boolean) -> Unit) {
    val accent = LocalAccent.current
    Row(
        modifier.fillMaxWidth().liftedCard(lifted)
            .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp).heightIn(min = 58.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(grip.size(38.dp, 44.dp), contentAlignment = Alignment.Center) {
            SIcon(SettingsIcons.Grip, size = 20.dp, color = if (lifted) SeedyColors.White else Faint)
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
