package io.github.arkmind.seedy.ui.components

import android.graphics.BlurMaskFilter
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.Ease
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.nativePaint
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import kotlin.math.floor
import kotlin.math.min

/** Medu's moods (board "Brand · Mascot"): each has its face and its own small loop on top of the idle float. */
enum class MeduMood {
    /** The idle float, pulse and blink: the screensaver. */
    Idle,

    /** Hello: a waving arm (sign-in, a new profile). */
    Wave,

    /** Lost: a tilting question mark, a tear, eyes looking around, and a sweeping torch when asked for. */
    Search,

    /** Sleepy: closed eyes and rising z's, at half speed (empty lists, a sleep timer). */
    Sleepy,

    /** Yay: a grin and sparkles, at double speed (something is ready). */
    Yay,

    /** Thinking: bouncing dots over the bell (a long wait). */
    Think,

    /** Oops: a dropped plug swinging from a tentacle (the server can't be reached). */
    Oops,
}

/**
 * Whether looping decorations hold still: the system's animations are off (Settings › Accessibility › Remove
 * animations, or a duration scale of 0), a preview, or a screenshot test. They then show their rest pose.
 */
@Composable
fun rememberStillMotion(): Boolean {
    if (LocalInspectionMode.current || Build.FINGERPRINT == "robolectric") return true
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * Nanoseconds since this first showed, ticking every frame, or null while motion is off ([rememberStillMotion]).
 * Read it while drawing (or in a graphicsLayer block) so only the drawing follows the clock.
 */
@Composable
fun rememberLoopClock(): MutableLongState? {
    if (rememberStillMotion()) return null
    val nanos = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withInfiniteAnimationFrameNanos { nanos.longValue = it - start }
    }
    return nanos
}

/** A loop clock's time in seconds; null (rest pose) without one. */
fun MutableLongState?.seconds(): Double? = this?.longValue?.let { it / 1e9 }

/**
 * Medu, the jellyfish (board "Brand · Mascot"), drawn from the boards' SVG (`viewBox="0 -24 200 264"`) at [width],
 * its height 1.32× that unless given (the boards round it). The full detail has six tentacles, the bell's pulse, its
 * spots, highlight and blink, and a soft glow of the accent behind it; [small] is the under-80 px Medu: three thick
 * tentacles, bigger eyes, no face details, no pulse. The moods' loops run as the boards' CSS keyframes; with motion
 * off Medu holds still in its pose. Hidden from screen readers: the text beside it says what happened.
 */
@Composable
fun Medu(
    mood: MeduMood,
    width: Dp,
    modifier: Modifier = Modifier,
    height: Dp = width * 1.32f,
    small: Boolean = false,
    glow: Boolean = !small,
    torch: Boolean = true,
    bubbles: Boolean = false,
    accent: Color = LocalAccent.current,
) {
    val clock = rememberLoopClock()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // The "?" and z's are laid out in viewBox units (a unit is a pixel before Medu's scale).
    val texts = remember(measurer, density) {
        fun glyph(c: String, units: Float) = measurer.measure(
            c,
            BaseText.copy(fontSize = with(density) { units.toSp() }, fontWeight = FontWeight(900)),
        )
        MeduTexts(question = glyph("?", 46f), z = listOf(glyph("z", 20f), glyph("z", 28f), glyph("z", 22f)))
    }
    Canvas(modifier.size(width, height)) {
        val s = min(size.width / 200f, size.height / 264f)
        translate((size.width - 200f * s) / 2f, (size.height - 264f * s) / 2f + 24f * s) {
            scale(s, s, Offset.Zero) {
                drawMedu(mood, clock.seconds(), accent, small, glow, torch, bubbles, texts)
            }
        }
    }
}

private class MeduTexts(val question: TextLayoutResult, val z: List<TextLayoutResult>)

/**
 * Medu in its rest pose as a bitmap of [width] × [height] pixels, for where Compose doesn't draw (a notification's
 * large icon). The question mark and z's need text, so Search and Sleepy come without them.
 */
fun meduBitmap(
    mood: MeduMood,
    width: Int,
    height: Int,
    accent: Color,
    small: Boolean = false,
    glow: Boolean = false,
    torch: Boolean = false,
): ImageBitmap {
    val bitmap = ImageBitmap(width, height)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bitmap), Size(width.toFloat(), height.toFloat())) {
        val s = min(size.width / 200f, size.height / 264f)
        translate((size.width - 200f * s) / 2f, (size.height - 264f * s) / 2f + 24f * s) {
            scale(s, s, Offset.Zero) { drawMedu(mood, null, accent, small, glow, torch, false, null) }
        }
    }
    return bitmap
}

// ---- Drawing, in the SVG's own units --------------------------------------------------------------------------

private val Body = Color(0xFFEFDFF3)
private val Ink = Color(0xFF1E1E1E)
private val Frill = Color(0xFFD6B3DE)
private val Tear = Color(0xFF9FD3FF)
private val Light = Color(0xFFFFF4D6)
private val Mist = Color(0xFFE0E0E0)

private fun p(d: String): Path = PathParser().parsePathString(d).toPath()

/** A tentacle: its path, the top centre of its box (it swings from there), its delay, ink and width. */
private class Tentacle(val path: Path, val pivot: Offset, val delay: Double, val accent: Boolean, val width: Float)

private object Art {
    val bell = p("M30 108 C30 52 66 22 100 22 C134 22 170 52 170 108 Q156 124 142 110 Q128 124 114 110 Q100 124 86 110 Q72 124 58 110 Q44 124 30 108 Z")
    val frill = p("M30 108 Q44 124 58 110 Q72 124 86 110 Q100 124 114 110 Q128 124 142 110 Q156 124 170 108")
    val bodyFill = Brush.verticalGradient(listOf(Color(0xFFFDF6FE), Color(0xFFEBD3F0)), startY = 22f, endY = 117f)

    private val a = p("M48 112 C40 136 56 152 46 174 C38 192 50 206 44 220")
    private val d = p("M152 112 C160 136 144 152 154 174 C162 192 150 206 156 220")
    val full = listOf(
        Tentacle(a, Offset(46.277f, 112f), -.4, false, 6f),
        Tentacle(p("M70 117 C64 142 78 160 68 184 C62 200 72 214 66 228"), Offset(68.861f, 117f), -1.2, false, 6f),
        Tentacle(p("M130 117 C136 142 122 160 132 184 C138 200 128 214 134 228"), Offset(131.139f, 117f), -.8, false, 6f),
        Tentacle(d, Offset(153.723f, 112f), -1.6, false, 6f),
        Tentacle(p("M90 116 C82 132 98 144 88 160 C80 174 94 182 88 196"), Offset(88.433f, 116f), -2.0, true, 8f),
        Tentacle(p("M110 116 C118 132 102 144 112 160 C120 174 106 182 112 196"), Offset(111.567f, 116f), -.8, true, 8f),
    )
    /** Without the fourth: it holds the torch, or dropped the plug. */
    val busy = full.filterIndexed { i, _ -> i != 3 }
    val small = listOf(
        Tentacle(a, Offset(46.277f, 112f), -.4, false, 12f),
        Tentacle(p("M100 118 C94 144 108 162 98 190 C92 206 102 218 98 230"), Offset(98.875f, 118f), -.8, false, 12f),
        Tentacle(d, Offset(153.723f, 112f), -1.2, false, 12f),
    )

    val waveArm = p("M163 96 C180 92 188 76 180 60")
    val beam = p("M30 -6 L640 -160 L640 160 L30 6 Z")
    val torchArm = p("M152 112 C156 126 166 133 192 131")
    val plugTentacle = p("M152 112 C160 136 146 150 156 170 C162 182 168 190 170 200")
    val cable = p("M170 200 C172 212 160 218 166 230")
    val prongs = p("M161 239v6M171 239v6")
    val tear = p("M150 62 C150 62 143 72 143 77 a7 7 0 0 0 14 0 C157 72 150 62 150 62 Z")
    val spark = p("M0 -10 C1.5 -2 2 -1.5 10 0 C2 1.5 1.5 2 0 10 C-1.5 2 -2 1.5 -10 0 C-2 -1.5 -1.5 -2 0 -10 Z")

    val smile = p("M93 95 Q100 102 107 95")
    val searchBrowL = p("M71 69 Q78 63 86 66")
    val searchBrowR = p("M114 64 Q121 61 129 66")
    val wobble = p("M93 99 q3.5 -4 7 0 t7 0")
    val sleepL = p("M73 86 Q80 92 87 86")
    val sleepR = p("M113 86 Q120 92 127 86")
    val happyL = p("M73 88 Q80 79 87 88")
    val happyR = p("M113 88 Q120 79 127 88")
    val grin = p("M89 94 Q100 112 111 94 Z")
    val tongue = p("M95 101 Q100 106 105 101 Q100 98 95 101 Z")
    val flat = p("M94 98 L106 97")
    val oopsBrowL = p("M70 72 L86 67")
    val oopsBrowR = p("M130 72 L114 67")
    val frown = p("M92 101 Q100 94 108 101")

    /** The beam's light, along its length; it's blurred (stdDeviation 5) on the boards. */
    val beamPaint: Paint by lazy {
        Paint().apply {
            shader = LinearGradientShader(
                Offset(30f, 0f), Offset(640f, 0f),
                listOf(Light.copy(alpha = .55f), Light.copy(alpha = .2f), Light.copy(alpha = 0f)),
                listOf(0f, .35f, 1f),
            )
            // Android turns a blur radius into sigma = radius × 0.57735 + 0.5; the filter follows the scale.
            nativePaint.maskFilter = BlurMaskFilter((5f - .5f) / .57735f, BlurMaskFilter.Blur.NORMAL)
        }
    }
}

// ---- The boards' keyframes ------------------------------------------------------------------------------------

/** How far into its loop (0..1) an animation of [dur] seconds with CSS `animation-delay: [delay]` is at [t]. */
internal fun loop(t: Double, dur: Double, delay: Double = 0.0): Float {
    val x = (t - delay) / dur
    return (x - floor(x)).toFloat()
}

/** A CSS keyframe track: [values] at offsets [at], [ease] applied within each step. */
internal fun track(p: Float, at: FloatArray, values: FloatArray, ease: Easing): Float {
    var i = 0
    while (i < at.size - 2 && p > at[i + 1]) i++
    val span = at[i + 1] - at[i]
    val f = if (span <= 0f) 1f else ((p - at[i]) / span).coerceIn(0f, 1f)
    return values[i] + (values[i + 1] - values[i]) * ease.transform(f)
}

private val Half = floatArrayOf(0f, .5f, 1f)

private fun swing(p: Float, from: Float, to: Float) = track(p, Half, floatArrayOf(from, to, from), EaseInOut)

private fun DrawScope.stroke(path: Path, color: Color, width: Float, cap: StrokeCap = StrokeCap.Round, join: StrokeJoin = StrokeJoin.Miter) =
    drawPath(path, color, style = Stroke(width, cap = cap, join = join))

private fun DrawScope.drawMedu(
    mood: MeduMood,
    t: Double?,
    accent: Color,
    small: Boolean,
    glow: Boolean,
    torch: Boolean,
    bubbles: Boolean,
    texts: MeduTexts?,
) {
    // m-slow / m-fast: Sleepy floats at half speed, Yay at double.
    val (floatDur, tentDur) = when (mood) {
        MeduMood.Sleepy -> 5.5 to 4.0
        MeduMood.Yay -> 1.6 to 1.2
        else -> 3.2 to 2.4
    }
    if (glow) {
        drawCircle(
            Brush.radialGradient(listOf(accent.copy(alpha = .32f), accent.copy(alpha = 0f)), center = Offset(100f, 96f), radius = 108f),
            radius = 108f, center = Offset(100f, 96f),
        )
    }
    if (bubbles) {
        for ((x, y, r, delay) in listOf(Bub(26f, 196f, 5f, -.6), Bub(176f, 170f, 7f, -2.1), Bub(160f, 226f, 4f, -3.3), Bub(40f, 140f, 3.5f, -1.4))) {
            var dy = 0f
            var alpha = 1f
            if (t != null) {
                val q = loop(t, 4.5, delay)
                dy = -110f * EaseIn.transform(q)
                alpha = track(q, floatArrayOf(0f, .2f, 1f), floatArrayOf(0f, .75f, 0f), EaseIn)
            }
            drawCircle(Color.White.copy(alpha = .6f * alpha), r, Offset(x, y + dy), style = Stroke(2f))
        }
    }
    val bob = if (t == null) 0f else swing(loop(t, floatDur), 0f, -8f)
    translate(0f, bob) {
        val tentacles = when {
            small -> Art.small
            (mood == MeduMood.Search && torch) || mood == MeduMood.Oops -> Art.busy
            else -> Art.full
        }
        for (tn in tentacles) {
            val angle = if (t == null) 0f else swing(loop(t, tentDur, tn.delay), -5f, 5f)
            rotate(angle, tn.pivot) {
                stroke(tn.path, if (tn.accent) accent.copy(alpha = .75f) else Body, tn.width)
            }
        }
        when (mood) {
            MeduMood.Wave -> rotate(if (t == null) 0f else swing(loop(t, 1.1), -14f, 16f), Offset(163f, 96f)) {
                stroke(Art.waveArm, Body, 8f)
                drawCircle(Body, 6.5f, Offset(180f, 58f))
            }
            MeduMood.Search -> if (torch) {
                withTransform({ translate(186f, 128f); rotate(16f, Offset.Zero) }) {
                    rotate(if (t == null) 0f else swing(loop(t, 6.0), -16f, 14f), Offset(30f, 0f)) {
                        drawIntoCanvas { it.drawPath(Art.beam, Art.beamPaint) }
                    }
                    drawRoundRect(Color(0xFFA1A1A1), Offset(0f, -6f), Size(22f, 12f), CornerRadius(3f))
                    drawRoundRect(Color(0xFFD4D4D4), Offset(20f, -9f), Size(11f, 18f), CornerRadius(3f))
                    drawLine(Light, Offset(31f, -7f), Offset(31f, 7f), 3f, StrokeCap.Round)
                }
                stroke(Art.torchArm, Body, 6.5f)
            }
            MeduMood.Oops -> rotate(if (t == null) 0f else swing(loop(t, 2.6), -8f, 8f), Offset(163.5f, 112f)) {
                stroke(Art.plugTentacle, Body, 6f)
                stroke(Art.cable, Color(0xFF8C8C8C), 3.5f)
                drawRoundRect(Color(0xFFA1A1A1), Offset(157f, 226f), Size(18f, 13f), CornerRadius(3.5f))
                stroke(Art.prongs, Color(0xFFA1A1A1), 3f)
            }
            else -> Unit
        }
        if (small) drawSmallBell() else drawBell(mood, t, floatDur, accent)
    }
    drawExtras(mood, t, accent, texts)
}

private data class Bub(val x: Float, val y: Float, val r: Float, val delay: Double)

/** The under-80 px bell: no pulse, no face details, bigger eyes. */
private fun DrawScope.drawSmallBell() {
    drawPath(Art.bell, Art.bodyFill)
    stroke(Art.frill, Frill.copy(alpha = .85f), 2.5f, cap = StrokeCap.Butt, join = StrokeJoin.Round)
    for (x in floatArrayOf(78f, 122f)) {
        drawOval(Ink, Offset(x - 10f, 84f - 12.5f), Size(20f, 25f))
        drawCircle(Color.White, 3.6f, Offset(x + 3f, 79f))
    }
}

private fun DrawScope.drawBell(mood: MeduMood, t: Double?, floatDur: Double, accent: Color) {
    var sx = 1f
    var sy = 1f
    if (t != null) {
        val q = loop(t, floatDur)
        val at = floatArrayOf(0f, .4f, .65f, 1f)
        sx = track(q, at, floatArrayOf(1f, 1.05f, .98f, 1f), EaseInOut)
        sy = track(q, at, floatArrayOf(1f, .94f, 1.03f, 1f), EaseInOut)
    }
    scale(sx, sy, Offset(100f, 117f)) {
        drawPath(Art.bell, Art.bodyFill)
        stroke(Art.frill, Frill.copy(alpha = .85f), 2.5f, cap = StrokeCap.Butt, join = StrokeJoin.Round)
        drawCircle(accent.copy(alpha = .32f), 6.5f, Offset(76f, 44f))
        drawCircle(accent.copy(alpha = .32f), 4.2f, Offset(100f, 35f))
        drawCircle(accent.copy(alpha = .32f), 5.5f, Offset(124f, 45f))
        rotate(24f, Offset(54f, 70f)) { drawOval(Color.White.copy(alpha = .9f), Offset(47f, 55f), Size(14f, 30f)) }
        val blush: Float
        when (mood) {
            MeduMood.Sleepy -> {
                stroke(Art.sleepL, Ink, 3.2f)
                stroke(Art.sleepR, Ink, 3.2f)
                drawOval(Ink, Offset(100f - 3.4f, 99f - 2.8f), Size(6.8f, 5.6f))
                blush = .5f
            }
            MeduMood.Yay -> {
                stroke(Art.happyL, Ink, 3.6f)
                stroke(Art.happyR, Ink, 3.6f)
                drawPath(Art.grin, Ink)
                stroke(Art.grin, Ink, 2.4f, cap = StrokeCap.Butt, join = StrokeJoin.Round)
                drawPath(Art.tongue, accent)
                blush = .7f
            }
            else -> {
                val eyeY = if (mood == MeduMood.Think) 80f else 83f
                val blink = if (t == null) 1f else track(loop(t, 5.0), floatArrayOf(0f, .93f, .96f, 1f), floatArrayOf(1f, 1f, .1f, 1f), Ease)
                val look = if (t != null && mood == MeduMood.Search) {
                    track(loop(t, 3.4), floatArrayOf(0f, .18f, .42f, .64f, .88f, 1f), floatArrayOf(-4f, -4f, 4f, 4f, -4f, -4f), EaseInOut)
                } else 0f
                scale(1f, blink, Offset(100f, eyeY)) {
                    translate(look, 0f) {
                        for (x in floatArrayOf(80f, 120f)) {
                            drawOval(Ink, Offset(x - 8.5f, eyeY - 10.5f), Size(17f, 21f))
                            drawCircle(Color.White, 3.4f, Offset(x + 2.8f, eyeY - 5f))
                            drawCircle(Color.White, 1.6f, Offset(x - 2.4f, eyeY + 5f))
                        }
                    }
                }
                blush = when (mood) {
                    MeduMood.Search -> {
                        stroke(Art.searchBrowL, Ink, 3f)
                        stroke(Art.searchBrowR, Ink, 3f)
                        stroke(Art.wobble, Ink, 3f, join = StrokeJoin.Round)
                        .4f
                    }
                    MeduMood.Think -> {
                        stroke(Art.flat, Ink, 3f)
                        .45f
                    }
                    MeduMood.Oops -> {
                        stroke(Art.oopsBrowL, Ink, 3f)
                        stroke(Art.oopsBrowR, Ink, 3f)
                        stroke(Art.frown, Ink, 3f)
                        .4f
                    }
                    else -> {
                        stroke(Art.smile, Ink, 3.2f)
                        .55f
                    }
                }
            }
        }
        drawOval(accent.copy(alpha = blush), Offset(64f - 8.5f, 97f - 4.6f), Size(17f, 9.2f))
        drawOval(accent.copy(alpha = blush), Offset(136f - 8.5f, 97f - 4.6f), Size(17f, 9.2f))
    }
}

/** What floats beside Medu rather than with it: the question mark and tear, z's, sparkles, the thinking dots. */
private fun DrawScope.drawExtras(mood: MeduMood, t: Double?, accent: Color, texts: MeduTexts?) {
    when (mood) {
        MeduMood.Search -> {
            drawPath(Art.tear, Tear.copy(alpha = .9f))
            val q = texts?.question ?: return
            val w = q.getLineRight(0)
            // Tilts from the bottom centre of its text box (font ascent and descent around the baseline at y 22).
            val origin = Offset(164f, 22f - q.firstBaseline + q.getLineBottom(0))
            val p = if (t == null) null else loop(t, 2.2)
            val angle = if (p == null) 0f else swing(p, -12f, 12f)
            val lift = if (p == null) 0f else swing(p, 0f, -4f)
            withTransform({ rotate(angle, origin); translate(0f, lift) }) {
                drawText(q, accent, Offset(164f - w / 2f, 22f - q.firstBaseline))
            }
        }
        MeduMood.Sleepy -> {
            if (texts == null) return
            val spots = listOf(Triple(148f, 40f, 0.0), Triple(166f, 18f, -1.2), Triple(186f, -6f, -2.4))
            spots.forEachIndexed { i, (x, y, delay) ->
                val z = texts.z[i]
                val top = y - z.firstBaseline
                val origin = Offset(x + z.getLineRight(0) / 2f, top + z.getLineBottom(0) / 2f)
                var dx = 0f
                var dy = 0f
                var k = 1f
                var alpha = 1f
                if (t != null) {
                    val p = loop(t, 3.6, delay)
                    val e = EaseOut.transform(p)
                    dx = 16f * e
                    dy = -38f * e
                    k = .5f + (1.15f - .5f) * e
                    alpha = track(p, floatArrayOf(0f, .25f, 1f), floatArrayOf(0f, 1f, 0f), EaseOut)
                }
                withTransform({ translate(dx, dy); scale(k, k, origin) }) {
                    drawText(z, Mist, Offset(x, top), alpha = alpha)
                }
            }
        }
        MeduMood.Yay -> {
            val sparks = listOf(
                Spark(28f, 30f, 1.1f, true, 0.0), Spark(176f, 14f, 1.4f, false, -.5),
                Spark(186f, 96f, .9f, true, -1.0), Spark(18f, 110f, .8f, false, -.8),
            )
            for (sp in sparks) {
                var k = 1f
                var angle = 0f
                var alpha = 1f
                if (t != null) {
                    val p = loop(t, 1.5, sp.delay)
                    k = swing(p, .35f, 1f)
                    angle = swing(p, 0f, 20f)
                    alpha = swing(p, .25f, 1f)
                }
                withTransform({ translate(sp.x, sp.y); scale(sp.scale * k, sp.scale * k, Offset.Zero); rotate(angle, Offset.Zero) }) {
                    drawPath(Art.spark, if (sp.accent) accent else Color.White, alpha = alpha)
                }
            }
        }
        MeduMood.Think -> {
            for ((i, x) in floatArrayOf(82f, 100f, 118f).withIndex()) {
                var dy = 0f
                var alpha = 1f
                if (t != null) {
                    val p = loop(t, 1.2, .18 * i)
                    val at = floatArrayOf(0f, .35f, 1f)
                    dy = track(p, at, floatArrayOf(0f, -7f, 0f), EaseInOut)
                    alpha = track(p, at, floatArrayOf(.35f, 1f, .35f), EaseInOut)
                }
                drawCircle(if (i == 1) accent else Mist, 6f, Offset(x, dy), alpha = alpha)
            }
        }
        else -> Unit
    }
}

private class Spark(val x: Float, val y: Float, val scale: Float, val accent: Boolean, val delay: Double)
