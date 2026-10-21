package io.github.arkmind.seedy.mobile.admin.system

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.admin.system.LastTone
import io.github.arkmind.seedy.core.admin.system.TaskRow
import io.github.arkmind.seedy.core.admin.system.TasksActions
import io.github.arkmind.seedy.core.admin.system.TasksState
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.admin.AdminIcons
import io.github.arkmind.seedy.mobile.admin.Bar
import io.github.arkmind.seedy.mobile.admin.ErrorCard
import io.github.arkmind.seedy.mobile.admin.RoundButton
import io.github.arkmind.seedy.mobile.admin.SkeletonCard
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.settings.Card
import io.github.arkmind.seedy.mobile.settings.FootNote
import io.github.arkmind.seedy.mobile.settings.RowLine
import io.github.arkmind.seedy.mobile.settings.SectionTitle
import io.github.arkmind.seedy.mobile.settings.SettingsPage
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/**
 * Board "Android · Server · Scheduled tasks": the tasks by group, each with what it does, when it runs and what it did
 * last; ▶ runs one now, ■ stops it; a tap opens its schedule. [openLogs] opens the Logs page on a failed run's day.
 */
@Composable
fun TasksScreen(state: TasksState, actions: TasksActions, onBack: () -> Unit, openLogs: (file: String, query: String) -> Unit) {
    var editing by remember { mutableStateOf<String?>(null) }
    SettingsPage(
        t("Scheduled tasks"),
        lede = state.lede,
        onBack = onBack,
        overlay = {
            val task = editing?.let { id -> state.groups?.flatMap { it.tasks }?.firstOrNull { it.id == id } }
            if (task != null) ScheduleSheet(task, state.savingSchedule, onSave = { triggers -> actions.saveTriggers(task.id, triggers) { ok -> if (ok) editing = null } }, onDismiss = { editing = null })
            Toast(state.message, actions::messageShown, Modifier.align(Alignment.BottomCenter))
        },
    ) {
        val groups = state.groups
        when {
            groups == null && state.error != null -> {
                Box(Modifier.padding(top = 22.dp)) { ErrorCard(state.error!!, actions::retry) }
            }
            groups == null -> for (n in listOf(4, 3, 2)) {
                SectionTitle(" ")
                SkeletonCard(n)
            }
            else -> for (g in groups) {
                SectionTitle(g.label)
                Card(Modifier.padding(horizontal = 16.dp)) {
                    g.tasks.forEachIndexed { i, task ->
                        if (i > 0) RowLine()
                        TaskLine(task, onSchedule = { editing = task.id }, onRun = { actions.run(task.id) }, onStop = { actions.stop(task.id) }, onLogs = openLogs)
                    }
                }
            }
        }
        FootNote(t("Tasks keep running when you close the app"))
    }
}

@Composable
private fun TaskLine(task: TaskRow, onSchedule: () -> Unit, onRun: () -> Unit, onStop: () -> Unit, onLogs: (String, String) -> Unit) {
    val accent = LocalAccent.current
    Column(Modifier.fillMaxWidth().tap(onClick = onSchedule).padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SText(task.name, size = 14.sp, weight = 700, color = SeedyColors.White)
                SText(task.desc, size = 12.sp, color = SeedyColors.Text3)
                WhenLine(task, accent, onLogs)
            }
            if (task.running) {
                RoundButton(AdminIcons.Stop, onStop, light = true, iconSize = 14.dp, enabled = !task.busy && !task.cancelling)
            } else {
                RoundButton(AdminIcons.Run, onRun, enabled = !task.busy)
            }
        }
        if (task.running) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val p = task.progress
                if (p != null) Bar(p, Modifier.weight(1f)) else Indeterminate(Modifier.weight(1f), accent)
                if (task.pct.isNotEmpty()) SText(task.pct, size = 12.sp, weight = 700, color = accent, maxLines = 1)
            }
        }
    }
}

/** "Every 12 hours · Running since 20:41": the schedule in grey, the last run in its colour ("see logs" opens the log). */
@Composable
private fun WhenLine(task: TaskRow, accent: Color, onLogs: (String, String) -> Unit) {
    val tone = when (task.tone) {
        LastTone.Failed -> SeedyColors.Danger
        LastTone.Running -> accent
        LastTone.Normal -> SeedyColors.Text2
    }
    val text = buildAnnotatedString {
        append(task.schedule)
        append(" · ")
        withStyle(SpanStyle(color = tone)) {
            append(task.last)
            val file = task.logFile
            if (task.tone == LastTone.Failed) {
                append(" · ")
                withLink(LinkAnnotation.Clickable("logs") { onLogs(file.orEmpty(), task.logQuery.orEmpty()) }) { append(t("see logs")) }
            }
        }
    }
    BasicText(text, style = BaseText.copy(fontSize = 12.sp, color = SeedyColors.Text2))
}

/** A running task that doesn't say how far it is: a segment sliding along the track. */
@Composable
private fun Indeterminate(modifier: Modifier, color: Color) {
    val x by rememberInfiniteTransition(label = "task").animateFloat(-0.3f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "slide")
    BoxWithConstraints(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(SeedyColors.Surface2)) {
        Box(Modifier.offset(x = maxWidth * x).width(maxWidth * 0.3f).height(4.dp).background(color))
    }
}
