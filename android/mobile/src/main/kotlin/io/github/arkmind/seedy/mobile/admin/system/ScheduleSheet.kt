package io.github.arkmind.seedy.mobile.admin.system

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.admin.TaskTrigger
import io.github.arkmind.seedy.core.admin.WeekdaysEn
import io.github.arkmind.seedy.core.admin.intervalText
import io.github.arkmind.seedy.core.admin.minutesOf
import io.github.arkmind.seedy.core.admin.runLength
import io.github.arkmind.seedy.core.admin.system.TaskRow
import io.github.arkmind.seedy.core.admin.ticksOfMinutes
import io.github.arkmind.seedy.core.admin.triggerText
import io.github.arkmind.seedy.core.admin.weekdayLabel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.settings.Choice
import io.github.arkmind.seedy.mobile.admin.AdminIcons
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.admin.PillStyle
import io.github.arkmind.seedy.mobile.browse.BottomSheet
import io.github.arkmind.seedy.mobile.parts.HeaderButton
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.settings.Segmented
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/*
 * A task's schedule (web/src/screens/server/ScheduleSheet.tsx): the times it runs on its own, each removable, and a
 * new one to add (daily at a time, weekly on a day, every so often, or at startup). Lists open in the sheet itself.
 */

private enum class Kind { Daily, Weekly, Interval, Startup }

private val IntervalMinutes = listOf(15L, 30L, 45L, 60L, 120L, 180L, 240L, 360L, 480L, 720L, 1440L)

private fun clockText(m: Long) = "%02d:%02d".format(m / 60, m % 60)

private fun same(a: TaskTrigger, b: TaskTrigger) =
    a.type == b.type && (a.timeOfDayTicks ?: 0) == (b.timeOfDayTicks ?: 0) && (a.intervalTicks ?: 0) == (b.intervalTicks ?: 0) && (a.dayOfWeek ?: "") == (b.dayOfWeek ?: "")

@Composable
fun ScheduleSheet(task: TaskRow, saving: Boolean, onSave: (List<TaskTrigger>) -> Unit, onDismiss: () -> Unit) {
    var draft by remember(task.id) { mutableStateOf(task.triggers) }
    var kind by remember(task.id) { mutableStateOf(Kind.Daily) }
    var time by remember(task.id) { mutableStateOf(180L) }
    var day by remember(task.id) { mutableStateOf("Sunday") }
    var every by remember(task.id) { mutableStateOf(1440L) }
    var note by remember(task.id) { mutableStateOf("") }
    var picking by remember(task.id) { mutableStateOf<String?>(null) }

    val changed = draft.size != task.triggers.size || draft.indices.any { !same(draft[it], task.triggers[it]) }

    fun candidate(): TaskTrigger = when (kind) {
        Kind.Daily -> TaskTrigger("DailyTrigger", timeOfDayTicks = ticksOfMinutes(time))
        Kind.Weekly -> TaskTrigger("WeeklyTrigger", timeOfDayTicks = ticksOfMinutes(time), dayOfWeek = day)
        Kind.Interval -> TaskTrigger("IntervalTrigger", intervalTicks = ticksOfMinutes(every))
        Kind.Startup -> TaskTrigger("StartupTrigger")
    }

    BottomSheet(onDismiss = onDismiss) {
        when (picking) {
            "day" -> PickPage(t("Day"), WeekdaysEn.map { Choice(it, weekdayLabel(it)) }, day, { picking = null }) { day = it }
            "time" -> PickPage(t("Time"), (0 until 48).map { Choice(it * 30L, clockText(it * 30L)) }, time, { picking = null }) { time = it }
            "interval" -> PickPage(t("Interval"), IntervalMinutes.map { Choice(it, intervalText(ticksOfMinutes(it))) }, every, { picking = null }) { every = it }
            else -> Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 28.dp).imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SText(task.name, Modifier.weight(1f), size = 17.sp, weight = 800, color = SeedyColors.White)
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).tap(onClick = onDismiss), contentAlignment = Alignment.Center) {
                        SIcon(WebIcons.Close, size = 18.dp, color = SeedyColors.Text2)
                    }
                }
                SText(t("When it runs on its own. You can always start it from the list with Run."), size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Text2)

                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SeedyColors.Row)) {
                    if (draft.isEmpty()) {
                        SText(t("Not scheduled. It only runs when you start it."), Modifier.padding(horizontal = 14.dp, vertical = 14.dp), size = 13.sp, color = SeedyColors.Text3)
                    }
                    draft.forEachIndexed { i, tr ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(SeedyColors.Surface))
                        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                SText(triggerText(tr), size = 14.sp, weight = 700, color = SeedyColors.White)
                                val max = tr.maxRuntimeTicks
                                if (max != null && max > 0) SText(t("Stops if it runs longer than {time}", "time" to runLength(minutesOf(max) * 60_000)), size = 12.sp, color = SeedyColors.Text3)
                            }
                            Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).tap { draft = draft.filterIndexed { j, _ -> j != i } }, contentAlignment = Alignment.Center) {
                                SIcon(WebIcons.Close, size = 16.dp, color = SeedyColors.Text2)
                            }
                        }
                    }
                }

                SText(t("Add a time"), Modifier.padding(top = 4.dp), size = 13.sp, weight = 700, color = SeedyColors.Text)
                Segmented(
                    listOf(Choice(Kind.Daily, t("Daily")), Choice(Kind.Weekly, t("Weekly")), Choice(Kind.Interval, t("Every…")), Choice(Kind.Startup, t("On startup"))),
                    kind, { kind = it; note = "" },
                )
                if (kind == Kind.Weekly) PickLine(t("Day"), weekdayLabel(day)) { picking = "day" }
                if (kind == Kind.Daily || kind == Kind.Weekly) PickLine(t("Time"), clockText(time)) { picking = "time" }
                if (kind == Kind.Interval) PickLine(t("Interval"), intervalText(ticksOfMinutes(every))) { picking = "interval" }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) {
                        when {
                            note.isNotEmpty() -> SText(note, size = 12.sp, lineHeight = 1.4f, color = SeedyColors.Danger)
                            kind == Kind.Startup -> SText(t("Each time the server starts."), size = 13.sp, color = SeedyColors.Text2)
                        }
                    }
                    PillButton(t("Add"), {
                        val next = candidate()
                        if (draft.any { same(it, next) }) note = t("It already runs {schedule}.", "schedule" to triggerText(next).lowercase())
                        else {
                            note = ""
                            draft = draft + next
                        }
                    }, leading = { SIcon(AdminIcons.Plus, size = 14.dp, color = SeedyColors.White) })
                }

                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    PillButton(t("Cancel"), onDismiss)
                    PillButton(if (saving) t("Saving…") else t("Save schedule"), { onSave(draft) }, style = PillStyle.Light, enabled = changed && !saving)
                }
            }
        }
    }
}

/** A value in the sheet that opens its list ("Time  03:00 ›"). */
@Composable
private fun PickLine(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(SeedyColors.Row).tap(onClick = onClick).padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SText(label, Modifier.weight(1f), size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
        SText(value, size = 13.sp, weight = 600, color = SeedyColors.TextHero, maxLines = 1)
        SIcon(AppIcons.Chevron, size = 16.dp, color = SeedyColors.Text3)
    }
}

/** A list in place of the sheet's form, with a back arrow to it. */
@Composable
private fun <T> ColumnScope.PickPage(title: String, options: List<Choice<T>>, value: T, onBack: () -> Unit, onPick: (T) -> Unit) {
    val accent = LocalAccent.current
    Row(Modifier.padding(start = 8.dp, end = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        HeaderButton(WebIcons.ArrowLeft, onBack, stroke = 2.2f)
        SText(title, Modifier.padding(start = 4.dp), size = 17.sp, weight = 800, color = SeedyColors.White)
    }
    val start = options.indexOfFirst { it.value == value }.coerceAtLeast(0)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (start - 3).coerceAtLeast(0))
    LazyColumn(Modifier.weight(1f, fill = false).padding(start = 12.dp, end = 12.dp, bottom = 24.dp), state = list) {
        itemsIndexed(options) { _, o ->
            val on = o.value == value
            Row(
                Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(12.dp)).background(if (on) SeedyColors.Surface else Color.Transparent)
                    .tap(role = Role.RadioButton) { onPick(o.value); onBack() }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SText(o.label, Modifier.weight(1f), size = 15.sp, weight = if (on) 800 else 600, color = if (on) SeedyColors.White else SeedyColors.Text)
                if (on) SIcon(WebIcons.Check, size = 18.dp, color = accent, stroke = 2.6f)
            }
        }
    }
}
