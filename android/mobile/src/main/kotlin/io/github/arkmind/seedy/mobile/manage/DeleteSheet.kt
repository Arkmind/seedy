package io.github.arkmind.seedy.mobile.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.manage.DeleteActions
import io.github.arkmind.seedy.core.manage.DeleteState
import io.github.arkmind.seedy.core.manage.DeleteViewModel
import io.github.arkmind.seedy.mobile.admin.BoardField
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession

/** Delete (board ItemDelete) for [ids]; [onDeleted] gets what went and what to say, then the sheet closes. */
@Composable
fun DeleteSheet(ids: List<String>, onDismiss: () -> Unit, onDeleted: (List<String>, String) -> Unit, continuing: Boolean = false) {
    val session = currentSession()
    val vm = viewModel(key = "delete:" + ids.joinToString(",")) { DeleteViewModel(session, ids, continuing) }
    val state by vm.state.collectAsStateWithLifecycle()
    DeleteContent(state, vm, onDismiss) { gone, message -> onDismiss(); onDeleted(gone, message) }
}

/** The sheet itself (stateless, for screenshot tests). */
@Composable
fun DeleteContent(state: DeleteState, actions: DeleteActions, onDismiss: () -> Unit, onDeleted: (List<String>, String) -> Unit = { _, _ -> }) {
    ActionSheet(
        onDismiss,
        footer = {
            state.error?.let { SText(it, size = 12.sp, weight = 600, color = SeedyColors.Danger) }
            BigButton(
                if (state.busy) t("Deleting…") else if (state.size.isNotEmpty()) t("Delete {size}", "size" to state.size) else t("Delete"),
                { actions.delete(onDeleted) }, enabled = state.canDelete, icon = ManageIcons.Trash, style = BigButtonStyle.Danger,
            )
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconTile(ManageIcons.Trash, SeedyColors.Danger, size = 44.dp, iconSize = 22.dp, background = SeedyColors.Danger.copy(alpha = 0.12f), radius = 13.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                SText(state.title.ifEmpty { t("Delete") }, size = 20.sp, weight = 900, lineHeight = 1.2f, color = SeedyColors.White, maxLines = 2)
                if (state.sub.isNotEmpty()) SText(state.sub, size = 13.sp, color = SeedyColors.Text2, maxLines = 1)
            }
        }
        when {
            state.failed -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SText(state.error ?: t("Couldn’t look at the files."), size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Danger)
                PillButton(t("Try again"), actions::retry)
            }
            state.loading -> repeat(3) { Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(13.dp)).background(SeedyColors.Row)) }
            else -> {
                SText(state.text, size = 14.sp, lineHeight = 1.5f, color = SeedyColors.TextHero)
                Breakdown(state)
                if (state.seerr) CheckRow(t("Also clear it in Seerr"), t("So it can be requested again later"), state.clearSeerr, actions::toggleSeerr)
                state.warning?.let { Warning(it) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val word = state.word
                    val label = t("Type {name} to confirm", "name" to "\u0000")
                    val (before, after) = label.split("\u0000").let { it[0] to it.getOrElse(1) { "" } }
                    BasicText(
                        buildAnnotatedString {
                            append(before)
                            withStyle(SpanStyle(color = SeedyColors.White, fontWeight = FontWeight(800))) { append(word) }
                            append(after)
                        },
                        style = BaseText.copy(fontSize = 13.sp, fontWeight = FontWeight(700), color = SeedyColors.Text2),
                    )
                    BoardField(state.typed, actions::type, placeholder = word, onDone = { if (state.canDelete) actions.delete(onDeleted) })
                }
            }
        }
    }
}

private val BreakdownLine = Color(0xFF222222)

@Composable
private fun Breakdown(state: DeleteState) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SeedyColors.Row)) {
        state.lines.forEachIndexed { i, line ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(BreakdownLine))
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SText(line.title, size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 2)
                    if (line.sub.isNotEmpty()) SText(line.sub, size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
                }
                SText(line.size, size = 14.sp, weight = 700, color = SeedyColors.Text, maxLines = 1)
            }
        }
        for (path in state.paths) {
            if (state.lines.isNotEmpty() || path != state.paths.first()) Box(Modifier.fillMaxWidth().height(1.dp).background(BreakdownLine))
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SIcon(ManageIcons.Folder, size = 14.dp, color = SeedyColors.Text3)
                SText(path, Modifier.weight(1f), size = 12.sp, color = SeedyColors.Text3)
            }
        }
    }
}

/** The amber box: Sonarr or Radarr would download it again. */
@Composable
fun Warning(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(SeedyColors.Pending.copy(alpha = 0.08f)).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SIcon(ManageIcons.Warning, Modifier.padding(top = 1.dp), size = 16.dp, color = SeedyColors.Pending)
        SText(text, Modifier.weight(1f), size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Pending)
    }
}
