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
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ParentDataModifierNode
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
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
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * The phone settings boards' parts ("Android · Settings · …"), which the server pages share: a page with a back arrow
 * and a lede, sections of rows in 18 px cards, the rows themselves (switch, value with a chevron, segmented choice,
 * colour swatches, a name over what it holds) and the sheets that pick from a list. Text is boxed as the boards'
 * Chrome boxes it (see [cssText]), and a page stacks its parts the CSS way (see [CssColumn]).
 */

/** #6a6a6a: the pages' foot notes. */
val Faint = Color(0xFF6A6A6A)

private val TagRing = Color(0xFF3A3A3A)
private val TrackOff = Color(0xFF2A2A2A)
private val CountOn = Color(0xFF5A5A5A)

/* ── Text as the boards' Chrome boxes it ── */

/** Noto Sans's ascent and descent (hhea, per em). */
private const val NotoAscent = 1.069f
private const val NotoDescent = 0.293f

/** How many pixels taller than its CSS box a child of a [CssColumn] came out (a fraction CSS carries on). */
interface CssExcess {
    val excess: Float
}

/**
 * Lays out [size] px text with a [ratio] line height as the boards' Chrome does. Chrome gives a line exactly size ×
 * ratio px and floors the half-leading above the glyphs; Compose rounds each line up to whole pixels and centres them,
 * which sets 13 px ledes and 12 px help lines a pixel low. The box is as tall as its lines add up to, to the nearest
 * pixel, and the glyphs sit where Chrome puts the first line's. In a [CssColumn] the fraction carries on to what's
 * under it, as CSS adds it up. (`line-height: normal` needs none of this: Android rounds Noto's ascent and descent
 * apart just as Chrome does, 12 px → 17, 14 → 19.)
 */
fun Modifier.cssText(size: Int, ratio: Float): Modifier = this then CssTextElement(size, ratio)

private data class CssTextElement(val size: Int, val ratio: Float) : ModifierNodeElement<CssTextNode>() {
    override fun create() = CssTextNode(size, ratio)
    override fun update(node: CssTextNode) {
        node.size = size
        node.ratio = ratio
        node.invalidateMeasurement()
    }
}

private class CssTextNode(var size: Int, var ratio: Float) : Modifier.Node(), LayoutModifierNode, ParentDataModifierNode, CssExcess {
    override var excess = 0f

    override fun Density.modifyParentData(parentData: Any?): Any = this@CssTextNode

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val p = measurable.measure(constraints)
        val px = size.sp.toPx()
        val ascent = (NotoAscent * px).roundToInt()
        val font = ascent + (NotoDescent * px).roundToInt()
        val line = ratio * px
        val lines = (p.height / line).roundToInt().coerceAtLeast(1)
        val css = lines * line
        val h = css.roundToInt()
        excess = h - css
        val base = p[FirstBaseline]
        val dy = if (base == AlignmentLine.Unspecified) 0 else floor((line - font) / 2f).toInt() + ascent - base
        return layout(p.width, h) { p.place(0, dy) }
    }
}

/** A [CssColumn]'s own fraction, for the column it sits in (or for a row that holds it, see [settingsRow]). */
internal class CssBox : CssExcess, ParentDataModifier {
    override var excess = 0f
    override fun Density.modifyParentData(parentData: Any?): Any = this@CssBox
}

private class FixedExcess(override val excess: Float) : CssExcess, ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = this@FixedExcess
}

/** Tells the [CssColumn] this sits in that it came out [px] pixels taller than CSS makes it (a 51.75 px picture is 52). */
fun Modifier.cssExcess(px: Float): Modifier = this.then(FixedExcess(px))

/**
 * A column stacked the CSS way: each child's CSS height (its height less its [CssExcess]) adds up in fractions and
 * every child is drawn at the rounded position, as Chrome snaps boxes, so a long page keeps in step with its board
 * instead of gaining or losing a fraction of a pixel at each lede and card foot. It passes its own fraction on when
 * it sits in another one. Children are [gap] apart and start-aligned; there are no weights.
 */
@Composable
fun CssColumn(modifier: Modifier = Modifier, gap: Dp = 0.dp, content: @Composable () -> Unit) = CssColumn(modifier, gap, remember { CssBox() }, content)

@Composable
internal fun CssColumn(modifier: Modifier, gap: Dp, own: CssBox, content: @Composable () -> Unit) {
    Layout(content, modifier.then(own)) { measurables, constraints ->
        val space = gap.roundToPx()
        var y = 0f
        var width = constraints.minWidth
        val tops = IntArray(measurables.size)
        val placeables = measurables.mapIndexed { i, m ->
            if (i > 0) y += space
            val room = if (constraints.hasBoundedHeight) (constraints.maxHeight - y.roundToInt()).coerceAtLeast(0) else Constraints.Infinity
            val p = m.measure(constraints.copy(minWidth = 0, minHeight = 0, maxHeight = room))
            tops[i] = y.roundToInt()
            y += p.height - ((m.parentData as? CssExcess)?.excess ?: 0f)
            width = maxOf(width, p.width)
            p
        }
        val total = y.roundToInt()
        val height = total.coerceIn(constraints.minHeight, constraints.maxHeight)
        own.excess = if (height == total) height - y else 0f
        layout(width.coerceAtMost(constraints.maxWidth), height) {
            placeables.forEachIndexed { i, p -> p.place(0, tops[i]) }
        }
    }
}

/* ── The page ── */

/** Where a settings page goes from here. */
data class SettingsNav(
    val back: () -> Unit = {},
    val open: (String) -> Unit = {},
    val requests: () -> Unit = {},
    val admin: () -> Unit = {},
)

/**
 * A settings page: the back arrow and title ([onTitle] makes it tappable), the lede, then the page stacked as CSS
 * stacks it; [overlay] draws sheets and bars over it.
 */
@Composable
fun SettingsPage(
    title: String,
    lede: String?,
    onBack: () -> Unit,
    onTitle: (() -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
        CssColumn(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
            PageHeader(title, onBack, onTitle)
            if (lede != null) Lede(lede)
            content()
        }
        overlay()
    }
}

@Composable
fun PageHeader(title: String, onBack: () -> Unit, onTitle: (() -> Unit)? = null) {
    val top = max(36.dp, WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp)
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = top), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        HeaderButton(WebIcons.ArrowLeft, onBack, stroke = 2.2f)
        SText(
            title,
            Modifier.weight(1f).then(if (onTitle != null) Modifier.tap(onClick = onTitle) else Modifier).padding(start = 6.dp),
            size = 22.sp, weight = 900, color = SeedyColors.White, maxLines = 1,
        )
    }
}

/** The line under a page's title (13 px, 1.5 line height). */
@Composable
fun Lede(text: String, modifier: Modifier = Modifier) {
    SText(text, modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp).cssText(13, 1.5f), size = 13.sp, lineHeight = 1.5f, color = SeedyColors.Text2)
}

/** A section's title (12 px capitals) over its card. */
@Composable
fun SectionTitle(text: String) {
    SText(text, Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp), size = 12.sp, weight = 800, tracking = 0.1f, color = SeedyColors.Text3, uppercase = true)
}

/** The line under a card (12 px, 1.45 line height); red for an error. */
@Composable
fun SectionFoot(text: String, color: Color = SeedyColors.Text3) {
    SText(text, Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp).cssText(12, 1.45f), size = 12.sp, lineHeight = 1.45f, color = color)
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
    CssColumn(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SeedyColors.Panel)) {
        rows.forEachIndexed { i, row ->
            if (i > 0) RowLine()
            row()
        }
    }
    if (foot != null) SectionFoot(foot)
}

/** The centred grey line at a page's foot ("Scans run on the server, so you can close the app"). */
@Composable
fun FootNote(text: String, modifier: Modifier = Modifier, color: Color = Faint, onClick: (() -> Unit)? = null) {
    SText(
        text,
        modifier.fillMaxWidth().then(if (onClick != null) Modifier.tap(onClick = onClick) else Modifier).padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 40.dp),
        size = 12.sp, color = color, align = TextAlign.Center,
    )
}

/** "Changes save as you make them" at the foot of a page (or what's happening to the last change). */
@Composable
fun SaveLine(saving: Boolean = false, error: String? = null) {
    when {
        error != null -> FootNote(error, color = SeedyColors.Danger)
        saving -> FootNote(t("Saving…"))
        else -> FootNote(t("Changes save as you make them"))
    }
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

/**
 * A row's name (14 px bold, with the "This phone" tag when it's [device]) over its help line (12 px, 1.4 line height,
 * set where Chrome sets it).
 */
@Composable
fun RowLabel(
    name: String,
    help: String?,
    modifier: Modifier = Modifier,
    device: Boolean = false,
    nameColor: Color = SeedyColors.White,
    helpColor: Color = SeedyColors.Text3,
) = RowLabel(name, help, modifier, device, nameColor, helpColor, remember { CssBox() })

@Composable
private fun RowLabel(name: String, help: String?, modifier: Modifier, device: Boolean, nameColor: Color, helpColor: Color, box: CssBox) {
    CssColumn(modifier, 2.dp, box) {
        NameLine(name, device, nameColor)
        if (!help.isNullOrEmpty()) SText(help, Modifier.cssText(12, 1.4f), size = 12.sp, lineHeight = 1.4f, color = helpColor)
    }
}

/** A row's box: 12 × 14 × 12 × 16 px of padding around at least 44 px of content (the boards' CSS is content-box). */
fun Modifier.settingsRow(): Modifier = this.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp).heightIn(min = 44.dp)

/** [settingsRow] for a row around a [RowLabel], telling the card its CSS height when the label is what makes it tall. */
private fun Modifier.settingsRow(label: CssBox): Modifier =
    this.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp).then(CssRowElement(label)).heightIn(min = 44.dp)

private class CssRowElement(val label: CssBox) : ModifierNodeElement<CssRowNode>() {
    override fun create() = CssRowNode(label)
    override fun update(node: CssRowNode) {
        node.label = label
    }
    override fun equals(other: Any?) = other is CssRowElement && other.label === label
    override fun hashCode() = System.identityHashCode(label)
}

/** A row is at least 44 px; past that, it's as tall as CSS makes its label (and what's under it). */
private class CssRowNode(var label: CssBox) : Modifier.Node(), LayoutModifierNode, ParentDataModifierNode, CssExcess {
    override var excess = 0f

    override fun Density.modifyParentData(parentData: Any?): Any = this@CssRowNode

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val p = measurable.measure(constraints)
        excess = p.height - maxOf(44.dp.toPx(), p.height - label.excess)
        return layout(p.width, p.height) { p.place(0, 0) }
    }
}

/** A row's name and help over what it holds (chips, a field, a list), [gap] apart. */
@Composable
fun StackRow(name: String, help: String?, gap: Dp = 10.dp, content: @Composable ColumnScope.() -> Unit) {
    val label = remember { CssBox() }
    Column(Modifier.settingsRow(label), verticalArrangement = Arrangement.spacedBy(gap)) {
        RowLabel(name, help, Modifier, false, SeedyColors.White, SeedyColors.Text3, label)
        content()
    }
}

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
    val label = remember { CssBox() }
    Row(
        Modifier.tap(role = Role.Switch, enabled = enabled) { onChange(!on) }.settingsRow(label),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RowLabel(name, help, Modifier.weight(1f), device, SeedyColors.White, SeedyColors.Text3, label)
        BoardSwitch(on, onChange, enabled)
    }
}

/**
 * A value that opens a list or a page: the value in grey (or [valueColor]) and a chevron, never wider than half the
 * row (longer values wrap, right-aligned).
 */
@Composable
fun PickRow(name: String, help: String?, value: String, device: Boolean = false, valueColor: Color = SeedyColors.TextHero, onClick: () -> Unit) {
    val label = remember { CssBox() }
    Layout(
        content = {
            RowLabel(name, help, Modifier, device, SeedyColors.White, SeedyColors.Text3, label)
            Row(Modifier.heightIn(min = 40.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                SText(value, Modifier.weight(1f, fill = false), size = 13.sp, weight = 600, color = valueColor, align = TextAlign.End)
                SIcon(AppIcons.Chevron, size = 16.dp, color = SeedyColors.Text3)
            }
        },
        modifier = Modifier.tap(onClick = onClick).settingsRow(label),
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
    val label = remember { CssBox() }
    Column(Modifier.settingsRow(label), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RowLabel(name, help, Modifier, device, SeedyColors.White, SeedyColors.Text3, label)
        Segmented(options, value, onChange)
    }
}

/**
 * The boards' segmented control: a #1f1f1f well of [height] px options, the picked one light; [count] adds a grey
 * number after an option's name ("All 5").
 */
@Composable
fun <T> Segmented(
    options: List<Choice<T>>,
    value: T,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp,
    size: Int = 12,
    count: ((T) -> String?)? = null,
) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(SeedyColors.Surface).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (o in options) {
            val on = o.value == value
            Box(
                Modifier.weight(1f).height(height).clip(RoundedCornerShape(10.dp)).background(if (on) SeedyColors.Inverse else Color.Transparent)
                    .tap(role = Role.RadioButton) { onChange(o.value) }.padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                val n = count?.invoke(o.value)
                val ink = if (on) SeedyColors.InkInverse else SeedyColors.TextHero
                if (n.isNullOrEmpty()) {
                    SText(o.label, size = size.sp, weight = if (on) 800 else 600, color = ink, maxLines = 1)
                } else {
                    val text = buildAnnotatedString {
                        append(o.label)
                        append(" ")
                        withStyle(SpanStyle(fontWeight = FontWeight(600), color = if (on) CountOn else SeedyColors.Text3)) { append(n) }
                    }
                    BasicText(text, style = BaseText.copy(fontSize = size.sp, fontWeight = FontWeight(if (on) 800 else 600), color = ink, textAlign = TextAlign.Center), maxLines = 1)
                }
            }
        }
    }
}

/** A colour or background swatch: [argb] fills it; null draws "None" (a grey stroke across). */
data class Swatch(val value: String, val label: String, val argb: Long?)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SwatchRow(name: String, help: String?, swatches: List<Swatch>, value: String, device: Boolean = false, onChange: (String) -> Unit) {
    val label = remember { CssBox() }
    Column(Modifier.settingsRow(label), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RowLabel(name, help, Modifier, device, SeedyColors.White, SeedyColors.Text3, label)
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
    BottomSheet(onDismiss = onClose) {
        SheetHead(spec.title)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            for (o in spec.options) PickOption(o.label, o.value == spec.value) { spec.onPick(o.value); onClose() }
        }
    }
}

/** A choice in a list sheet: light grey with the accent tick when it's the picked one; [sub] adds a line under it. */
@Composable
fun PickOption(label: String, on: Boolean, sub: String? = null, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(12.dp))
            .background(if (on) SeedyColors.Surface else Color.Transparent)
            .tap(role = Role.RadioButton, onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            SText(label, size = 15.sp, weight = if (on) 800 else 600, color = if (on) SeedyColors.White else SeedyColors.Text)
            if (sub != null) SText(sub, size = 12.sp, color = SeedyColors.Text3)
        }
        if (on) SIcon(WebIcons.Check, size = 18.dp, color = accent, stroke = 2.6f)
    }
}

/** A sheet's title and the line under it, [gap] apart. */
@Composable
fun SheetHead(title: String, text: String? = null, gap: Dp = 6.dp) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
        SText(title, size = 17.sp, weight = 800, color = SeedyColors.White)
        if (text != null) SText(text, size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Text2)
    }
}

/**
 * A sheet with a title, a line of text and whatever [content] puts under it (forms); [scroll] lets it scroll when
 * it's taller than the screen allows.
 */
@Composable
fun FormSheet(title: String, text: String?, onClose: () -> Unit, scroll: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    BottomSheet(onDismiss = onClose) {
        Column(
            Modifier.then(if (scroll) Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()) else Modifier)
                .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 28.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SText(title, size = 17.sp, weight = 800, color = SeedyColors.White)
            if (text != null) SText(text, size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Text2)
            content()
        }
    }
}
