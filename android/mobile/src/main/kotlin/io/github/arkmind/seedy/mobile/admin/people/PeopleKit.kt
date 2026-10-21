package io.github.arkmind.seedy.mobile.admin.people

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.admin.people.DeviceKind
import io.github.arkmind.seedy.mobile.parts.HeaderButton
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.settings.BoardSwitch
import io.github.arkmind.seedy.mobile.settings.CardRows
import io.github.arkmind.seedy.mobile.settings.DeviceTag
import io.github.arkmind.seedy.mobile.settings.SectionFoot
import io.github.arkmind.seedy.mobile.settings.cssBox
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/*
 * Pieces the people pages share beyond the admin and settings kits: a page whose title can be tapped (renaming a
 * user), the scrolling tab pills, the counted segmented filter, chips, and the device glyphs.
 */

object PeopleIcons {
    val Tv = IconDef(1.8f, "M5 5h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M8 21h8")
    val Phone = IconDef(1.8f, "M9 3h6a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M11 18h2")
    val Computer = IconDef(1.8f, "M6 5h12a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M2 19h20")
    val Minus = IconDef(2.6f, "M5 12h14")

    fun of(kind: DeviceKind): IconDef = when (kind) {
        DeviceKind.Tv -> Tv
        DeviceKind.Phone -> Phone
        DeviceKind.Computer -> Computer
    }
}

/** #5a5a5a: the count on the picked filter. */
private val CountOn = Color(0xFF5A5A5A)

/**
 * The settings page frame (back arrow, 22 px title, lede, scrolling content, overlay) with a title that can be
 * tapped.
 */
@Composable
fun PeoplePage(
    title: String,
    lede: String?,
    onBack: () -> Unit,
    onTitle: (() -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
            val top = max(36.dp, WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp)
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = top), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HeaderButton(WebIcons.ArrowLeft, onBack, stroke = 2.2f)
                SText(
                    title,
                    Modifier.weight(1f).then(if (onTitle != null) Modifier.tap(onClick = onTitle) else Modifier).padding(start = 6.dp),
                    size = 22.sp, weight = 900, color = SeedyColors.White, maxLines = 1,
                )
            }
            // Android sets a 1.5 line's glyphs a pixel lower than Chrome does.
            if (lede != null) SText(lede, Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp).cssBox(13, 1.5f).offset(y = (-1).dp), size = 13.sp, lineHeight = 1.5f, color = SeedyColors.Text2)
            content()
        }
        overlay()
    }
}

/** The boards' scrolling pills (a user's tabs, the people filter): light when picked, and scrolled into view. */
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

/** The users filter: a segmented control whose options carry a count ("All 5"). */
@Composable
fun <T> CountSegmented(options: List<Triple<T, String, Int>>, value: T, onPick: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(SeedyColors.Surface).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for ((v, label, n) in options) {
            val on = v == value
            Box(
                Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(10.dp)).background(if (on) SeedyColors.Inverse else Color.Transparent)
                    .tap(role = Role.Tab) { onPick(v) },
                contentAlignment = Alignment.Center,
            ) {
                val text = buildAnnotatedString {
                    append(label)
                    append(" ")
                    withStyle(SpanStyle(fontWeight = FontWeight(600), color = if (on) CountOn else SeedyColors.Text3)) { append(n.toString()) }
                }
                BasicText(
                    text,
                    style = BaseText.copy(fontSize = 13.sp, fontWeight = FontWeight(if (on) 800 else 600), color = if (on) SeedyColors.InkInverse else SeedyColors.TextHero, textAlign = TextAlign.Center),
                    maxLines = 1,
                )
            }
        }
    }
}

/** A tag in a list that can be taken out (Parental controls' tags). */
@Composable
fun Chip(label: String, onRemove: () -> Unit) {
    Row(
        Modifier.height(32.dp).clip(CircleShape).background(SeedyColors.Surface).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SText(label, size = 13.sp, weight = 600, color = SeedyColors.Text, maxLines = 1)
        Box(Modifier.size(26.dp).clip(CircleShape).tap(onClick = onRemove), contentAlignment = Alignment.Center) {
            SIcon(WebIcons.Close, size = 12.dp, color = SeedyColors.Text2)
        }
    }
}

/** The dashed "Add tag" chip. */
@Composable
fun AddChip(label: String, onClick: () -> Unit) {
    Row(
        Modifier.height(32.dp).clip(CircleShape).border(1.dp, SeedyColors.Stroke, CircleShape).tap(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SIcon(WebIcons.Plus, size = 12.dp, color = SeedyColors.Text2)
        SText(label, size = 13.sp, weight = 700, color = SeedyColors.Text2, maxLines = 1)
    }
}

/** A row's name and help over what it holds (chips, fields), inside the card's row padding. */
@Composable
fun StackRow(name: String, help: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(name, size = 14.sp, weight = 700, color = SeedyColors.White)
            if (!help.isNullOrEmpty()) SText(help, size = 12.sp, lineHeight = 1.4f, color = SeedyColors.Text3)
        }
        content()
    }
}

/** A row in the accent with a plus ("Add a time", "Show every device"). */
@Composable
fun AccentRow(label: String, onClick: () -> Unit, icon: IconDef? = WebIcons.Plus) {
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().tap(onClick = onClick).padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp).heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) SIcon(icon, size = 14.dp, color = accent)
        SText(label, size = 14.sp, weight = 700, color = accent)
    }
}

/** A line saying something went wrong, in red (forms). */
@Composable
fun FormError(text: String, modifier: Modifier = Modifier) {
    SText(text, modifier, size = 13.sp, weight = 600, lineHeight = 1.4f, color = SeedyColors.Danger)
}

/** A stepper for a value in steps ("9:00" with − and +). */
@Composable
fun Stepper(label: String, value: String, onMinus: (() -> Unit)?, onPlus: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SText(label, Modifier.weight(1f), size = 14.sp, weight = 700, color = SeedyColors.White)
        StepButton(PeopleIcons.Minus, onMinus)
        SText(value, Modifier.padding(horizontal = 4.dp), size = 15.sp, weight = 800, color = SeedyColors.White, maxLines = 1, align = TextAlign.Center)
        StepButton(WebIcons.Plus, onPlus)
    }
}

@Composable
private fun StepButton(icon: IconDef, onClick: (() -> Unit)?) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(SeedyColors.Surface).tap(enabled = onClick != null) { onClick?.invoke() },
        contentAlignment = Alignment.Center,
    ) { SIcon(icon, size = 16.dp, color = if (onClick != null) SeedyColors.White else SeedyColors.Stroke) }
}

/**
 * A section as [io.github.arkmind.seedy.mobile.settings.Section] draws it, with the title's line as tall as
 * Chrome makes a 12 px Noto line (17 px: ascent and descent rounded apart), which keeps the user page's long run of
 * sections on the board's pixels.
 */
@Composable
fun PeopleSection(title: String?, foot: String? = null, build: CardRows.() -> Unit) {
    if (title != null) {
        SText(title, Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp).cssBox(12, 17f / 12f), size = 12.sp, weight = 800, tracking = 0.1f, color = SeedyColors.Text3, uppercase = true)
    } else {
        androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
    }
    val rows = CardRows().apply(build).rows
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SeedyColors.Panel)) {
        rows.forEachIndexed { i, row ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(SeedyColors.Line))
            row()
        }
    }
    if (foot != null) SectionFoot(foot)
}

/*
 * The settings kit's switch and value rows, with a help line whose wrapped height rounds down the way the boards'
 * 16.8 px lines add up (a two-line help is 33.6 px in CSS; a whole-pixel 34 would push every later row down).
 */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeopleRowText(name: String, help: String?, device: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (device) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                SText(name, size = 14.sp, weight = 700, color = SeedyColors.White)
                DeviceTag()
            }
        } else {
            SText(name, size = 14.sp, weight = 700, color = SeedyColors.White)
        }
        // Android rounds the name line out to 20 px where Chrome makes it 19: the help sits a pixel higher to match.
        if (!help.isNullOrEmpty()) SText(help, Modifier.wrappedLines(12, 1.4f).offset(y = (-1).dp), size = 12.sp, lineHeight = 1.4f, color = SeedyColors.Text3)
    }
}

/** A wrapped text's box as tall as its lines add up to, rounded down (one line keeps its own height). */
private fun Modifier.wrappedLines(size: Int, ratio: Float): Modifier = this.layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val line = ratio * size.dp.toPx()
    val lines = (p.height / line).roundToInt()
    val h = if (lines <= 1) p.height else kotlin.math.floor(lines * line).toInt()
    layout(p.width, h) { p.place(0, 0) }
}

private fun Modifier.peopleRow(): Modifier = this.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp).heightIn(min = 44.dp)

@Composable
fun PeopleSwitchRow(name: String, help: String?, on: Boolean, device: Boolean = false, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.tap(role = Role.Switch, enabled = enabled) { onChange(!on) }.peopleRow(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PeopleRowText(name, help, device, Modifier.weight(1f))
        BoardSwitch(on, onChange, enabled)
    }
}

/** A value that opens a list: grey with a chevron, never wider than half the row (longer values wrap, right-aligned). */
@Composable
fun PeoplePickRow(name: String, help: String?, value: String, valueColor: Color = SeedyColors.TextHero, onClick: () -> Unit) {
    Layout(
        content = {
            PeopleRowText(name, help, false)
            Row(Modifier.heightIn(min = 40.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                SText(value, Modifier.weight(1f, fill = false), size = 13.sp, weight = 600, color = valueColor, align = TextAlign.End)
                SIcon(AppIcons.Chevron, size = 16.dp, color = SeedyColors.Text3)
            }
        },
        modifier = Modifier.tap(onClick = onClick).peopleRow(),
    ) { measurables, constraints ->
        val gap = 12.dp.roundToPx()
        val w = constraints.maxWidth
        val button = measurables[1].measure(constraints.copy(minWidth = 0, maxWidth = w / 2, minHeight = 0))
        val text = measurables[0].measure(constraints.copy(minWidth = 0, maxWidth = (w - gap - button.width).coerceAtLeast(0), minHeight = 0))
        val h = maxOf(text.height, button.height, constraints.minHeight)
        layout(w, h) {
            text.place(0, (h - text.height) / 2)
            button.place(w - button.width, (h - button.height) / 2)
        }
    }
}
