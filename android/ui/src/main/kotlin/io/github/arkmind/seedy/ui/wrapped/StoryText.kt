package io.github.arkmind.seedy.ui.wrapped

import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.drawText
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.NotoLineRatio
import io.github.arkmind.seedy.ui.theme.cssLineBox
import kotlin.math.roundToInt

/* Text and spacing the Wrapped boards' CSS way: negative margins, tight multi-line headings, outlined figures. */

/** CSS margins, negative ones included: the box grows or shrinks by them and the content sits [start], [top] in. */
fun Modifier.margin(start: Dp = 0.dp, top: Dp = 0.dp, end: Dp = 0.dp, bottom: Dp = 0.dp): Modifier = layout { m, c ->
    val s = start.roundToPx()
    val e = end.roundToPx()
    val tp = top.roundToPx()
    val b = bottom.roundToPx()
    val h = s + e
    val inner = c.copy(
        minWidth = (c.minWidth - h).coerceAtLeast(0),
        maxWidth = if (c.hasBoundedWidth) (c.maxWidth - h).coerceAtLeast(0) else c.maxWidth,
        minHeight = (c.minHeight - tp - b).coerceAtLeast(0),
        maxHeight = if (c.hasBoundedHeight) (c.maxHeight - tp - b).coerceAtLeast(0) else c.maxHeight,
    )
    val p = m.measure(inner)
    layout((p.width + h).coerceAtLeast(0), (p.height + tp + b).coerceAtLeast(0)) { p.place(s, tp) }
}

/**
 * A story heading: heavy capitals, tight lines, allowed 6 px into the right margin (a line that just fits on the
 * boards then fits here too).
 */
@Composable
fun Heading(text: String, size: TextUnit, color: Color, modifier: Modifier = Modifier, lineHeight: Float = .98f, tracking: Float = -.03f, maxLines: Int = Int.MAX_VALUE) {
    TightText(AnnotatedString(text.uppercase()), size, 900, color, lineHeight, modifier.margin(end = (-6).dp), tracking = tracking, maxLines = maxLines)
}

/**
 * Text whose lines are closer than the font's own height, laid out as CSS does: Compose keeps the font's full height
 * above the first line and below the last, so the box loses the difference on both ends ([NotoLineRatio]).
 */
@Composable
fun TightText(
    text: AnnotatedString,
    size: TextUnit,
    weight: Int,
    color: Color,
    lineHeight: Float,
    modifier: Modifier = Modifier,
    tracking: Float = 0f,
    align: TextAlign = TextAlign.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    softWrap: Boolean = true,
) {
    BasicText(
        text,
        modifier.layout { m, c ->
            val d = ((NotoLineRatio - lineHeight) / 2f * size.toPx()).roundToInt().coerceAtLeast(0)
            val p = m.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            layout(p.width, (p.height - 2 * d).coerceAtLeast(0)) { p.place(0, -d) }
        },
        style = tightStyle(size, weight, color, lineHeight, tracking, align),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        softWrap = softWrap,
    )
}

private fun tightStyle(size: TextUnit, weight: Int, color: Color, lineHeight: Float, tracking: Float, align: TextAlign): TextStyle =
    BaseText.copy(
        fontSize = size, fontWeight = FontWeight(weight), color = color, lineHeight = lineHeight.em,
        letterSpacing = if (tracking == 0f) TextUnit.Unspecified else tracking.em, textAlign = align,
    )

/** Whether a line of [layout] ends inside a word: the text didn't fit its width, as CSS (which never breaks words) sees it. */
private fun brokeWord(layout: TextLayoutResult): Boolean {
    val text = layout.layoutInput.text.text
    return (0 until layout.lineCount - 1).any { i ->
        val e = layout.getLineEnd(i)
        e in 1 until text.length && text[e - 1].isLetterOrDigit() && text[e].isLetterOrDigit()
    }
}

/**
 * Where [text] breaks when its first line is [inset] px shorter than the [width] px the others get (a float beside
 * it): the first line and the rest, or null when it doesn't fit [lines] lines that way. One line doesn't wrap.
 */
private fun fitLines(measurer: TextMeasurer, text: AnnotatedString, style: TextStyle, width: Int, lines: Int, inset: Int = 0): Pair<AnnotatedString, AnnotatedString?>? {
    if (lines == 1) {
        val one = measurer.measure(text, style, softWrap = false, maxLines = 1)
        return if (one.size.width <= width - inset + 1 && one.lineCount == 1) text to null else null
    }
    val first = measurer.measure(text, style, constraints = Constraints(maxWidth = (width - inset).coerceAtLeast(1)))
    if (brokeWord(first)) return null
    if (first.lineCount == 1) return text to null
    if (inset == 0) return if (first.lineCount <= lines) text to null else null
    val end = first.getLineEnd(0)
    var from = end
    while (from < text.length && text[from].isWhitespace()) from++
    val rest = text.subSequence(from, text.length)
    val after = measurer.measure(rest, style, constraints = Constraints(maxWidth = width))
    return if (!brokeWord(after) && 1 + after.lineCount <= lines) text.subSequence(0, end) to rest else null
}

/** The size, from [max] down by [step], at which [fits] says yes; [min] when none does. */
inline fun fitSize(max: Float, min: Float, step: Float, fits: (Float) -> Boolean): Float {
    var s = max
    while (s > min && !fits(s)) s -= step
    return s.coerceAtLeast(min)
}

/**
 * A board text that steps its size down from [max] (by [step]) until it fits its width in [lines] lines (the web's
 * Fit): one line doesn't wrap, and no word is broken. At [min] it's clamped with an ellipsis. [firstLineInset] keeps
 * the first line that much shorter (the personality's icon floats beside it). Measured and drawn in one layout (no
 * subcomposition), so it can sit in rows that ask for intrinsic sizes.
 */
@Composable
fun FitText(
    text: AnnotatedString,
    max: TextUnit,
    min: TextUnit,
    lines: Int,
    weight: Int,
    color: Color,
    modifier: Modifier = Modifier,
    lineHeight: Float = NotoLineRatio,
    tracking: Float = 0f,
    align: TextAlign = TextAlign.Unspecified,
    step: TextUnit = 1.sp,
    firstLineInset: Dp = 0.dp,
) {
    val measurer = rememberTextMeasurer()
    // What the last measure laid out, for drawing: each block and where it goes.
    val drawn = remember { arrayOfNulls<Pair<TextLayoutResult, Float>>(2) }
    Layout(
        content = {},
        modifier = modifier
            .semantics { this.text = text }
            .drawBehind { for (d in drawn) if (d != null) drawText(d.first, topLeft = Offset(0f, d.second)) },
    ) { _, c ->
        val width = c.maxWidth
        val bounded = c.hasBoundedWidth
        val inset = firstLineInset.roundToPx()
        val style = { sp: Float -> tightStyle(sp.sp, weight, color, lineHeight, tracking, align) }
        val size = if (!bounded) max.value else fitSize(max.value, min.value, step.value) { fitLines(measurer, text, style(it), width, lines, inset) != null }
        val split = if (bounded && inset > 0 && lines > 1) fitLines(measurer, text, style(size), width, lines, inset) else null
        val box = Constraints(minWidth = c.minWidth, maxWidth = c.maxWidth)
        // Lines tighter than the font's own height lose the difference above the first and below the last (CSS).
        val d = ((NotoLineRatio - lineHeight) / 2f * size.sp.toPx()).coerceAtLeast(0f)
        val blocks = if (split?.second != null) {
            listOf(
                measurer.measure(split.first, style(size), overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1, constraints = box),
                measurer.measure(split.second!!, style(size), overflow = TextOverflow.Ellipsis, maxLines = lines - 1, constraints = box),
            )
        } else {
            listOf(measurer.measure(text, style(size), overflow = TextOverflow.Ellipsis, softWrap = lines > 1, maxLines = lines, constraints = box))
        }
        var y = 0f
        drawn.fill(null)
        blocks.forEachIndexed { i, b ->
            drawn[i] = b to (y - d)
            y += b.size.height - 2 * d
        }
        val w = blocks.maxOf { it.size.width }.coerceIn(c.minWidth, c.maxWidth)
        val last = drawn[blocks.lastIndex]!!
        val baselines = mapOf<androidx.compose.ui.layout.AlignmentLine, Int>(
            FirstBaseline to (blocks[0].firstBaseline - d).roundToInt(),
            LastBaseline to (last.second + last.first.lastBaseline).roundToInt(),
        )
        layout(w, y.roundToInt().coerceIn(c.minHeight, c.maxHeight), baselines) {}
    }
}

@Composable
fun FitText(
    text: String, max: TextUnit, min: TextUnit, lines: Int, weight: Int, color: Color, modifier: Modifier = Modifier,
    lineHeight: Float = NotoLineRatio, tracking: Float = 0f, align: TextAlign = TextAlign.Unspecified, step: TextUnit = 1.sp, firstLineInset: Dp = 0.dp,
) = FitText(AnnotatedString(text), max, min, lines, weight, color, modifier, lineHeight, tracking, align, step, firstLineInset)

/**
 * The same in a board-pixel canvas: [text] laid out [width] board px wide, from [max] px down by 1 until it fits
 * [lines] lines, clamped with an ellipsis at [min] ([style]: the text style at a size in board px).
 */
fun fitLayout(measurer: TextMeasurer, text: String, width: Int, lines: Int, max: Float, min: Float, style: (Float) -> TextStyle): TextLayoutResult {
    val a = AnnotatedString(text)
    val px = fitSize(max, min, 1f) { fitLines(measurer, a, style(it), width, lines) != null }
    return measurer.measure(a, style(px), overflow = TextOverflow.Ellipsis, softWrap = lines > 1, maxLines = lines, constraints = Constraints(maxWidth = width))
}

/** Text drawn as an outline only (CSS -webkit-text-stroke on transparent text). */
@Composable
fun OutlineText(text: String, size: TextUnit, color: Color, stroke: Dp, modifier: Modifier = Modifier, lineHeight: Float = 1f, tracking: Float = 0f) {
    val px = with(LocalDensity.current) { stroke.toPx() }
    BasicText(
        text,
        modifier.cssLineBox(size, lineHeight),
        style = BaseText.copy(fontSize = size, fontWeight = FontWeight(900), color = color, letterSpacing = tracking.em, drawStyle = Stroke(px)),
        maxLines = 1,
        softWrap = false,
    )
}


private val LocalRoom = staticCompositionLocalOf { true }

/** A line that shows only when its [StoryArea] has room for it (the web's IfRoom): it goes last in the area. */
@Composable
fun IfRoom(content: @Composable () -> Unit) {
    if (LocalRoom.current) content()
}

/**
 * A story's text area (the space above Medu): the story is laid out at its own height, running on at the bottom if it
 * must, but without its [IfRoom] lines when they'd go past the area. A long list then hides the footnote under it
 * rather than pushing it under Medu.
 */
@Composable
fun StoryArea(modifier: Modifier, content: @Composable () -> Unit) {
    SubcomposeLayout(modifier) { c ->
        val loose = c.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        var placeables = subcompose(true) { CompositionLocalProvider(LocalRoom provides true, content = content) }.map { it.measure(loose) }
        // Up to Medu's box, 14 dp below the area (the boards' "other x%" line sits there), plus the 10 dp before her art
        // and bubble start: Compose's line boxes run a little taller than the boards'.
        if ((placeables.maxOfOrNull { it.height } ?: 0) > c.maxHeight + 24.dp.roundToPx()) {
            placeables = subcompose(false) { CompositionLocalProvider(LocalRoom provides false, content = content) }.map { it.measure(loose) }
        }
        layout(c.maxWidth, c.maxHeight) { placeables.forEach { it.place(0, 0) } }
    }
}

/**
 * A row of parts sharing its width as the boards' content-box flex does: each part [base] wide plus its [grow] share of
 * what's left, but never narrower than its own content (a "34" keeps its digits); the others give way.
 */
@Composable
fun FlexBar(grow: List<Int>, gap: Dp, base: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, c ->
        val g = gap.roundToPx()
        val avail = c.maxWidth - g * (measurables.size - 1)
        val mins = measurables.map { it.minIntrinsicWidth(c.maxHeight) }
        val widths = flexWidths(grow, mins, avail, base.roundToPx())
        val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i].coerceAtLeast(0), c.maxHeight)) }
        layout(c.maxWidth, c.maxHeight) {
            var x = 0
            for (p in placeables) {
                p.place(x, 0)
                x += p.width + g
            }
        }
    }
}

/** CSS flex-grow from a [base] each, with min-content [mins]: parts below their minimum are frozen there, the rest share again. */
internal fun flexWidths(grow: List<Int>, mins: List<Int>, avail: Int, base: Int): List<Int> {
    val n = grow.size
    val w = IntArray(n)
    val frozen = BooleanArray(n)
    repeat(n + 1) {
        val open = (0 until n).filter { !frozen[it] }
        if (open.isEmpty()) return w.toList()
        val sum = open.sumOf { grow[it] }.coerceAtLeast(1)
        val space = avail - (0 until n).filter { frozen[it] }.sumOf { w[it] } - base * open.size
        for (i in open) w[i] = base + (space.toLong() * grow[i] / sum).toInt()
        val low = open.filter { w[it] < mins[it] }
        if (low.isEmpty()) return w.toList()
        for (i in low) {
            w[i] = mins[i]
            frozen[i] = true
        }
    }
    return w.toList()
}

/**
 * Lays its content out at its own height and, when that's taller than the space, scales it down (from the top centre)
 * to fit, at most [scale] whatever the room. What it draws inside (a recorded picture) keeps its own size.
 */
fun Modifier.fitHeight(scale: Float = 1f): Modifier = layout { m, c ->
    val p = m.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    val k = if (c.hasBoundedHeight && p.height * scale > c.maxHeight) c.maxHeight.toFloat() / p.height else scale
    val w = (p.width * k).roundToInt().coerceIn(c.minWidth, c.maxWidth)
    val h = (p.height * k).roundToInt().coerceIn(c.minHeight, c.maxHeight)
    layout(w, h) {
        p.placeWithLayer((w - p.width) / 2, 0) {
            scaleX = k
            scaleY = k
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, 0f)
        }
    }
}
