package io.github.arkmind.seedy.ui.theme

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.ui.R
import kotlin.math.roundToInt

/** Noto Sans, one variable font file for every weight the boards use (400–900). */
val NotoSans = FontFamily(
    listOf(400, 500, 600, 700, 800, 900).map { w ->
        Font(R.font.noto_sans, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

/** The server's accent colour (Seedy admin · Branding); the Foundations pink when the server has no Seedy. */
val LocalAccent = staticCompositionLocalOf { SeedyColors.Accent }

/** Base text: line height from the font itself, like CSS `line-height: normal` on the boards. */
val BaseText = TextStyle(
    fontFamily = NotoSans,
    color = SeedyColors.Text,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    // Exact (fractional) glyph advances, like the boards' browser, rather than whole pixels: lines wrap where the
    // boards wrap them.
    textMotion = TextMotion.Animated,
)

@Composable
fun SeedyTheme(accent: Color = SeedyColors.Accent, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAccent provides accent, content = content)
}

/** "#ff9fff" → Color, or null for anything else. */
fun parseHexColor(hex: String?): Color? {
    val h = hex?.removePrefix("#") ?: return null
    if (h.length != 6) return null
    return h.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
}

/** Noto Sans's natural line height (hhea ascent 1069 + descent 293, per 1000): CSS `line-height: normal`. */
const val NotoLineRatio = 1.362f

/**
 * Text as the boards write it: a size, a weight and a colour. Letter spacing is in em, like the boards' CSS
 * (`letter-spacing: -0.01em` → `tracking = -0.01f`); line height is a multiple of the size when given.
 *
 * A one-line text tighter than the font's own height (a heading's `line-height: 1.2`) is boxed the CSS way: the box
 * loses the difference and the glyphs stay centred in it. Text that wraps takes the line height itself, so its lines
 * close up as on the boards.
 */
@Composable
fun SText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 14.sp,
    weight: Int = 400,
    color: Color = SeedyColors.Text,
    tracking: Float = 0f,
    lineHeight: Float? = null,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    decoration: TextDecoration? = null,
    uppercase: Boolean = false,
) {
    val tight = lineHeight != null && lineHeight < NotoLineRatio
    // Whether the text took more than one line; it then takes the line height itself.
    var wraps by remember(text) { mutableStateOf(false) }
    val boxed = tight && !wraps
    BasicText(
        text = if (uppercase) text.uppercase() else text,
        modifier = if (boxed) modifier.cssLineBox(size, lineHeight!!) else modifier,
        style = BaseText.copy(
            fontSize = size,
            fontWeight = FontWeight(weight),
            color = color,
            letterSpacing = if (tracking == 0f) TextUnit.Unspecified else tracking.em,
            lineHeight = if (lineHeight != null && !boxed) lineHeight.em else TextUnit.Unspecified,
            textAlign = align ?: TextAlign.Unspecified,
            textDecoration = decoration,
        ),
        onTextLayout = if (tight && maxLines != 1) { r -> if (r.lineCount > 1) wraps = true } else null,
        maxLines = maxLines,
        overflow = overflow,
        softWrap = maxLines != 1,
    )
}

/** Shrinks a text box from Noto's natural line height to [ratio] × [size], keeping the glyphs centred. */
private fun Modifier.cssLineBox(size: TextUnit, ratio: Float): Modifier = this.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val px = size.toPx()
    val cut = ((NotoLineRatio - ratio) * px).coerceAtLeast(0f)
    val height = (placeable.height - cut).roundToInt().coerceAtLeast(0)
    layout(placeable.width, height) { placeable.place(0, -(cut / 2).roundToInt()) }
}
