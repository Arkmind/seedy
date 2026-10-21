package io.github.arkmind.seedy.mobile.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.admin.AdminIcons
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.components.boxShadow
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/** What the selection bar can do; null hides an action. */
class SelectionActions(
    val onClose: () -> Unit = {},
    val onSelectAll: (() -> Unit)? = null,
    val onPlaylist: (() -> Unit)? = null,
    val onWatched: (() -> Unit)? = null,
    val onRefresh: (() -> Unit)? = null,
    val onDelete: (() -> Unit)? = null,
    val onCollection: (() -> Unit)? = null,
)

/**
 * Board LibrarySelect's bar, pinned over a library while titles are picked: close, "4 selected", Select all, then
 * Add to playlist, Mark watched, Refresh metadata, Delete and ⋯ (Add to collection). [total] is what Select all picks.
 */
@Composable
fun SelectionBar(count: Int, total: Int?, actions: SelectionActions, modifier: Modifier = Modifier, busy: Boolean = false) {
    val top = max(12.dp, WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp)
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.padding(start = 12.dp, end = 12.dp, top = top).fillMaxWidth()
            .boxShadow(Color.Black.copy(alpha = 0.45f), blur = 40.dp, y = 16.dp, radius = 18.dp)
            .clip(shape).background(SeedyColors.Surface).border(1.dp, Color.White.copy(alpha = 0.06f), shape).tap {}
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BarButton(WebIcons.Close, actions.onClose)
            SText(t("{n} selected", "n" to count), size = 18.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (actions.onSelectAll != null && total != null && count < total) {
                Box(Modifier.height(36.dp).clip(RoundedCornerShape(10.dp)).tap(onClick = actions.onSelectAll).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                    SText(t("Select all {n}", "n" to total), size = 14.sp, weight = 700, color = SeedyColors.TextHero, maxLines = 1)
                }
            }
        }
        val enabled = count > 0 && !busy
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            actions.onPlaylist?.let { BarAction(ManageIcons.PlaylistAdd, t("Add to playlist"), it, enabled) }
            actions.onWatched?.let { BarAction(WebIcons.Check, t("Mark watched"), it, enabled) }
            actions.onRefresh?.let { BarAction(ManageIcons.Refresh, t("Refresh metadata"), it, enabled) }
            actions.onDelete?.let { BarAction(ManageIcons.Trash, t("Delete"), it, enabled, danger = true) }
            actions.onCollection?.let { add ->
                var open by remember { mutableStateOf(false) }
                Box {
                    BarButton(AdminIcons.Dots, { open = true }, enabled)
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = SeedyColors.Row, shape = RoundedCornerShape(12.dp)) {
                        DropdownMenuItem(
                            text = { SText(t("Add to collection"), size = 14.sp, weight = 700, color = SeedyColors.White) },
                            leadingIcon = { SIcon(ManageIcons.Collection, size = 18.dp, color = SeedyColors.Text2) },
                            onClick = { open = false; add() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BarButton(icon: IconDef, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(SeedyColors.Surface2).tap(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.5f),
        contentAlignment = Alignment.Center,
    ) { SIcon(icon, size = 18.dp, color = SeedyColors.White, stroke = 2.2f) }
}

@Composable
private fun BarAction(icon: IconDef, label: String, onClick: () -> Unit, enabled: Boolean, danger: Boolean = false) {
    Row(
        Modifier.height(44.dp).clip(RoundedCornerShape(13.dp)).background(if (danger) SeedyColors.Danger.copy(alpha = 0.14f) else SeedyColors.Surface2)
            .tap(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.5f).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SIcon(icon, size = 16.dp, color = if (danger) SeedyColors.Danger else SeedyColors.White)
        SText(label, size = 14.sp, weight = 700, color = if (danger) SeedyColors.Danger else SeedyColors.White, maxLines = 1)
    }
}
