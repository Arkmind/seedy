package io.github.arkmind.seedy.ui.wrapped

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
fun Heading(text: String, size: TextUnit, color: Color, modifier: Modifier = Modifier, lineHeight: Float = .98f, tracking: Float = -.03f) {
    TightText(AnnotatedString(text.uppercase()), size, 900, color, lineHeight, modifier.margin(end = (-6).dp), tracking = tracking)
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
) {
    BasicText(
        text,
        modifier.layout { m, c ->
            val d = ((NotoLineRatio - lineHeight) / 2f * size.toPx()).roundToInt().coerceAtLeast(0)
            val p = m.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            layout(p.width, (p.height - 2 * d).coerceAtLeast(0)) { p.place(0, -d) }
        },
        style = BaseText.copy(
            fontSize = size, fontWeight = FontWeight(weight), color = color, lineHeight = lineHeight.em,
            letterSpacing = if (tracking == 0f) TextUnit.Unspecified else tracking.em, textAlign = align,
        ),
    )
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

