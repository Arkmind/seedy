package io.github.arkmind.seedy.mobile.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.browse.BottomSheet
import io.github.arkmind.seedy.mobile.parts.SeedyField
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.settings.cssBox
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.components.boxShadow
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.IconPart
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/*
 * The phone server boards' parts ("Android · Server · …"), beside the settings kit they share (SettingsPage,
 * Section, SwitchRow, PickRow, sheets): pill buttons, notices, stat tiles, avatars, link rows, the unsaved-changes
 * bar and the confirm and action sheets.
 */

/** #6a6a6a: the pages' foot notes. */
val Faint = Color(0xFF6A6A6A)

object AdminIcons {
    /** The session card's actions button. */
    val Dots = IconDef(
        0f,
        listOf(
            IconPart("M3.8 12a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0Z", filled = true),
            IconPart("M10.3 12a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0Z", filled = true),
            IconPart("M16.8 12a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0Z", filled = true),
        ),
    )
    val Run = IconDef(0f, listOf(IconPart("M7 4.5v15l12-7.5z", filled = true)))
    val Stop = IconDef(0f, listOf(IconPart("M7 5h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", filled = true)))
    val Caret = IconDef(2.4f, "M6 9l6 6 6-6")
    val Plus = IconDef(2.6f, "M12 5v14", "M5 12h14")
    val Check = IconDef(3f, "M5 12.5l4.5 4.5L19 7.5")
}

enum class PillStyle { Dark, Light, Danger }

/** The boards' 40 px pill button (`flex: none`, 16 px sides, 13 px bold). */
@Composable
fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.Dark,
    enabled: Boolean = true,
    height: Dp = 40.dp,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val bg = if (style == PillStyle.Light) SeedyColors.Inverse else SeedyColors.Surface
    val ink = when (style) {
        PillStyle.Light -> SeedyColors.InkInverse
        PillStyle.Danger -> SeedyColors.Danger
        PillStyle.Dark -> SeedyColors.White
    }
    Row(
        // Disabled, the whole pill fades (the web's `opacity: .5`).
        modifier.alpha(if (enabled) 1f else 0.5f).height(height).clip(CircleShape).background(bg).tap(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        leading?.invoke()
        SText(label, size = 13.sp, weight = 700, color = ink, maxLines = 1)
        trailing?.invoke()
    }
}

/** A row of pill buttons under the lede (14 px down, 16 px sides); [scroll] lets it run past the edge. */
@Composable
fun PillRow(modifier: Modifier = Modifier, scroll: Boolean = true, top: Dp = 14.dp, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().then(if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier).padding(start = 16.dp, end = 16.dp, top = top),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** The lede with a status dot before it ("● seedy-server · Jellyfin 12.0 …"). */
@Composable
fun DotLede(text: String, dot: Color, modifier: Modifier = Modifier) {
    Row(modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp).cssBox(13, 1.5f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.dp).background(dot, CircleShape))
        SText(text, size = 13.sp, lineHeight = 1.5f, color = SeedyColors.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A notice: a dot, a title and a line, and a light button on the right. */
@Composable
fun NoticeCard(title: String, help: String, action: String?, dot: Color, background: Color, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(background).padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(10.dp).background(dot, CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(title, size = 13.sp, weight = 700, color = SeedyColors.White)
            if (help.isNotEmpty()) SText(help, size = 12.sp, lineHeight = 1.4f, color = SeedyColors.Text2)
        }
        if (action != null) PillButton(action, onAction, style = PillStyle.Light)
    }
}

/** A 18 px card (#141414) for whatever it holds; [padding] inside. */
@Composable
fun Card(modifier: Modifier = Modifier, padding: Dp = 0.dp, gap: Dp = 0.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SeedyColors.Panel).padding(padding),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/** The 1 px line between a card's rows. */
@Composable
fun RowLine() = Box(Modifier.fillMaxWidth().height(1.dp).background(SeedyColors.Line))

/** A number over its label (Library, Activity log). */
@Composable
fun StatTile(n: String, label: String, modifier: Modifier = Modifier, ink: Color = SeedyColors.White, size: TextUnit = 22.sp) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(SeedyColors.Panel).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SText(n, size = size, weight = 900, color = ink, maxLines = 1)
        SText(label, size = 12.sp, color = SeedyColors.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A progress line: [fraction] of a #2a2a2a track. */
@Composable
fun Bar(fraction: Float, modifier: Modifier = Modifier, color: Color = LocalAccent.current, height: Dp = 4.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(2.dp)).background(SeedyColors.Surface2)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).background(color))
    }
}

/** The server pages' avatar colours, in the order of the server's user list. */
val AvatarColours = listOf(
    Color(0xFFF0F0F0), Color(0xFF9FD3FF), Color(0xFFB8F59F), Color(0xFFFFC98A),
    Color(0xFFE3C4FF), Color(0xFFFFB3E6), Color(0xFFA8E6DF), Color(0xFFF0B9A0),
)

fun avatarColour(index: Int): Color = AvatarColours[index.mod(AvatarColours.size)]

/**
 * A person's round avatar: their picture, or their initial on [colour]. [online] adds the green dot at the bottom
 * right, ringed in [ring] (the colour behind it).
 */
@Composable
fun AdminAvatar(
    name: String,
    colour: Color,
    size: Dp,
    letter: TextUnit,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    online: Boolean = false,
    dot: Dp = 12.dp,
    dotInset: Dp = (-1).dp,
    ring: Color = SeedyColors.Panel,
) {
    Box(modifier.size(size)) {
        Box(Modifier.size(size).clip(CircleShape).background(colour), contentAlignment = Alignment.Center) {
            SText(name.trim().take(1).uppercase().ifEmpty { "?" }, size = letter, weight = 900, color = SeedyColors.InkInverse, maxLines = 1)
            if (imageUrl != null) Artwork(imageUrl, Modifier.fillMaxSize(), placeholder = Color.Transparent)
        }
        if (online) {
            Box(
                Modifier.align(Alignment.BottomEnd).offset(x = -dotInset, y = -dotInset).size(dot)
                    .drawBehind { drawCircle(ring, radius = this.size.minDimension / 2 + 3.dp.toPx()) }
                    .background(SeedyColors.Ok, CircleShape),
            )
        }
    }
}

/** A small grey tag after a name ("Admin", "Disabled"). */
@Composable
fun NameTag(label: String) {
    Box(Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.1f)).padding(horizontal = 7.dp, vertical = 1.dp)) {
        SText(label, size = 10.sp, weight = 800, color = SeedyColors.TextHero, maxLines = 1)
    }
}

/**
 * A row that opens a page: its name, a line under it (in [subColor]), an optional count badge and the chevron; at
 * least 56 px inside 8 px of padding (the boards' CSS is content-box).
 */
@Composable
fun LinkRow(name: String, sub: String, onClick: () -> Unit, subColor: Color = SeedyColors.Text3, badge: Int = 0) {
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().tap(onClick = onClick).padding(start = 16.dp, end = 14.dp, top = 8.dp, bottom = 8.dp).heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(name, size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
            if (sub.isNotEmpty()) SText(sub, size = 12.sp, color = subColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (badge > 0) {
            Box(Modifier.heightIn(min = 22.dp).widthIn(min = 22.dp).clip(CircleShape).background(accent).padding(horizontal = 7.dp), contentAlignment = Alignment.Center) {
                SText(badge.toString(), size = 11.sp, weight = 800, color = SeedyColors.InkInverse, maxLines = 1)
            }
        }
        SIcon(AppIcons.Chevron, size = 16.dp, color = SeedyColors.Text3)
    }
}

/** The centred grey line at a page's foot ("Scans run on the server, so you can close the app"). */
@Composable
fun FootNote(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    SText(
        text,
        modifier.fillMaxWidth().then(if (onClick != null) Modifier.tap(onClick = onClick) else Modifier).padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 40.dp),
        size = 12.sp,
        color = Faint,
        align = TextAlign.Center,
    )
}

/** Room under a page's last card for the unsaved-changes bar. */
@Composable
fun SaveBarRoom() = Spacer(Modifier.height(110.dp))

/**
 * The unsaved-changes bar pinned to the foot of the page: what changed (in the pending colour), Discard and Save.
 * Draw it in the page's overlay.
 */
@Composable
fun BoxScope.SaveBar(text: String, onDiscard: () -> Unit, onSave: () -> Unit, saving: Boolean = false) {
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Row(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .boxShadow(Color.Black.copy(alpha = 0.5f), blur = 30.dp, y = (-10).dp)
            .background(SeedyColors.Row).tap {}
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = max(26.dp, nav + 12.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SText(text, Modifier.weight(1f), size = 13.sp, weight = 700, color = SeedyColors.Pending)
        Row(Modifier.height(40.dp).clip(CircleShape).tap(enabled = !saving, onClick = onDiscard).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            SText(t("Discard"), size = 13.sp, weight = 700, color = SeedyColors.Text, maxLines = 1)
        }
        Box(Modifier.height(44.dp).clip(CircleShape).background(SeedyColors.Inverse).tap(enabled = !saving, onClick = onSave).padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
            SText(if (saving) t("Saving…") else t("Save"), size = 14.sp, weight = 800, color = SeedyColors.InkInverse, maxLines = 1)
        }
    }
}

/** The light floating button at the bottom right ("Add user"). Draw it in the page's overlay. */
@Composable
fun BoxScope.FloatingPill(label: String, icon: IconDef, onClick: () -> Unit) {
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Row(
        Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = max(26.dp, nav + 12.dp))
            .boxShadow(Color.Black.copy(alpha = 0.5f), blur = 30.dp, y = 10.dp, radius = 18.dp)
            .height(56.dp).clip(RoundedCornerShape(18.dp)).background(SeedyColors.Inverse).tap(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SIcon(icon, size = 18.dp, color = SeedyColors.InkInverse)
        SText(label, size = 14.sp, weight = 800, color = SeedyColors.InkInverse, maxLines = 1)
    }
}

/* ── Sheets ── */

/** A question before something that can't be taken back: Cancel, and [action] (red when [danger]). */
@Composable
fun ConfirmSheet(title: String, text: String?, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = false) {
    BottomSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SText(title, size = 17.sp, weight = 800, color = SeedyColors.White)
            if (text != null) SText(text, size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Text2)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                PillButton(t("Cancel"), onDismiss)
                PillButton(action, { onConfirm(); onDismiss() }, style = if (danger) PillStyle.Danger else PillStyle.Light)
            }
        }
    }
}

/**
 * Text joined with " · " the way the boards' flex rows space it: each part is its own item, [gap] apart (a template's
 * "{{user}} · {{device}}" becomes three flex items).
 */
@Composable
fun SpacedDots(parts: List<String>, size: TextUnit, color: Color, modifier: Modifier = Modifier, gap: Dp = 6.dp, weight: Int = 400) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
        val shown = parts.filter { it.isNotEmpty() }
        shown.forEachIndexed { i, part ->
            if (i > 0) SText("·", size = size, weight = weight, color = color, maxLines = 1)
            // The last part gives way when the line is too long.
            SText(part, if (i == shown.lastIndex) Modifier.weight(1f, fill = false) else Modifier, size = size, weight = weight, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A short list of things to do with one item (a session's actions). */
@Composable
fun ActionSheet(title: String, actions: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit, danger: Set<Int> = emptySet()) {
    BottomSheet(onDismiss = onDismiss) {
        SText(title, Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 10.dp), size = 17.sp, weight = 800, color = SeedyColors.White)
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            actions.forEachIndexed { i, (label, run) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(12.dp)).tap { onDismiss(); run() }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SText(label, size = 15.sp, weight = 600, color = if (i in danger) SeedyColors.Danger else SeedyColors.Text)
                }
            }
        }
    }
}

/** A sheet with one text field and a send/save button. */
@Composable
fun TextSheet(title: String, text: String?, action: String, onSubmit: (String) -> Unit, onDismiss: () -> Unit, initial: String = "", placeholder: String = "") {
    var value by remember { mutableStateOf(initial) }
    BottomSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SText(title, size = 17.sp, weight = 800, color = SeedyColors.White)
            if (text != null) SText(text, size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Text2)
            SeedyField(value, { value = it }, placeholder = placeholder, onDone = { if (value.isNotBlank()) { onSubmit(value); onDismiss() } })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                PillButton(t("Cancel"), onDismiss)
                PillButton(action, { onSubmit(value); onDismiss() }, style = PillStyle.Light, enabled = value.isNotBlank())
            }
        }
    }
}

/** A 92 × 52 (16:9) picture with 10 px corners, or [fallback] while there's none. */
@Composable
fun Thumb(url: String?, width: Dp, modifier: Modifier = Modifier, fallback: Color = SeedyColors.Surface, content: @Composable BoxScope.() -> Unit = {}) {
    Box(modifier.size(width, width * 9 / 16).clip(RoundedCornerShape(10.dp)).background(fallback)) {
        if (url != null) Artwork(url, Modifier.fillMaxSize(), placeholder = fallback)
        content()
    }
}

/** A radio dot (the boards' 22 px ring with a 10 px accent centre). */
@Composable
fun RadioDot(on: Boolean) {
    val accent = LocalAccent.current
    Box(
        Modifier.size(22.dp).drawBehind {
            val w = 2.dp.toPx()
            drawCircle(if (on) accent else Color(0xFF5A5A5A), radius = size.minDimension / 2 - w / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
        },
        contentAlignment = Alignment.Center,
    ) { if (on) Box(Modifier.size(10.dp).background(accent, CircleShape)) }
}

/** A row picked from a list (Hardware acceleration): the radio, a name and a line under it. */
@Composable
fun RadioRow(name: String, sub: String?, on: Boolean, onClick: () -> Unit, subColor: Color = SeedyColors.Text3) {
    Row(
        Modifier.fillMaxWidth().tap(role = Role.RadioButton, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 38.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RadioDot(on)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            SText(name, size = 14.sp, weight = 700, color = SeedyColors.White)
            if (!sub.isNullOrEmpty()) SText(sub, size = 12.sp, color = subColor)
        }
    }
}
