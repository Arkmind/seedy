package io.github.arkmind.seedy.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import io.github.arkmind.seedy.core.settings.ResolvedStyle
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.NotoLineRatio
import io.github.arkmind.seedy.ui.theme.NotoSans
import kotlin.math.roundToInt

/**
 * A subtitle line in a [ResolvedStyle] (Settings · Subtitles, the web's cueCss): font, weight (Normal is the player
 * boards' 600, Bold the Subtitles board's 800), colour, background behind the words, and the outline — the boards'
 * two-layer shadow, an 8-way outline, or a raised edge. Lines are centred, 1.3 × the size apart.
 */
@Composable
fun SubtitleLine(text: String, style: ResolvedStyle, size: TextUnit, modifier: Modifier = Modifier, preview: Boolean = true, boardPx: Float? = null) {
    val density = LocalDensity.current
    val px = with(density) { size.toPx() }
    // One board pixel: a dp on the phone boards, half of one on the TV boards.
    val u = boardPx ?: density.density
    val base = BaseText.copy(
        fontSize = size,
        fontWeight = FontWeight(if (style.weight == "bold") 800 else 600),
        fontFamily = when (style.font) {
            "system" -> FontFamily.Default
            "print" -> FontFamily.Serif
            "typewriter", "console" -> FontFamily.Monospace
            else -> NotoSans
        },
        fontFeatureSettings = if (style.font == "smallcaps") "smcp" else null,
        color = Color(style.colorArgb()),
        background = Color(style.backgroundArgb()),
        textAlign = TextAlign.Center,
        lineHeight = 1.3.em,
    )
    // Blur radii as CSS gives them (sigma = blur / 2); Android's shadow radius r gives sigma = 0.577 r + 0.5.
    fun blur(css: Float) = ((css / 2f - 0.5f) / 0.57735f).coerceAtLeast(0.01f)
    val layers: List<TextStyle> = when (style.outline) {
        "none" -> listOf(base)
        "outline" -> listOf(
            base.copy(color = Color.Black, background = Color.Transparent, drawStyle = Stroke(width = px * 0.11f, join = StrokeJoin.Round)),
            base.copy(background = Color.Transparent),
        )
        "raised" -> listOf(
            base.copy(shadow = Shadow(Color.White.copy(alpha = 0.4f), Offset(-0.035f * px, -0.035f * px), 0.01f)),
            base.copy(background = Color.Transparent, shadow = Shadow(Color.Black.copy(alpha = 0.75f), Offset(0.09f * px, 0.09f * px), blur(0.03f * px))),
            base.copy(background = Color.Transparent, shadow = Shadow(Color.Black, Offset(0.045f * px, 0.045f * px), 0.01f)),
        )
        // The Subtitles board's preview shadow is lighter than the player's.
        else -> listOf(
            base.copy(shadow = Shadow(Color.Black, Offset.Zero, blur(2f * u))),
            base.copy(background = Color.Transparent, shadow = Shadow(Color.Black.copy(alpha = 0.9f), Offset(0f, 2f * u), blur((if (preview) 4f else 8f) * u))),
        )
    }
    Box(modifier.cssLines(px, 1.3f), contentAlignment = Alignment.TopCenter) {
        // The outline layers lie under the line itself; only the first one draws the background.
        layers.forEachIndexed { i, s -> BasicText(text, style = if (i == 0) s else s.copy(background = Color.Transparent)) }
    }
}

/** One line of [ratio] × size, like CSS (Compose never makes a line shorter than the font's own height). */
private fun Modifier.cssLines(px: Float, ratio: Float): Modifier = this.layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val natural = NotoLineRatio * px
    val lines = (p.height / natural).roundToInt().coerceAtLeast(1)
    if (lines > 1) return@layout layout(p.width, p.height) { p.place(0, 0) }
    val cut = ((NotoLineRatio - ratio) * px).coerceAtLeast(0f)
    val h = (p.height - cut).roundToInt().coerceAtLeast(0)
    layout(p.width, h) { p.place(0, -(cut / 2).roundToInt()) }
}

/** The subtitle previews' scene (Settings · Subtitles boards): a moonlit pass, the boards' 1600 × 900 SVG drawn with `slice`. */
@Composable
fun PassScene(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.background(Color(0xFF0D141C))) {
        Canvas(Modifier.fillMaxSize()) { drawPass() }
        content()
    }
}

private fun DrawScope.drawPass() {
    val k = maxOf(size.width / 1600f, size.height / 900f)
    val dx = (size.width - 1600f * k) / 2
    val dy = (size.height - 900f * k) / 2
    fun p(x: Float, y: Float) = Offset(dx + x * k, dy + y * k)
    drawCircle(Color(0xFFDFE7EF).copy(alpha = 0.06f), 110f * k, p(420f, 230f))
    drawCircle(Color(0xFFDFE7EF), 46f * k, p(420f, 230f))
    fun ridge(points: List<Pair<Float, Float>>, color: Color) {
        val path = Path().apply {
            val (x0, y0) = points.first()
            p(x0, y0).let { moveTo(it.x, it.y) }
            for ((x, y) in points.drop(1)) p(x, y).let { lineTo(it.x, it.y) }
            p(1600f, 900f).let { lineTo(it.x, it.y) }
            p(0f, 900f).let { lineTo(it.x, it.y) }
            close()
        }
        drawPath(path, color)
    }
    ridge(listOf(0f to 560f, 200f to 420f, 360f to 520f, 560f to 360f, 760f to 500f, 940f to 410f, 1120f to 520f, 1320f to 380f, 1600f to 480f), Color(0xFF1D2A38))
    ridge(listOf(0f to 660f, 260f to 580f, 480f to 640f, 720f to 560f, 980f to 640f, 1240f to 580f, 1600f to 640f), Color(0xFF141E29))
    ridge(listOf(0f to 780f, 360f to 710f, 700f to 760f, 1040f to 700f, 1380f to 750f, 1600f to 720f), Color(0xFF0A0F15))
}
