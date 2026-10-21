package io.github.arkmind.seedy.mobile.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.settings.Choice
import io.github.arkmind.seedy.mobile.browse.BottomSheet
import io.github.arkmind.seedy.mobile.parts.HeaderButton
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.LocalLinearText
import androidx.compose.runtime.CompositionLocalProvider
import io.github.arkmind.seedy.ui.theme.NotoLineRatio
import androidx.compose.ui.layout.layout
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import kotlin.math.roundToInt

/*
 * The phone settings boards' parts ("Android · Settings · …"): a page with a back arrow and a lede, sections of rows in
 * 18 px cards, and the rows themselves (switch, value with a chevron, segmented choice, colour swatches).
 */

internal val Quiet = Color(0xFF6A6A6A)


/**
 * A text box as tall as CSS makes it: lines × [ratio] × [size] px, rounded to the nearest pixel, or [up] or down
 * (CSS adds up the fractions; whole-pixel boxes would drift a few pixels from the boards over a long page, so titles
 * round down and the notes under cards round up).
 */
internal fun Modifier.cssBox(size: Int, ratio: Float, up: Boolean? = null): Modifier = this.layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val line = ratio * size.dp.toPx()
    val lines = (p.height / line).roundToInt().coerceAtLeast(1)
    val exact = lines * line
    val h = when (up) {
        true -> kotlin.math.ceil(exact).toInt()
        false -> kotlin.math.floor(exact).toInt()
        null -> exact.roundToInt()
    }
    layout(p.width, h) { p.place(0, (h - p.height) / 2) }
}
private val TagRing = Color(0xFF3A3A3A)
private val TrackOff = Color(0xFF2A2A2A)

/** Where a settings page goes from here. */
data class SettingsNav(
    val back: () -> Unit = {},
    val open: (String) -> Unit = {},
    val requests: () -> Unit = {},
    val admin: () -> Unit = {},
)

/** A settings page: the back arrow and title, a line under it, then the page; [overlay] draws sheets over it. */
@Composable
fun SettingsPage(
    title: String,
    lede: String?,
    onBack: () -> Unit,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
        CompositionLocalProvider(LocalLinearText provides true) {
            Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
                PageHeader(title, onBack)
                if (lede != null) SText(lede, Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp).cssBox(13, 1.5f), size = 13.sp, lineHeight = 1.5f, color = SeedyColors.Text2)
                content()
            }
            overlay()
        }
    }
}

@Composable
internal fun PageHeader(title: String, onBack: () -> Unit) {
    val top = max(36.dp, WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp)
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = top), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        HeaderButton(WebIcons.ArrowLeft, onBack, stroke = 2.2f)
        SText(title, Modifier.weight(1f).padding(start = 6.dp), size = 22.sp, weight = 900, color = SeedyColors.White, maxLines = 1)
    }
}

/** A section's title (12 px capitals) over its card. */
@Composable
fun SectionTitle(text: String) {
    SText(text, Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp).cssBox(12, NotoLineRatio, up = false), size = 12.sp, weight = 800, tracking = 0.1f, color = SeedyColors.Text3, uppercase = true)
}

/** The line under a card. */
@Composable
fun SectionFoot(text: String) {
    SText(text, Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp).cssBox(12, 1.45f, up = true), size = 12.sp, lineHeight = 1.45f, color = SeedyColors.Text3)
}

/** Builds a card's rows; the card draws the 1 px lines between them. */
class CardRows {
    internal val rows = mutableListOf<@Composable () -> Unit>()
    fun row(content: @Composable () -> Unit) {
        rows += content
    }
}

/** A section: an optional title (none leaves 16 px), the card of rows, an optional line under it. */
@Composable
fun Section(title: String?, foot: String? = null, build: CardRows.() -> Unit) {
    if (title != null) SectionTitle(title) else Spacer(Modifier.height(16.dp))
    val rows = CardRows().apply(build).rows
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SeedyColors.Panel)) {
        rows.forEachIndexed { i, row ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(SeedyColors.Line))
            row()
        }
    }
    if (foot != null) SectionFoot(foot)
}

/** "Changes save as you make them" at the foot of a page (or what's happening to the last change). */
@Composable
fun SaveLine(saving: Boolean = false, error: String? = null) {
    val (text, color) = when {
        error != null -> error to SeedyColors.Danger
        saving -> t("Saving…") to Quiet
        else -> t("Changes save as you make them") to Quiet
    }
    SText(text, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 40.dp).cssBox(12, NotoLineRatio), size = 12.sp, color = color, align = TextAlign.Center)
}

/** The "This phone" tag. */
@Composable
fun DeviceTag(label: String = t("This phone")) {
    Box(Modifier.clip(CircleShape).border(1.dp, TagRing, RoundedCornerShape(999.dp)).padding(horizontal = 7.dp, vertical = 1.dp)) {
        SText(label, size = 10.sp, weight = 700, color = SeedyColors.Text2, maxLines = 1)
    }
}

/**
 * A row's name, with the "This phone" tag after it when it fits on the name's line, else on a line of its own (CSS
 * flex-wrap with a 4 × 8 px gap).
 */
@Composable
private fun NameLine(name: String, device: Boolean, color: Color = SeedyColors.White) {
    if (!device) return SText(name, size = 14.sp, weight = 700, color = color)
    Layout(content = {
        SText(name, size = 14.sp, weight = 700, color = color)
        DeviceTag()
    }) { measurables, constraints ->
        val gapX = 8.dp.roundToPx()
        val gapY = 4.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val oneLine = measurables[0].maxIntrinsicWidth(Int.MAX_VALUE)
        val text = measurables[0].measure(loose)
        val tag = measurables[1].measure(loose)
        val sameLine = oneLine <= constraints.maxWidth && text.width + gapX + tag.width <= constraints.maxWidth
        if (sameLine) {
            val h = maxOf(text.height, tag.height)
            layout(text.width + gapX + tag.width, h) {
                text.place(0, (h - text.height) / 2)
                tag.place(text.width + gapX, (h - tag.height) / 2)
            }
        } else {
            layout(maxOf(text.width, tag.width), text.height + gapY + tag.height) {
                text.place(0, 0)
                tag.place(0, text.height + gapY)
            }
        }
    }
}

@Composable
private fun RowText(name: String, help: String?, device: Boolean, modifier: Modifier = Modifier, nameColor: Color = SeedyColors.White) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        NameLine(name, device, nameColor)
        if (!help.isNullOrEmpty()) SText(help, size = 12.sp, lineHeight = 1.4f, color = SeedyColors.Text3)
    }
}

/** A row's box: 12 × 14 × 12 × 16 px of padding around at least 44 px of content (the boards' CSS is content-box). */
private fun Modifier.settingsRow(): Modifier = this.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp).heightIn(min = 44.dp)

/** The boards' 46 × 28 switch: the accent track with a dark knob when on, grey when off. */
@Composable
fun BoardSwitch(on: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val accent = LocalAccent.current
    Box(
        Modifier.size(46.dp, 28.dp).clip(CircleShape).background(if (on) accent else TrackOff)
            .tap(role = Role.Switch, enabled = enabled) { onChange(!on) },
    ) {
        Box(Modifier.padding(start = if (on) 22.dp else 4.dp, top = 4.dp).size(20.dp).background(if (on) SeedyColors.InkInverse else SeedyColors.Text3, CircleShape))
    }
}

@Composable
fun SwitchRow(name: String, help: String?, on: Boolean, device: Boolean = false, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.tap(role = Role.Switch, enabled = enabled) { onChange(!on) }.settingsRow(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RowText(name, help, device, Modifier.weight(1f))
        BoardSwitch(on, onChange, enabled)
    }
}

/**
 * A value that opens a list or a page: the value in grey (or [valueColor]) and a chevron, never wider than half the
 * row (longer values wrap, right-aligned).
 */
@Composable
fun PickRow(name: String, help: String?, value: String, device: Boolean = false, valueColor: Color = SeedyColors.TextHero, onClick: () -> Unit) {
    Layout(
        content = {
            RowText(name, help, device)
            Row(Modifier.heightIn(min = 40.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                SText(value, Modifier.weight(1f, fill = false), size = 13.sp, weight = 600, color = valueColor, align = TextAlign.End)
                SIcon(AppIcons.Chevron, size = 16.dp, color = SeedyColors.Text3)
            }
        },
        modifier = Modifier.tap(onClick = onClick).settingsRow(),
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

/** A short list of choices under the row's name (a segmented control, 36 px options). */
@Composable
fun <T> SegRow(name: String, help: String?, options: List<Choice<T>>, value: T, device: Boolean = false, onChange: (T) -> Unit) {
    Column(Modifier.settingsRow(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RowText(name, help, device)
        Segmented(options, value, onChange)
    }
}

@Composable
fun <T> Segmented(options: List<Choice<T>>, value: T, onChange: (T) -> Unit, height: Dp = 36.dp, size: Int = 12) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(SeedyColors.Surface).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (o in options) {
            val on = o.value == value
            Box(
                Modifier.weight(1f).height(height).clip(RoundedCornerShape(10.dp)).background(if (on) SeedyColors.Inverse else Color.Transparent)
                    .tap(role = Role.RadioButton) { onChange(o.value) }.padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                SText(o.label, size = size.sp, weight = if (on) 800 else 600, color = if (on) SeedyColors.InkInverse else SeedyColors.TextHero, maxLines = 1)
            }
        }
    }
}

/** A colour or background swatch: [argb] fills it; null draws "None" (a grey stroke across). */
data class Swatch(val value: String, val label: String, val argb: Long?)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SwatchRow(name: String, help: String?, swatches: List<Swatch>, value: String, device: Boolean = false, onChange: (String) -> Unit) {
    Column(Modifier.settingsRow(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RowText(name, help, device)
        FlowRow(Modifier.padding(start = 2.dp, top = 2.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            for (s in swatches) SwatchDot(s, s.value == value) { onChange(s.value) }
        }
    }
}

@Composable
private fun SwatchDot(s: Swatch, on: Boolean, onClick: () -> Unit) {
    val stripe = SeedyColors.Text3
    Canvas(Modifier.size(34.dp).tap(role = Role.RadioButton, onClick = onClick)) {
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        if (on) {
            drawCircle(Color.White, r + 5.dp.toPx(), c)
            drawCircle(SeedyColors.Panel, r + 3.dp.toPx(), c)
        }
        if (s.argb != null) drawCircle(Color(s.argb), r, c)
        else {
            // "None": linear-gradient(135deg, transparent 46%, grey 46% 54%, transparent 54%).
            val clip = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
            clipPath(clip) {
                val w = size.width * 0.08f * 1.41421f
                drawLine(stripe, Offset(0f, size.height), Offset(size.width, 0f), strokeWidth = w)
            }
        }
        if (!on) drawCircle(SeedyColors.Stroke, r - 0.75.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
    }
}

/* ── Choosing from a list: a bottom sheet ── */

/** A list the page shows in a sheet: its title, choices and what picking one does. */
class PickSpec<T>(val title: String, val options: List<Choice<T>>, val value: T, val onPick: (T) -> Unit)

/** The page's open sheet, if any. */
@Stable
class SheetState {
    var pick by mutableStateOf<PickSpec<*>?>(null)
    /** A form the page draws itself (with its current state): "password", "code"… */
    var form by mutableStateOf<String?>(null)

    fun <T> open(title: String, options: List<Choice<T>>, value: T, onPick: (T) -> Unit) {
        pick = PickSpec(title, options, value, onPick)
    }

    fun close() {
        pick = null
        form = null
    }
}

@Composable
fun rememberSheet(): SheetState = remember { SheetState() }

/** Draws the page's open sheet. */
@Composable
fun SheetHost(sheet: SheetState) {
    sheet.pick?.let { spec -> PickSheet(spec, sheet::close) }
}

@Composable
private fun <T> PickSheet(spec: PickSpec<T>, onClose: () -> Unit) {
    val accent = LocalAccent.current
    BottomSheet(onDismiss = onClose) {
        SText(spec.title, Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 10.dp), size = 17.sp, weight = 800, color = SeedyColors.White)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            for (o in spec.options) {
                val on = o.value == spec.value
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (on) SeedyColors.Surface else Color.Transparent)
                        .tap(role = Role.RadioButton) { spec.onPick(o.value); onClose() }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SText(o.label, Modifier.weight(1f), size = 15.sp, weight = if (on) 800 else 600, color = if (on) SeedyColors.White else SeedyColors.Text)
                    if (on) SIcon(WebIcons.Check, size = 18.dp, color = accent, stroke = 2.6f)
                }
            }
        }
    }
}

/** A sheet with a title, a line of text and whatever [content] puts under it (forms). */
@Composable
fun FormSheet(title: String, text: String?, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BottomSheet(onDismiss = onClose) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 28.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SText(title, size = 17.sp, weight = 800, color = SeedyColors.White)
            if (text != null) SText(text, size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Text2)
            content()
        }
    }
}

internal fun Modifier.drawTopLine(color: Color): Modifier = drawBehind { drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }

internal fun Float.px(): Int = roundToInt()
