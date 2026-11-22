package io.github.arkmind.seedy.ui.wrapped

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.wrapped.StoryKey
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.SText
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/* The Wrapped boards' drawn pieces: each story's colours and backdrop, the stand-in landscapes and poster (for titles
   without a picture), the hour-of-day dial, Medu's speech bubble. Drawn in board pixels, scaled by the caller. */

val WrappedInk = Color(0xFF1E1E1E)
val WrappedGreen = Color(0xFFB8F59F)
val WrappedPeach = Color(0xFFFFC98A)
val WrappedBlue = Color(0xFF9FD3FF)
val WrappedLilac = Color(0xFFCDB4FF)
val WrappedNight = Color(0xFF121633)
val WrappedMint = Color(0xFFA8E6DF)
val WrappedSand = Color(0xFFF3E3C3)
val WrappedRust = Color(0xFFE86F4F)

/** A story's colours: its background, the ink of its text and controls, and whether it's a dark story. */
data class StoryLook(val bg: Color, val ink: Color, val brush: Brush? = null) {
    val dark: Boolean get() = ink != WrappedInk
}

/** What each story looks like (boards Wrapped*); the clock's sky follows the part of the day the peak falls in, and
 * Shows or movies? takes the winner's colour ([showsWon]). */
fun storyLook(story: StoryKey, accent: Color, clockKind: String? = null, showsWon: Boolean = true): StoryLook = when (story) {
    StoryKey.Cover, StoryKey.Summary -> StoryLook(Color(0xFF0E0E0E), Color.White)
    StoryKey.Hours -> StoryLook(accent, WrappedInk)
    StoryKey.Lover -> StoryLook(if (showsWon) WrappedBlue else WrappedPeach, WrappedInk)
    StoryKey.Genres -> StoryLook(Color(0xFF121212), Color.White)
    StoryKey.Shows -> StoryLook(WrappedGreen, WrappedInk)
    StoryKey.Movie -> StoryLook(WrappedPeach, WrappedInk)
    StoryKey.Binge -> StoryLook(WrappedBlue, WrappedInk)
    StoryKey.Clock -> when (clockKind) {
        "morning" -> StoryLook(WrappedPeach, WrappedInk)
        "afternoon" -> StoryLook(WrappedBlue, WrappedInk)
        "evening" -> StoryLook(Color(0xFF2A1F4D), Color.White)
        else -> StoryLook(WrappedNight, Color.White)
    }
    StoryKey.Requests -> StoryLook(WrappedMint, WrappedInk)
    StoryKey.Oddest -> StoryLook(WrappedSand, WrappedInk)
    StoryKey.Personality -> StoryLook(WrappedLilac, WrappedInk, cssGradient(160f, 0f to WrappedLilac, .55f to Color(0xFFE6B8FF), 1f to Color(0xFFFF9FFF)))
}

/** CSS linear-gradient([angle]deg, …): 0° runs upwards, 90° to the right, across the whole box whatever its shape. */
fun cssGradient(angle: Float, vararg stops: Pair<Float, Color>): Brush = object : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val a = Math.toRadians(angle.toDouble())
        val dir = Offset(sin(a).toFloat(), -cos(a).toFloat())
        val half = (abs(size.width * sin(a)) + abs(size.height * cos(a))).toFloat() / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        return LinearGradientShader(c - dir * half, c + dir * half, stops.map { it.second }, stops.map { it.first })
    }
}

private fun path(d: String): Path = PathParser().parsePathString(d).toPath()

/** A wavy band across the board, from x −40 to [to], one bump every 40 px, [amp] px high, starting downwards. */
fun wavePath(y: Float, amp: Float, to: Float = 520f): Path = Path().apply {
    moveTo(-40f, y)
    var x = -40f
    var down = true
    while (x < to) {
        quadraticTo(x + 40f, if (down) y + amp else y - amp, x + 80f, y)
        x += 80f
        down = !down
    }
}

/** The Wrapped waves: round-capped bands of [width] px in [color]. */
fun DrawScope.waves(ys: List<Float>, amp: Float, width: Float, color: Color, to: Float = 520f) {
    for (y in ys) drawPath(wavePath(y, amp, to), color, style = Stroke(width, cap = StrokeCap.Round))
}

/** A board bubble: a ring 2 px thick inside a circle [size] px across, top left at [x], [y]. */
private class Bub(val x: Float, val y: Float, val size: Float, val delay: Double)

private fun DrawScope.bubbles(list: List<Bub>, color: Color, t: Double?) {
    for (b in list) {
        val (dy, alpha) = bubble(t, b.delay)
        val r = b.size / 2f
        drawCircle(color, r - 1f, Offset(b.x + r, b.y + r + dy), alpha = alpha, style = Stroke(2f))
    }
}

/** A soft radial light, [d] px across at [x], [y] (top left): closest-side, [color] fading out. */
private fun DrawScope.light(x: Float, y: Float, d: Float, color: Color, alpha: Float, k: Float = 1f) {
    val c = Offset(x + d / 2f, y + d / 2f)
    val r = d / 2f * k
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)), c, r), r, c)
}

private val Bubbles1 = listOf(121f, 182f, 243f, 304f, 365f, 66f, 127f)
private val BubbleYs = listOf(784f, 737f, 690f, 643f, 596f, 749f, 702f)

/** The boards' seven rising bubbles: lefts from [xs], tops and delays in the boards' order, sizes [sizes]. */
private fun bubblesOf(xs: List<Float>, sizes: List<Float>) = xs.indices.map { i -> Bub(xs[i], BubbleYs[i], sizes[i], -1.1 * i) }

private val HoursBubbles = bubblesOf(Bubbles1, listOf(12f, 8f, 18f, 14f, 10f, 6f, 16f))
private val GenresBubbles = bubblesOf(listOf(195f, 256f, 317f, 18f, 79f, 140f, 201f), listOf(16f, 12f, 8f, 18f, 14f, 10f, 6f))
private val ShowsBubbles = bubblesOf(listOf(306f, 367f, 68f, 129f, 190f), listOf(8f, 18f, 14f, 10f, 6f))
private val LoverBubbles = bubblesOf(listOf(158f, 219f, 280f, 341f, 42f), listOf(14f, 10f, 6f, 16f, 12f))
private val BingeBubbles = bubblesOf(listOf(57f, 118f, 179f, 240f, 301f, 362f), listOf(14f, 10f, 6f, 16f, 12f, 8f))
private val PersonalityBubbles = bubblesOf(listOf(84f, 145f, 206f, 267f, 328f, 29f), listOf(10f, 6f, 16f, 12f, 8f, 18f))
private val RequestsBubbles = bubblesOf(listOf(343f, 44f, 105f, 166f, 227f), listOf(10f, 6f, 16f, 12f, 8f))
private val SummaryBubbles = bubblesOf(listOf(232f, 293f, 354f, 55f, 116f, 177f, 238f), listOf(18f, 14f, 10f, 6f, 16f, 12f, 8f))

/** Confetti on the cover: x, y, w, h, corner, colour, turn, delay. */
private class Bit(val x: Float, val y: Float, val w: Float, val h: Float, val r: Float, val color: Color?, val turn: Float, val delay: Double)

private val Confetti = listOf(
    Bit(40f, 150f, 10f, 10f, 2f, null, 20f, 0.0), Bit(330f, 190f, 8f, 18f, 4f, WrappedPeach, -30f, -.6),
    Bit(300f, 560f, 10f, 10f, 5f, WrappedBlue, 0f, -1.2), Bit(56f, 610f, 8f, 16f, 4f, WrappedGreen, 40f, -1.8),
    Bit(190f, 130f, 6f, 6f, 3f, Color.White, 0f, -.9), Bit(350f, 420f, 12f, 12f, 3f, WrappedLilac, 15f, -2.1),
    Bit(24f, 420f, 6f, 6f, 3f, Color.White, 0f, -.3),
)

/** The night board's 22 stars, 2 to 4 px, scattered over its top half. */
private val Stars = (0 until 22).map { i ->
    Bit(((8 + 83 * i) % 370).toFloat(), (40 + (47 * i) % 330).toFloat(), (2 + i % 3).toFloat(), 0f, 0f, null, 0f, -.3667 * i)
}

/**
 * A story's backdrop (boards Wrapped*: waves, bubbles, rings, rays, stars and lights), in a [w] × [h] board-pixel box.
 * What sits low on the board keeps its distance to the bottom ([bottomShift] moves it further down), so it rests on
 * taller screens the way it rests on the boards; [t] is the loop clock (null: at rest). [focus] is where the cover's
 * rings and the movie's rays turn (the TV puts them elsewhere), and the cover's confetti spreads over the box.
 */
fun DrawScope.storyBackdrop(
    story: StoryKey, accent: Color, w: Float, h: Float, t: Double?, clockKind: String? = null, bubbles: Boolean = true, bottomShift: Float = 0f,
    focus: Offset? = null, showsWon: Boolean = true, topShift: Float = 0f, band: Boolean = true,
) {
    val low = h - 844f + bottomShift
    val driftA = { p: Float -> Triple(-18f * p, 14f * p, 4f * p) }
    val driftB = { p: Float -> Triple(16f * p, -12f * p, -5f * p) }
    fun drift(dy: Float, slow: Boolean, block: DrawScope.() -> Unit) {
        val p = if (t == null) 0f else alternate(t, if (slow) 11.0 else 9.0)
        val (x, y, r) = if (slow) driftB(p) else driftA(p)
        translate(0f, dy) { withTransform({ translate(x, y); rotate(r, Offset(195f, 422f)) }, block) }
    }
    when (story) {
        StoryKey.Cover -> {
            val c = focus ?: Offset(195f, 440f)
            val (ga, gk) = glow(t)
            light(c.x - 315f, c.y - 320f, 630f, accent, .28f * ga, gk)
            val turn = if (t == null) 0f else loopAt(t, 40.0) * 360f
            rotate(turn, c) {
                drawCircle(accent.copy(alpha = .35f), 140f, c, style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 10f))))
                drawCircle(accent.copy(alpha = .22f), 200f, c, style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(30f, 14f))))
                drawCircle(accent.copy(alpha = .14f), 262f, c, style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 8f))))
                drawCircle(accent.copy(alpha = .10f), 300f, c, style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(60f, 20f))))
            }
            for (b in Confetti) {
                val x = b.x * w / 390f
                val y = if (focus != null) b.y * h / 844f else b.y + if (b.y > 422f) low else 0f
                rotate(b.turn, Offset(x + b.w / 2f, y + b.h / 2f)) {
                    drawRoundRect(b.color ?: accent, Offset(x, y), Size(b.w, b.h), CornerRadius(b.r), alpha = twinkle(t, b.delay))
                }
            }
        }
        StoryKey.Hours -> {
            drift(low, false) { waves(listOf(560f, 622f, 684f, 746f), 26f, 40f, WrappedInk.copy(alpha = .08f)) }
            drift(0f, true) { waves(listOf(120f, 168f), 20f, 22f, Color.White.copy(alpha = .18f)) }
            if (bubbles) translate(0f, low) { bubbles(HoursBubbles, WrappedInk.copy(alpha = .25f), t) }
        }
        StoryKey.Lover -> {
            drift(0f, true) { waves(listOf(170f, 210f), 18f, 18f, Color.White.copy(alpha = .2f)) }
            // The other side's slanted band, from 548 on the left up to 486 on the right; it follows the content when
            // the status bar is taller than the board's ([topShift]). The TV draws its own layout ([band] off).
            if (band) {
                val left = 548f + topShift
                val right = 486f + topShift
                val p = Path().apply { moveTo(0f, left); lineTo(w, right); lineTo(w, h); lineTo(0f, h); close() }
                drawPath(p, if (showsWon) WrappedPeach else WrappedBlue)
                drawLine(WrappedInk, Offset(0f, left), Offset(w, right), 4f)
            }
            if (bubbles) translate(0f, low) { bubbles(LoverBubbles, WrappedInk.copy(alpha = .2f), t) }
        }
        StoryKey.Genres -> {
            light(w - 280f, -80f, 420f, accent, .18f)
            if (bubbles) translate(0f, low) { bubbles(GenresBubbles, Color.White.copy(alpha = .12f), t) }
        }
        StoryKey.Shows -> {
            drift(low, false) { waves(listOf(700f, 760f, 820f), 24f, 36f, WrappedInk.copy(alpha = .07f)) }
            if (bubbles) translate(0f, low) { bubbles(ShowsBubbles, WrappedInk.copy(alpha = .22f), t) }
        }
        StoryKey.Movie -> rays(focus ?: Offset(195f, 420f), 300f, Color.White.copy(alpha = .14f), t)
        StoryKey.Requests -> {
            drift(low, false) { waves(listOf(690f, 748f, 806f), 22f, 34f, WrappedInk.copy(alpha = .07f)) }
            if (bubbles) translate(0f, low) { bubbles(RequestsBubbles, WrappedInk.copy(alpha = .2f), t) }
        }
        StoryKey.Oddest -> rays(focus ?: Offset(195f, 470f), 320f, WrappedRust.copy(alpha = .08f), t)
        StoryKey.Binge -> {
            drift(0f, true) { waves(listOf(150f, 190f, 230f), 18f, 18f, Color.White.copy(alpha = .22f)) }
            if (bubbles) translate(0f, low) { bubbles(BingeBubbles, WrappedInk.copy(alpha = .2f), t) }
        }
        StoryKey.Clock -> clockSky(clockKind, w, t)
        StoryKey.Personality -> {
            drift(low, false) { waves(listOf(640f, 692f, 744f), 22f, 26f, Color.White.copy(alpha = .2f)) }
            if (bubbles) translate(0f, low) { bubbles(PersonalityBubbles, WrappedInk.copy(alpha = .2f), t) }
            val (ga, gk) = glow(t)
            light(-80f, 180f, 360f, Color.White, .45f * ga, gk)
        }
        StoryKey.Summary -> {
            val (ga, gk) = glow(t)
            light(-100f, 60f, 590f, accent, .2f * ga, gk)
            if (bubbles) translate(0f, low) { bubbles(SummaryBubbles, Color.White.copy(alpha = .12f), t) }
        }
    }
}

/** The movie's and the oddest request's sunburst: 16 thin wedges [r] px long around [c], turning once in 40 s. */
private fun DrawScope.rays(c: Offset, r: Float, color: Color, t: Double?) {
    val turn = if (t == null) 0f else loopAt(t, 40.0) * 360f
    rotate(turn, c) {
        for (i in 0 until 16) {
            val a0 = Math.toRadians(i * 22.5).toFloat()
            val p = Path().apply {
                moveTo(c.x, c.y)
                lineTo(c.x + r * cos(a0), c.y + r * sin(a0))
                arcTo(Rect(c, r), Math.toDegrees(a0.toDouble()).toFloat(), 11.46f, false)
                close()
            }
            drawPath(p, color)
        }
    }
}

/** The clock story's sky: stars and a crescent moon at night (board WrappedNight), a sun by day. */
private fun DrawScope.clockSky(kind: String?, w: Float, t: Double?) {
    val night = kind == null || kind == "night" || kind == "evening"
    if (night) {
        val stars = if (kind == "evening") Stars.filterIndexed { i, _ -> i % 2 == 0 } else Stars
        for (s in stars) drawCircle(Color.White, s.w / 2f, Offset(s.x + s.w / 2f, s.y + s.w / 2f), alpha = twinkle(t, s.delay))
    }
    val (ga, gk) = glow(t)
    val sunColor = if (kind == "morning") Color.White else WrappedPeach
    light(w - 140f, 40f, 240f, sunColor, .35f * ga, gk)
    if (kind == null || kind == "night") {
        // The crescent: a 96 px disc with a 44 px bite out of its upper left.
        val left = w - 80f
        val moon = Path().apply { addOval(Rect(left, 104f, left + 96f, 200f)) }
        val bite = Path().apply { addOval(Rect(Offset(left + 30f, 104f + 34f), 44f)) }
        drawPath(Path.combine(PathOperation.Difference, moon, bite), WrappedPeach)
    } else {
        drawCircle(sunColor, 40f, Offset(w - 32f, 152f))
    }
}

// ── Stand-in pictures ──

/** The boards' drawn landscapes (320 × 180), one per place in a list: sky, sun or moon, hills, foreground. */
private class Scene(val sky: Color, val sun: Offset, val r: Float, val sunColor: Color, val mid: Path, val midColor: Color, val fg: Path, val fgColor: Color)

private val Ridge = "M0 120 L60 80 L110 110 L170 60 L230 105 L320 70 V180 H0Z"
private val Valley = "M0 100 L90 140 L150 95 L220 135 L320 90 V180 H0Z"
private val Shore = "M0 160 Q100 135 200 158 T320 150 V180 H0Z"
private val Rocks = "M0 150 L80 130 L150 150 L230 120 L320 145 V180 H0Z"

private val Scenes by lazy {
    listOf(
        Scene(Color(0xFF24313D), Offset(60f, 36f), 14f, Color(0xFFC9D6E3), path(Ridge), Color(0xFF3B4D5E), path(Shore), Color(0xFF151C23)),
        Scene(Color(0xFF26362B), Offset(202f, 62f), 14f, Color(0xFFD9E3B5), path(Valley), Color(0xFF3B5240), path(Shore), Color(0xFF121A14)),
        Scene(Color(0xFF3F3322), Offset(73f, 45f), 21f, Color(0xFFF2CF86), path(Ridge), Color(0xFF5C4A2C), path(Rocks), Color(0xFF1D170F)),
        Scene(Color(0xFF1F3A3C), Offset(215f, 41f), 21f, Color(0xFFA8E6DF), path(Valley), Color(0xFF2E5457), path(Rocks), Color(0xFF0F1C1D)),
        Scene(Color(0xFF2F2840), Offset(144f, 58f), 14f, Color(0xFFE3C4FF), path("M0 135 Q80 90 160 125 T320 115 V180 H0Z"), Color(0xFF463B5E), path(Shore), Color(0xFF16121F)),
    )
}

/** A drawn landscape standing in for a show's picture: [variant] picks the boards' scene (0 is the #1 show's). */
@Composable
fun Landscape(variant: Int, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = Scenes[((variant % Scenes.size) + Scenes.size) % Scenes.size]
        // preserveAspectRatio xMidYMid slice: fill the box, centred, cropped.
        val k = max(size.width / 320f, size.height / 180f)
        clipRect {
            translate((size.width - 320f * k) / 2f, (size.height - 180f * k) / 2f) {
                scale(k, k, Offset.Zero) {
                    drawRect(s.sky, size = Size(320f, 180f))
                    drawCircle(s.sunColor, s.r, s.sun)
                    drawPath(s.mid, s.midColor)
                    drawPath(s.fg, s.fgColor)
                }
            }
        }
    }
}

private val PosterHills by lazy {
    path("M0 380 C90 330 170 350 240 380 C310 410 360 360 400 370 V600 H0Z") to path("M0 460 C110 420 220 450 300 470 C350 482 380 470 400 472 V600 H0Z")
}

/** The movie board's drawn poster (400 × 600): a moon over dark hills, paper lanterns, the title in capitals. */
@Composable
fun PosterArt(title: String, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    Canvas(modifier) {
        val k = min(size.width / 400f, size.height / 600f)
        scale(k, k, Offset.Zero) {
            drawRect(Color(0xFF2A1519), size = Size(400f, 600f))
            drawCircle(Color(0xFFF3B69A), 120f, Offset(270f, 200f), alpha = .12f)
            drawCircle(Color(0xFFF3B69A), 58f, Offset(270f, 200f))
            drawPath(PosterHills.first, Color(0xFF4A2228))
            drawPath(PosterHills.second, Color(0xFF1F0E12))
            for ((x, y, a) in listOf(Triple(70f, 120f, .9f), Triple(130f, 170f, .75f), Triple(190f, 96f, .85f), Triple(52f, 236f, .6f))) {
                drawLine(Color(0xFF3A1A1F), Offset(x, y), Offset(x, y + 16f), 3f)
                drawRoundRect(Color(0xFFFFB38A), Offset(x - 14f, y + 16f), Size(28f, 38f), CornerRadius(12f), alpha = a)
            }
            // The title, as big as fits (40 px on the board), its baseline at 548.
            val style = boardText(density, 40f, 900, Color.White, tracking = 1f)
            var layout = measurer.measure(title.uppercase(), style, maxLines = 1, softWrap = false)
            if (layout.size.width > 370f) {
                layout = measurer.measure(title.uppercase(), boardText(density, 40f * 370f / layout.size.width, 900, Color.White, tracking = 1f), maxLines = 1, softWrap = false)
            }
            drawText(layout, topLeft = Offset(200f - layout.size.width / 2f, 548f - layout.firstBaseline))
        }
    }
}

/** Where the oddest request's poster has its stars: x, y, radius, opacity (the board's 26). */
private val RetroStars = (0 until 26).map { i -> floatArrayOf(((i * 97) % 400).toFloat(), ((i * 61) % 330).toFloat(), listOf(1.5f, 2.5f, 3.5f)[i % 3], listOf(.4f, .6f, .8f)[i % 3]) }

private val RetroRocket by lazy { path("M60 330 l26 -60 l26 60 z") }

/**
 * Board WrappedOddest's retro poster (400 × 600), for a title without one: a ringed planet and a little rocket under
 * the stars, the title in capitals across the bottom (52 px, on up to three lines).
 */
@Composable
fun RetroPoster(title: String, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    Canvas(modifier) {
        val k = min(size.width / 400f, size.height / 600f)
        scale(k, k, Offset.Zero) {
            drawRect(Color(0xFF1F2A44), size = Size(400f, 600f))
            for (s in RetroStars) drawCircle(WrappedSand, s[2], Offset(s[0], s[1]), alpha = s[3])
            drawCircle(WrappedRust, 118f, Offset(220f, 230f))
            drawArc(Color(0xFFC9553A), 0f, 180f, true, Offset(102f, 112f), Size(236f, 236f))
            rotate(-14f, Offset(220f, 236f)) {
                drawOval(WrappedSand, Offset(220f - 178f, 236f - 34f), Size(356f, 68f), style = Stroke(10f))
            }
            drawPath(RetroRocket, WrappedSand)
            drawRect(WrappedSand, Offset(76f, 330f), Size(20f, 18f))
            // The title in the bottom 360 × 180, centred, as big as fits up to the web's 52 px.
            var px = 52f
            var layout = measurer.measure(title.uppercase(), boardText(density, px, 900, WrappedSand, tracking = -1f).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 1.02.em), constraints = androidx.compose.ui.unit.Constraints(maxWidth = 360), maxLines = 3)
            while ((layout.hasVisualOverflow || layout.size.height > 180) && px > 24f) {
                px -= 4f
                layout = measurer.measure(title.uppercase(), boardText(density, px, 900, WrappedSand, tracking = -1f).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 1.02.em), constraints = androidx.compose.ui.unit.Constraints(maxWidth = 360), maxLines = 3)
            }
            drawText(layout, topLeft = Offset(20f + (360f - layout.size.width) / 2f, 400f + (180f - layout.size.height) / 2f))
        }
    }
}

/** Text measured in board pixels for drawing in a board-scaled canvas ([px] board px tall). */
fun boardText(density: androidx.compose.ui.unit.Density, px: Float, weight: Int, color: Color, tracking: Float = 0f): TextStyle =
    BaseText.copy(
        fontSize = with(density) { px.toSp() },
        fontWeight = FontWeight(weight),
        color = color,
        letterSpacing = if (tracking == 0f) TextUnit.Unspecified else (tracking / px).em,
    )

// ── The clock dial ──

/**
 * Board WrappedNight's dial (a 300 px box): 24 bars around a 62 px hole, one per hour from midnight at the top, each
 * 6.6 + 63.4·its share of the busiest hour long; the five hours around the peak in [hot], the rest in [cold]. The hours
 * 00, 06, 12, 18 around it and the peak time in the middle. Each bar draws itself in from the hole as the story opens.
 */
@Composable
fun ClockDial(hours: List<Long>, peak: String, peakLabel: String, hot: Color, cold: Color, ink: Color, labels: Color, sub: Color, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val time = LocalStoryTime.current
    val top = remember(hours) { hours.maxOrNull()?.takeIf { it > 0 } ?: 1L }
    val peakHour = peak.take(2).toIntOrNull() ?: hours.indices.maxByOrNull { hours[it] } ?: 0
    val text = remember(density, ink, labels, sub, peak, peakLabel) {
        val l = boardText(density, 11f, 800, labels)
        Triple(
            listOf("00", "06", "12", "18").map { measurer.measure(it, l) },
            measurer.measure(peak, boardText(density, 34f, 900, ink, tracking = -1f)),
            measurer.measure(peakLabel, boardText(density, 12f, 700, sub)),
        )
    }
    Canvas(modifier) {
        val k = size.width / 300f
        scale(k, k, Offset.Zero) {
            val c = Offset(150f, 150f)
            for (h in 0 until 24) {
                val v = hours.getOrElse(h) { 0L }
                val len = 6.6f + 63.4f * v / top
                val a = Math.toRadians(h * 15.0)
                val dir = Offset(sin(a).toFloat(), -cos(a).toFloat())
                val from = c + dir * 62f
                val drawn = enter(time?.floatValue, .5f + h * .0352f, .9f)
                val to = c + dir * (62f + len * drawn)
                val near = ((h - peakHour + 24) % 24).let { it <= 2 || it >= 22 }
                if (drawn > 0f) drawLine(if (near) hot else cold, from, to, 9f, StrokeCap.Round)
            }
            val (marks, big, small) = text
            val spots = listOf(Offset(150f, 5f), Offset(295f, 150f), Offset(150f, 295f), Offset(5f, 150f))
            marks.forEachIndexed { i, m -> drawText(m, topLeft = spots[i] - Offset(m.size.width / 2f, m.size.height / 2f)) }
            drawText(big, topLeft = Offset(150f - big.size.width / 2f, 146f - big.firstBaseline))
            drawText(small, topLeft = Offset(150f - small.size.width / 2f, 168f - small.firstBaseline))
        }
    }
}

// ── The odometer ──

/**
 * The hours board's .odo: [text] in a box one [size] tall (overflow hidden, faded top and bottom), each digit a column
 * of 0–9 rolling up to it (1.8 s, each a little after the one before); other characters stay put. Figures are tabular.
 */
@Composable
fun Odometer(text: String, size: TextUnit, color: Color, modifier: Modifier = Modifier, tracking: Float = 0f, delay: Float = .2f, step: Float = .08f) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val time = LocalStoryTime.current
    val style = BaseText.copy(fontSize = size, fontWeight = FontWeight(900), color = color, fontFeatureSettings = "tnum", letterSpacing = if (tracking == 0f) TextUnit.Unspecified else tracking.em)
    val whole = remember(text, style, density) { measurer.measure(text, style, maxLines = 1, softWrap = false) }
    val glyphs = remember(style, density) { (0..9).map { measurer.measure(it.toString(), style.copy(letterSpacing = TextUnit.Unspecified)) } }
    val em = with(density) { size.toPx() }
    // Room on the right for the last glyph, which overhangs its box under tight tracking (it was clipped).
    val widthDp = with(density) { (whole.size.width + em * .12f).toDp() }
    val heightDp = with(density) { size.toDp() }
    Canvas(modifier.size(widthDp, heightDp).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        // The line box is one em: the glyphs sit centred in it as CSS centres them in a short line.
        val shift = (em - whole.size.height) / 2f
        val digits = text.indices.filter { text[it].isDigit() }
        clipRect {
            if (time == null) {
                drawText(whole, topLeft = Offset(0f, shift))
            } else {
                for (i in text.indices) {
                    val x = whole.getBoundingBox(i).left
                    if (!text[i].isDigit()) {
                        drawText(measurer.measure(text[i].toString(), style.copy(letterSpacing = TextUnit.Unspecified)), topLeft = Offset(x, shift))
                        continue
                    }
                    val d = text[i] - '0'
                    val rolled = d * enter(time.floatValue, delay + step * digits.indexOf(i), 1.8f, OdoEase)
                    for (k in 0..9) {
                        val y = shift + (k - rolled) * em
                        if (y > -em && y < em) drawText(glyphs[k], topLeft = Offset(x, y))
                    }
                }
            }
        }
        drawRect(Brush.verticalGradient(0f to Color.Transparent, .09f to Color.Black, .91f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
    }
}

// ── Medu's speech bubble ──

/**
 * What Medu says (the boards' .say): a rounded bubble, its lines balanced, with a little diamond tail halfway down the
 * side Medu is on (the end when [tailEnd]), [tailOut] past the edge. Sizes are the phone board's unless given.
 */
@Composable
fun Say(
    text: String,
    bg: Color,
    ink: Color,
    tailEnd: Boolean,
    modifier: Modifier = Modifier,
    size: TextUnit = 14.sp,
    maxWidth: Dp = 206.dp,
    padX: Dp = 14.dp,
    padY: Dp = 10.dp,
    radius: Dp = 18.dp,
    tail: Dp = 14.dp,
    tailOut: Dp = 5.dp,
    tailRadius: Dp = 3.dp,
) {
    Box(
        modifier
            .drawBehind {
                val s = tail.toPx()
                val x = if (tailEnd) this.size.width + tailOut.toPx() - s else -tailOut.toPx()
                val y = (this.size.height - s) / 2f
                rotate(45f, Offset(x + s / 2f, y + s / 2f)) {
                    drawRoundRect(bg, Offset(x, y), Size(s, s), CornerRadius(tailRadius.toPx()))
                }
            }
            // CSS max-width is the text's, inside the padding.
            .widthIn(max = maxWidth + padX * 2)
            .background(bg, RoundedCornerShape(radius))
            .padding(horizontal = padX, vertical = padY),
    ) {
        SText(text, size = size, weight = 800, color = ink, lineHeight = 1.3f, balance = true)
    }
}

/** A board canvas: [draw] in board pixels ([unit] dp each), its size in board pixels given. */
@Composable
fun BoardCanvas(modifier: Modifier, unit: Float = 1f, draw: DrawScope.(w: Float, h: Float) -> Unit) {
    Canvas(modifier) {
        val k = unit * density
        scale(k, k, Offset.Zero) { draw(size.width / k, size.height / k) }
    }
}
