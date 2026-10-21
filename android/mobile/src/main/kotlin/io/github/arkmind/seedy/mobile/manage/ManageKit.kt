package io.github.arkmind.seedy.mobile.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.mobile.browse.BottomSheet
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/*
 * Parts of the phone's item-management sheets and pages (no Android boards: built from the phone kit, carrying the
 * web boards DetailsMore, ItemDelete, PlaylistNew, ItemEditMetadata, ItemIdentify and ItemImages): the menu's rows,
 * a sheet with a pinned button at its foot (Request seasons' frame), tiles, the checkbox.
 */

object ManageIcons {
    val PlayFromStart = IconDef(2.1f, "M4 12a8 8 0 1 0 2.4-5.7", "M4 4v5h5")
    val PlaylistAdd = IconDef(2.1f, "M4 6h12", "M4 11h12", "M4 16h7", "M18 14v7", "M14.5 17.5h7")
    val Collection = IconDef(2.1f, "M5 8h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z", "M6 5h12", "M8.5 2.5h7")
    val Edit = IconDef(2.1f, "M4 20h4L19 9l-4-4L4 16z", "M13.5 6.5l4 4")
    val Identify = IconDef(2.1f, "M4 11a7 7 0 1 0 14 0a7 7 0 1 0-14 0", "M20 20l-4-4", "M8 11l2 2 3.5-3.8")
    val Image = IconDef(2.1f, "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M7.2 10a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0-3.6 0", "M21 16l-5-5-9 8")
    val Refresh = IconDef(2.1f, "M20 11a8 8 0 1 0-2.3 5.7", "M20 4v7h-7")
    val Trash = IconDef(2.1f, "M4 7h16", "M10 11v6", "M14 11v6", "M6 7l1 12a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1l1-12", "M9 7V4h6v3")
    val Folder = IconDef(2.2f, "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    val Warning = IconDef(2.2f, "M12 3l9.5 17h-19z", "M12 10v4", "M12 17h.01")
    val Me = IconDef(2.2f, "M8 8a4 4 0 1 0 8 0a4 4 0 1 0-8 0", "M4 21a8 8 0 0 1 16 0")
    val People = IconDef(2.2f, "M5.5 8a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0-7 0", "M2.5 20a6.5 6.5 0 0 1 13 0", "M14.9 9a2.6 2.6 0 1 0 5.2 0a2.6 2.6 0 1 0-5.2 0", "M16 14.2a5 5 0 0 1 6 4.8")
    val Globe = IconDef(2.2f, "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0", "M3 12h18", "M12 3a14 14 0 0 1 0 18", "M12 3a14 14 0 0 0 0 18")
    val Lock = IconDef(2.4f, "M7 11h10a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2z", "M8 11V8a4 4 0 0 1 8 0v3")
    val Unlock = IconDef(2.4f, "M7 11h10a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2z", "M8 11V8a4 4 0 0 1 7.8-1.2")
    val Search = IconDef(2.2f, "M4 11a7 7 0 1 0 14 0a7 7 0 1 0-14 0", "M20 20l-4-4")
    val Upload = IconDef(2.2f, "M12 16V4", "M7 9l5-5 5 5", "M5 20h14")
    val Select = IconDef(2.2f, "M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z", "M8 12.5l2.8 2.8L16 10")
}

/** A 36 px tile around an icon (the menu's playlists, the audience options). */
@Composable
fun IconTile(icon: IconDef, tint: Color = SeedyColors.Text2, size: Dp = 36.dp, iconSize: Dp = 18.dp, background: Color = SeedyColors.Surface, radius: Dp = 10.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(radius)).background(background), contentAlignment = Alignment.Center) {
        SIcon(icon, size = iconSize, color = tint)
    }
}

/** The boards' 22 px checkbox: the accent with a dark tick when on, a grey ring when off. */
@Composable
fun CheckBox(on: Boolean, size: Dp = 22.dp) {
    val accent = LocalAccent.current
    Box(
        Modifier.size(size).clip(RoundedCornerShape(7.dp))
            .then(if (on) Modifier.background(accent) else Modifier.border(2.dp, Color(0xFF5A5A5A), RoundedCornerShape(7.dp))),
        contentAlignment = Alignment.Center,
    ) { if (on) SIcon(WebIcons.Check, size = size * 0.64f, color = SeedyColors.InkInverse, stroke = 3.2f) }
}

/** A row of a menu sheet: icon, label and an optional line under it, a chevron when it opens more. */
@Composable
fun MenuRow(icon: IconDef, label: String, onClick: () -> Unit, sub: String? = null, danger: Boolean = false, more: Boolean = false, enabled: Boolean = true) {
    val ink = if (danger) SeedyColors.Danger else SeedyColors.Text
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(12.dp)).tap(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SIcon(icon, size = 20.dp, color = if (danger) SeedyColors.Danger else SeedyColors.Text2)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(label, size = 15.sp, weight = 600, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrEmpty()) SText(sub, size = 12.sp, color = if (danger) SeedyColors.Danger.copy(alpha = 0.75f) else SeedyColors.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (more) SIcon(AppIcons.Chevron, size = 16.dp, color = SeedyColors.Text3)
    }
}

/** A menu's group name ("MANAGE"): 11 px capitals. */
@Composable
fun MenuGroup(text: String) {
    SText(text, Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp), size = 11.sp, weight = 800, tracking = 0.1f, color = SeedyColors.Text3, uppercase = true)
}

/** The line between a menu's groups. */
@Composable
fun MenuLine() = Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().height(1.dp).background(SeedyColors.Surface))

/**
 * A sheet whose [content] scrolls over a pinned [footer] (Request seasons' frame): 16 px sides, 12 px between parts,
 * the foot's line on top and the navigation bar's room under it.
 */
@Composable
fun ActionSheet(onDismiss: () -> Unit, footer: (@Composable ColumnScope.() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    BottomSheet(onDismiss) {
        Column(
            Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
        if (footer != null) {
            val bottom = max(26.dp, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp)
            Column(
                Modifier.fillMaxWidth().imePadding().drawBehind { drawLine(SeedyColors.Line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                    .padding(start = 16.dp, end = 16.dp, top = 13.dp, bottom = bottom),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = footer,
            )
        }
    }
}

enum class BigButtonStyle { Light, Danger }

/** The sheet's 50 px button across its foot ("Delete 252 GB", "Create playlist"). */
@Composable
fun BigButton(label: String, onClick: () -> Unit, enabled: Boolean = true, icon: IconDef? = null, style: BigButtonStyle = BigButtonStyle.Light) {
    val bg = when {
        !enabled -> SeedyColors.Surface2
        style == BigButtonStyle.Danger -> SeedyColors.Danger
        else -> SeedyColors.Inverse
    }
    val ink = if (enabled) SeedyColors.InkInverse else SeedyColors.Text3
    Row(
        Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp)).background(bg).tap(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) SIcon(icon, size = 19.dp, color = ink)
        SText(label, size = 15.sp, weight = 800, color = ink, maxLines = 1)
    }
}

/** A field's name over it (13 px bold grey), as the boards' dialogs label them. */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    SText(text, modifier, size = 13.sp, weight = 700, color = SeedyColors.Text2)
}

/** A tappable row with a checkbox, its label and a line under it ("Also clear it in Seerr"). */
@Composable
fun CheckRow(label: String, sub: String?, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().tap(role = Role.Checkbox, onClick = onClick), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CheckBox(on)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(label, size = 14.sp, weight = 700, color = SeedyColors.White)
            if (!sub.isNullOrEmpty()) SText(sub, size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Text3)
        }
    }
}
