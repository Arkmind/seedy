package io.github.arkmind.seedy.mobile.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.settings.Choice
import io.github.arkmind.seedy.mobile.browse.BottomSheet
import io.github.arkmind.seedy.mobile.parts.SeedyField
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.settings.SectionFoot
import io.github.arkmind.seedy.mobile.settings.SectionTitle
import io.github.arkmind.seedy.mobile.settings.SheetHead
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.components.boxShadow
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.IconPart
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.mobile.settings.PickOption
import io.github.arkmind.seedy.mobile.settings.cssExcess

/*
 * The phone server boards' parts ("Android · Server · …"), beside the settings kit they share (SettingsPage,
 * Section, rows, list sheets): pills and round buttons, dots, notices and status lines, cards (stat tiles, loading,
 * failing and empty lists), avatars and tags, link and radio rows, chips, fields, the unsaved-changes bar and the
 * sheets (confirm, menu, one field, a long list to search, the server's folders).
 */

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
    val Check = IconDef(3f, "M5 12.5l4.5 4.5L19 7.5")
}

/* ── Pills and buttons ── */

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
        modifier.height(height).clip(CircleShape).background(bg).tap(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.5f).padding(horizontal = 16.dp),
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

/** A pill that opens a list ("Everyone ▾", "All levels ▾"). */
@Composable
fun MenuPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PillButton(label, onClick, modifier, trailing = { SIcon(AdminIcons.Caret, size = 13.dp, color = SeedyColors.White) })
}

/** Live: a green dot while new lines show up as they happen, grey while paused. */
@Composable
fun LivePill(on: Boolean, onClick: () -> Unit, onLabel: String = t("Live"), offLabel: String = t("Paused"), enabled: Boolean = true) {
    PillButton(if (on) onLabel else offLabel, onClick, enabled = enabled, leading = { Dot(if (on) SeedyColors.Ok else Color(0xFF5A5A5A)) })
}

/**
 * The boards' round icon button ([size] px): dark with a white icon, light with a dark one, or [bare]; [background] and
 * [tint] recolour it, and [dim] is how it fades while disabled.
 */
@Composable
fun RoundButton(
    icon: IconDef,
    onClick: () -> Unit,
    size: Dp = 40.dp,
    iconSize: Dp = 16.dp,
    light: Boolean = false,
    bare: Boolean = false,
    background: Color? = null,
    tint: Color? = null,
    enabled: Boolean = true,
    dim: Float = 0.5f,
) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(background ?: if (bare) Color.Transparent else if (light) SeedyColors.Inverse else SeedyColors.Surface)
            .tap(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else dim),
        contentAlignment = Alignment.Center,
    ) { SIcon(icon, size = iconSize, color = tint ?: if (light) SeedyColors.InkInverse else SeedyColors.White) }
}

/** The scrolling pills (a user's tabs, the people filter): light when picked, and scrolled into view. */
@Composable
fun <T> TabPills(options: List<Pair<T, String>>, value: T, onPick: (T) -> Unit, modifier: Modifier = Modifier, top: Dp = 16.dp) {
    val scroll = rememberScrollState()
    val spans = remember { mutableStateMapOf<Any?, Pair<Int, Int>>() }
    val density = LocalDensity.current
    val span = spans[value]
    LaunchedEffect(value, span, scroll.viewportSize) {
        val (x, w) = span ?: return@LaunchedEffect
        val view = scroll.viewportSize
        if (view == 0) return@LaunchedEffect
        // The row's 16 px padding, and the same again beyond the pill.
        val pad = with(density) { 16.dp.roundToPx() }
        val target = when {
            x + w + pad * 2 > scroll.value + view -> x + w + pad * 2 - view
            x < scroll.value -> x
            else -> return@LaunchedEffect
        }
        scroll.animateScrollTo(target.coerceIn(0, scroll.maxValue))
    }
    Row(
        modifier.fillMaxWidth().horizontalScroll(scroll).padding(start = 16.dp, end = 16.dp, top = top),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((v, label) in options) {
            val on = v == value
            Box(
                Modifier.onPlaced { spans[v] = it.positionInParent().x.toInt() to it.size.width }
                    .height(36.dp).clip(CircleShape).background(if (on) SeedyColors.Inverse else SeedyColors.Surface)
                    .tap(role = Role.Tab) { onPick(v) }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                SText(label, size = 13.sp, weight = if (on) 800 else 600, color = if (on) SeedyColors.InkInverse else SeedyColors.Text, maxLines = 1)
            }
        }
    }
}

/* ── Dots and lines ── */

/** A status dot. */
@Composable
fun Dot(color: Color, size: Dp = 7.dp, modifier: Modifier = Modifier) = Box(modifier.size(size).background(color, CircleShape))

/** "● Active": a 6 px dot and a short label (12 px bold by default). */
@Composable
fun StatusLine(
    label: String,
    dot: Color,
    modifier: Modifier = Modifier,
    color: Color = SeedyColors.TextHero,
    weight: Int = 700,
    size: TextUnit = 12.sp,
    gap: Dp = 6.dp,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
        Dot(dot, 6.dp)
        SText(label, size = size, weight = weight, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The lede with a status dot before it ("● seedy-server · Jellyfin 12.0 …"). On the board the dot and the text are an
 * inline flex box whose baseline is the dot's bottom, so it sits 0.75 px down the line: the line is 20.25 px and the
 * text a pixel lower than in a plain lede, where Compose's own 20 px line puts it.
 */
@Composable
fun DotLede(text: String, dot: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp).cssExcess(-0.25f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Dot(dot)
        SText(text, size = 13.sp, lineHeight = 1.5f, color = SeedyColors.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/* ── Cards ── */

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

/** Something that couldn't load: what, the server's reason under it, and Try again (16 px sides). */
@Composable
fun ErrorCard(title: String, onRetry: () -> Unit, modifier: Modifier = Modifier, detail: String = "") {
    NoticeCard(title, detail, t("Try again"), SeedyColors.Danger, SeedyColors.Danger.copy(alpha = 0.08f), onRetry, modifier.padding(horizontal = 16.dp))
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

/** An empty list: a title and a line, in a card (16 px sides unless [modifier] says otherwise). */
@Composable
fun EmptyCard(title: String, text: String?, modifier: Modifier = Modifier.padding(horizontal = 16.dp), action: (@Composable () -> Unit)? = null) {
    Card(modifier, padding = 16.dp, gap = 2.dp) {
        SText(title, size = 13.sp, weight = 700, color = SeedyColors.White)
        if (!text.isNullOrEmpty()) SText(text, size = 12.sp, lineHeight = 1.4f, color = SeedyColors.Text3)
        if (action != null) Box(Modifier.padding(top = 10.dp)) { action() }
    }
}

/** A line inside a list's card (it's empty, or couldn't load), with an optional button under it. */
@Composable
fun CardNote(text: String, color: Color = SeedyColors.Text2, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SText(text, size = 13.sp, lineHeight = 1.45f, color = color)
        if (action != null) PillButton(action, onAction)
    }
}

/** Grey bars standing in for [rows] rows while a list loads (16 px sides). */
@Composable
fun SkeletonCard(rows: Int, modifier: Modifier = Modifier, tile: Boolean = false) {
    Card(modifier.padding(horizontal = 16.dp)) {
        repeat(rows) { i ->
            if (i > 0) RowLine()
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (tile) Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(SeedyColors.Row))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width((150 + (i * 53) % 80).dp).height(14.dp).clip(RoundedCornerShape(7.dp)).background(SeedyColors.Surface))
                    Box(Modifier.width((190 + (i * 37) % 60).dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(SeedyColors.Row))
                }
            }
        }
    }
}

/** Grey cards where a page's sections will be, each under a title bar. */
@Composable
fun LoadingCards(count: Int = 3, height: Dp = 132.dp) {
    repeat(count) {
        Spacer(Modifier.height(22.dp))
        Box(Modifier.padding(start = 20.dp).size(96.dp, 12.dp).clip(RoundedCornerShape(6.dp)).background(SeedyColors.Panel))
        Spacer(Modifier.height(10.dp))
        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(height).clip(RoundedCornerShape(18.dp)).background(SeedyColors.Panel))
    }
}

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

/* ── People and things ── */

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

/** A small tag after a name ("Admin", "Seerr", "● Online"), on a light [tint]. */
@Composable
fun NameTag(label: String, color: Color = SeedyColors.TextHero, dot: Color? = null, tint: Float = 0.1f) {
    Row(
        Modifier.clip(CircleShape).background(Color.White.copy(alpha = tint)).padding(horizontal = 7.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (dot != null) Dot(dot, 6.dp)
        SText(label, size = 10.sp, weight = 800, color = color, maxLines = 1)
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

/**
 * A row in the accent ("Add a time", "Show every device"), with a plus unless [icon] is null; [roomy] is the taller
 * add row of the library pages ("Add folder").
 */
@Composable
fun AccentRow(label: String, onClick: () -> Unit, icon: IconDef? = WebIcons.Plus, roomy: Boolean = false) {
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().tap(onClick = onClick)
            .then(if (roomy) Modifier.padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 38.dp) else Modifier.padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp).heightIn(min = 24.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (roomy) 10.dp else 8.dp),
    ) {
        if (icon != null) SIcon(icon, size = if (roomy) 16.dp else 14.dp, color = accent)
        SText(label, size = 14.sp, weight = 700, color = accent)
    }
}

/* ── Chips ── */

/** A 34 px pill that's on (light, with a tick) or off (dark); the tick keeps its room when off. */
@Composable
fun Chip(label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, tick: Boolean = true, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier.height(34.dp).clip(CircleShape).background(if (on) SeedyColors.Inverse else SeedyColors.Surface).tap(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val ink = if (on) SeedyColors.InkInverse else SeedyColors.TextHero
        if (tick) SIcon(AdminIcons.Check, Modifier.alpha(if (on) 1f else 0f), size = 13.dp, color = ink)
        SText(label, size = 12.sp, weight = 700, color = ink, maxLines = 1)
        trailing?.invoke()
    }
}

/** A dark chip with a remove cross: 34 px (the Playback board's codec chips), or the people pages' 32 px when [small]. */
@Composable
fun RemovableChip(label: String, small: Boolean = false, onRemove: () -> Unit) {
    val height = if (small) 32.dp else 34.dp
    Row(
        Modifier.height(height).clip(CircleShape).background(SeedyColors.Surface).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SText(label, Modifier.weight(1f, fill = false), size = if (small) 13.sp else 12.sp, weight = if (small) 600 else 700, color = SeedyColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.size(height - 6.dp).clip(CircleShape).tap(onClick = onRemove), contentAlignment = Alignment.Center) {
            SIcon(WebIcons.Close, size = 12.dp, color = SeedyColors.Text2)
        }
    }
}

/** The "+ Add" chip: dashed, or the people pages' 32 px ringed one when [small]. */
@Composable
fun AddChip(label: String, onClick: () -> Unit, small: Boolean = false, icon: IconDef = WebIcons.Plus) {
    val ring = Color(0xFF4A4A4A)
    val ink = if (small) SeedyColors.Text2 else SeedyColors.Text
    Row(
        Modifier.height(if (small) 32.dp else 34.dp).clip(CircleShape)
            .then(
                if (small) Modifier.border(1.dp, SeedyColors.Stroke, CircleShape)
                else Modifier.drawBehind {
                    val w = 1.dp.toPx()
                    drawRoundRect(ring, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), cornerRadius = CornerRadius(size.height / 2), style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                },
            )
            .tap(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SIcon(icon, size = if (small) 12.dp else 13.dp, color = ink)
        SText(label, size = if (small) 13.sp else 12.sp, weight = 700, color = ink, maxLines = 1)
    }
}

/** Chips with an add chip after them, wrapping (8 px apart). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipFlow(items: List<String>, onRemove: (Int) -> Unit, addLabel: String, onAdd: () -> Unit, modifier: Modifier = Modifier, small: Boolean = false) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { i, v -> RemovableChip(v, small) { onRemove(i) } }
        AddChip(addLabel, onAdd, small)
    }
}

/* ── Fields ── */

/**
 * The boards' 46 px field (#1f1f1f, 13 px corners, 15 px semibold text), ringed in the accent while focused or in red
 * on an [error]; [lines] above 1 makes it a text area (14 px text, 1.4 line height, 12 × 14 px padding).
 */
@Composable
fun BoardField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    mono: Boolean = false,
    lines: Int = 1,
    error: Boolean = false,
    enabled: Boolean = true,
    autoCorrect: Boolean = false,
    size: Int = 15,
    onDone: () -> Unit = {},
    onBlur: () -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var wasFocused by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    val multi = lines > 1
    val style = BaseText.copy(
        fontSize = if (multi || mono) 14.sp else size.sp,
        fontWeight = FontWeight(if (multi) 400 else 600),
        color = SeedyColors.White,
        lineHeight = if (multi) 1.4.em else TextUnit.Unspecified,
        fontFamily = if (mono) FontFamily.Monospace else BaseText.fontFamily,
    )
    val ring = when {
        error -> SeedyColors.Danger
        focused -> accent
        else -> null
    }
    val shape = RoundedCornerShape(13.dp)
    Box(
        modifier.fillMaxWidth().then(if (multi) Modifier.heightIn(min = (14 * 1.4f * lines + 24).toInt().dp) else Modifier.height(46.dp))
            .clip(shape).background(SeedyColors.Surface)
            .then(if (ring != null) Modifier.border(2.dp, ring, shape) else Modifier)
            .padding(horizontal = 14.dp, vertical = if (multi) 12.dp else 0.dp),
        contentAlignment = if (multi) Alignment.TopStart else Alignment.CenterStart,
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            androidx.compose.foundation.text.BasicText(placeholder, style = style.copy(color = SeedyColors.Text3), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = !multi,
            interactionSource = interaction,
            textStyle = style,
            cursorBrush = SolidColor(accent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else keyboard,
                imeAction = if (multi) ImeAction.Default else ImeAction.Done,
                autoCorrectEnabled = autoCorrect,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { f ->
                if (wasFocused && !f.isFocused) onBlur()
                wasFocused = f.isFocused
            },
        )
    }
}

/** A labelled field: the name (14 px bold), the field, a line under it (or the error in red). */
@Composable
fun FieldBlock(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    help: String? = null,
    error: String? = null,
    placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    lines: Int = 1,
    autoCorrect: Boolean = false,
    onDone: () -> Unit = {},
    onBlur: () -> Unit = {},
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SText(label, size = 14.sp, weight = 700, color = SeedyColors.White)
        BoardField(
            value, onChange, placeholder = placeholder, keyboard = keyboard, password = password, lines = lines, error = error != null,
            autoCorrect = autoCorrect, onDone = onDone, onBlur = onBlur,
        )
        when {
            error != null -> if (error.isNotEmpty()) SText(error, size = 12.sp, lineHeight = 1.4f, color = SeedyColors.Danger)
            !help.isNullOrEmpty() -> SText(help, size = 12.sp, lineHeight = 1.4f, color = SeedyColors.Text3)
        }
    }
}

/** A section whose card holds fields: 14 × 16 × 16 px inside, 14 px between them; the foot in [footColor]. */
@Composable
fun FieldSection(title: String?, foot: String? = null, footColor: Color = SeedyColors.Text3, content: @Composable ColumnScope.() -> Unit) {
    if (title != null) SectionTitle(title) else Spacer(Modifier.height(16.dp))
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SeedyColors.Panel).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
    if (foot != null) SectionFoot(foot, footColor)
}

/** A line saying something went wrong, in red (forms). */
@Composable
fun FormError(text: String, modifier: Modifier = Modifier) {
    SText(text, modifier, size = 13.sp, weight = 600, lineHeight = 1.4f, color = SeedyColors.Danger)
}

/* ── Unsaved changes ── */

/** Room under a page's last card for the unsaved-changes bar. */
@Composable
fun SaveBarRoom() = Spacer(Modifier.height(110.dp))

/**
 * The unsaved-changes bar pinned to the foot of the page: what changed (in the pending colour, with a [note] under
 * it), Discard and [saveLabel]; [busy] puts a line in place of it all ("Restarting the server…"). Draw it in the
 * page's overlay.
 */
@Composable
fun BoxScope.SaveBar(
    text: String,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
    saving: Boolean = false,
    saveLabel: String = t("Save"),
    busy: String? = null,
    discardLabel: String = t("Discard"),
    savingLabel: String = t("Saving…"),
    note: String? = null,
    /** Tighter buttons, for a long [saveLabel] ("Save and restart"). */
    compact: Boolean = false,
) {
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Row(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .boxShadow(Color.Black.copy(alpha = 0.5f), blur = 30.dp, y = (-10).dp)
            .background(SeedyColors.Row).tap {}
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = max(26.dp, nav + 12.dp)).heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (busy != null) {
            Dot(SeedyColors.Pending)
            SText(busy, Modifier.weight(1f), size = 13.sp, weight = 700, color = SeedyColors.Pending)
            return@Row
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (text.isNotEmpty()) SText(text, size = 13.sp, weight = 700, color = SeedyColors.Pending)
            if (!note.isNullOrEmpty()) SText(note, size = 12.sp, lineHeight = 1.4f, color = SeedyColors.Text2)
        }
        Row(Modifier.height(40.dp).clip(CircleShape).tap(enabled = !saving, onClick = onDiscard).padding(horizontal = if (compact) 8.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
            SText(discardLabel, size = 13.sp, weight = 700, color = SeedyColors.Text, maxLines = 1)
        }
        Box(Modifier.height(44.dp).clip(CircleShape).background(SeedyColors.Inverse).tap(enabled = !saving, onClick = onSave).padding(horizontal = if (compact) 16.dp else 20.dp), contentAlignment = Alignment.Center) {
            SText(if (saving) savingLabel else saveLabel, size = 14.sp, weight = 800, color = SeedyColors.InkInverse, maxLines = 1)
        }
    }
}

/**
 * Back with unsaved changes asks first ("Leave without saving?"); [onLeave] leaves anyway. Returns what the page's
 * back arrow does to ask the same.
 */
@Composable
fun LeaveGuard(dirty: Boolean, text: String, onLeave: () -> Unit): () -> Unit {
    var asking by remember { mutableStateOf(false) }
    BackHandler(enabled = dirty && !asking) { asking = true }
    if (asking) {
        ConfirmSheet(t("Leave without saving?"), text, t("Leave without saving"), onLeave, { asking = false }, danger = true)
    }
    return { if (dirty) asking = true else onLeave() }
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

/** A sheet's buttons, right-aligned 8 px apart. */
@Composable
fun SheetButtons(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, content = content)
}

/** A sheet's buttons at its foot when the sheet's content has no padding of its own (20 px sides, 24 px under). */
@Composable
fun SheetFoot(content: @Composable RowScope.() -> Unit) = SheetButtons(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp), content)

/** Cancel and [action] (light, or red when [danger]) at a sheet's foot. */
@Composable
fun SheetButtons(action: String, onAction: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false, cancel: String = t("Cancel")) {
    SheetButtons(modifier) {
        PillButton(cancel, onCancel)
        PillButton(action, onAction, style = if (danger) PillStyle.Danger else PillStyle.Light, enabled = enabled)
    }
}

/** A question before something that can't be taken back: Cancel, and [action] (red when [danger]). */
@Composable
fun ConfirmSheet(title: String, text: String?, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = false) {
    BottomSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SText(title, size = 17.sp, weight = 800, color = SeedyColors.White)
            if (text != null) SText(text, size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Text2)
            SheetButtons(action, { onConfirm(); onDismiss() }, onDismiss, Modifier.padding(top = 4.dp), danger = danger)
        }
    }
}

class MenuItem(val label: String, val sub: String? = null, val danger: Boolean = false, val enabled: Boolean = true, val run: () -> Unit = {})

/** A sheet of things to do with one item, each with an optional line under it; [text] goes under the title. */
@Composable
fun MenuSheet(title: String, items: List<MenuItem>, onDismiss: () -> Unit, text: String? = null) {
    BottomSheet(onDismiss = onDismiss) {
        SheetHead(title, text, gap = 4.dp)
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            for (item in items) {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(12.dp))
                        .tap(enabled = item.enabled) { onDismiss(); item.run() }.alpha(if (item.enabled) 1f else 0.5f)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                ) {
                    SText(item.label, size = 15.sp, weight = 600, color = if (item.danger) SeedyColors.Danger else SeedyColors.Text)
                    if (!item.sub.isNullOrEmpty()) SText(item.sub, size = 12.sp, color = SeedyColors.Text3)
                }
            }
        }
    }
}

/**
 * A sheet with one field (a name, a message, a path, a password) and [action]; [required] keeps it from sending an
 * empty field; [extra] goes under the field.
 */
@Composable
fun TextSheet(
    title: String,
    text: String?,
    action: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    initial: String = "",
    placeholder: String = "",
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    required: Boolean = true,
    extra: (@Composable () -> Unit)? = null,
) {
    var value by remember { mutableStateOf(initial) }
    val ok = !required || value.isNotBlank()
    val submit = { if (ok) { onSubmit(value); onDismiss() } }
    BottomSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SText(title, size = 17.sp, weight = 800, color = SeedyColors.White)
            if (text != null) SText(text, size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Text2)
            SeedyField(value, { value = it }, placeholder = placeholder, password = password, keyboard = keyboard, onDone = submit)
            extra?.invoke()
            SheetButtons(action, submit, onDismiss, enabled = ok)
        }
    }
}

/**
 * A long list to pick from, with a search field over it when it's long ([sub] gives a line under an option, [limit]
 * caps how many show); the picked [value] is ticked.
 */
@Composable
fun <T> SearchPickSheet(
    title: String,
    text: String?,
    options: List<Choice<T>>,
    value: T?,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    searchLabel: String = t("Search"),
    empty: String = t("Nothing matches"),
    limit: Int = Int.MAX_VALUE,
    sub: (T) -> String? = { null },
) {
    var q by remember { mutableStateOf("") }
    val shown = (if (q.isBlank()) options else options.filter { it.label.contains(q.trim(), ignoreCase = true) }).take(limit)
    BottomSheet(onDismiss) {
        SheetHead(title, text)
        if (options.size > 8) BoardField(q, { q = it }, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp), placeholder = searchLabel)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            for (o in shown) PickOption(o.label, o.value == value, sub(o.value)) { onPick(o.value); onDismiss() }
            if (shown.isEmpty()) SText(empty, Modifier.padding(12.dp), size = 13.sp, color = SeedyColors.Text3)
        }
    }
}

/** A folder or file in [FolderSheet]. */
class FolderItem(val name: String, val path: String, val file: Boolean)

/**
 * The server's folders (the web's FolderPicker), not this phone's: Up, the path (typed, or tapped to), what's in it
 * (null while it loads), and [action] to pick the folder; with [files], a file is picked by tapping it instead.
 */
@Composable
fun FolderSheet(
    title: String,
    path: String,
    parent: String?,
    entries: List<FolderItem>?,
    error: String?,
    files: Boolean,
    action: String,
    onBrowse: (String) -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by remember(path) { mutableStateOf(path) }
    BottomSheet(onDismiss) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 28.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SText(title, size = 17.sp, weight = 800, color = SeedyColors.White)
            SText(t("Folders on the server, not on this device."), size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Text2)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(t("Up"), { onBrowse(parent.orEmpty()) }, enabled = parent != null, leading = { SIcon(WebIcons.ChevronLeft, size = 16.dp, color = SeedyColors.White) })
                BoardField(typed, { typed = it }, Modifier.weight(1f), placeholder = "/", keyboard = KeyboardType.Uri, mono = true, onDone = { onBrowse(typed.trim()) })
                PillButton(t("Go"), { onBrowse(typed.trim()) })
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SeedyColors.Row)) {
                when {
                    entries == null -> SText("…", Modifier.padding(16.dp), size = 13.sp, color = SeedyColors.Text3)
                    entries.isEmpty() -> SText(error ?: t("No folders in here."), Modifier.padding(16.dp), size = 13.sp, color = if (error != null) SeedyColors.Danger else SeedyColors.Text3)
                    else -> entries.forEachIndexed { i, e ->
                        if (i > 0) RowLine()
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).tap { if (e.file) onPick(e.path) else onBrowse(e.path) }.padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            SIcon(if (e.file) WebIcons.List else WebIcons.Folder, size = 18.dp, color = SeedyColors.Text2)
                            SText(e.name.ifEmpty { e.path }, Modifier.weight(1f), size = 14.sp, weight = 600, color = SeedyColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!e.file) SIcon(AppIcons.Chevron, size = 16.dp, color = SeedyColors.Text3)
                        }
                    }
                }
            }
            if (!files) SheetButtons(action, { onPick(path) }, onDismiss, Modifier.padding(top = 4.dp), enabled = path.isNotEmpty())
            else SheetButtons { PillButton(t("Cancel"), onDismiss) }
        }
    }
}
