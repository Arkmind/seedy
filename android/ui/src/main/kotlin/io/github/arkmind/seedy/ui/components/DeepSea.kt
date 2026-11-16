package io.github.arkmind.seedy.ui.components

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableLongState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativePaint
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.tan

/** CSS `animation-direction: alternate`: how far along (eased) the loop is, running back on every other turn. */
private fun alternate(t: Double, dur: Double, delay: Double, ease: Easing = EaseInOut): Float {
    val x = (t - delay) / dur
    val n = floor(x)
    val p = (x - n).toFloat()
    return ease.transform(if (Math.floorMod(n.toLong(), 2L) == 1L) 1f - p else p)
}

/**
 * The boards' deep water behind a page (see [DeepScenes]), filling it: put it first in a Box. Board pixels follow the
 * page's width (and height for where things are); with motion off it's a still frame. [glow] lights the water from
 * above in that colour (the update screen's accent).
 */
@Composable
fun DeepSea(scene: DeepScene, modifier: Modifier = Modifier, glow: Color? = null) {
    val clock = rememberLoopClock()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val kx = maxWidth.value / scene.width
        val ky = maxHeight.value / scene.height
        Canvas(Modifier.fillMaxSize()) { drawWater(scene, glow) }
        for (ray in scene.rays) RayLayer(ray, scene, kx, ky, clock)
        Canvas(Modifier.fillMaxSize()) { drawLife(scene, clock.seconds() ?: 0.0) }
        if (scene.floor) Canvas(Modifier.fillMaxSize()) { drawFloor(scene, clock.seconds() ?: 0.0) }
    }
}

private val RayLight = Color(0xFFD6E6FF)

/** Room left and right of a ray for its blur (board px). */
private const val RayMargin = 40f

/**
 * A light ray in its own layer: it only sways (11 s, 11°→19°) and brightens, so its blurred light is drawn once and
 * the layer turns.
 */
@Composable
private fun RayLayer(ray: Ray, scene: DeepScene, kx: Float, ky: Float, clock: MutableLongState?) {
    Box(
        Modifier
            .offset(((ray.left - RayMargin) * kx).dp, (-160f * ky).dp)
            .size(((ray.width + 2 * RayMargin) * kx).dp, ((scene.height + 200f) * ky).dp)
            .graphicsLayer {
                val e = alternate(clock.seconds() ?: 0.0, 11.0, ray.delay)
                rotationZ = 11f + 8f * e
                alpha = .5f + .5f * e
                transformOrigin = TransformOrigin(.5f, 0f)
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawBehind {
                val sigma = 12f * kx * density
                val paint = Paint().apply {
                    shader = LinearGradientShader(
                        Offset.Zero, Offset(0f, size.height * .76f),
                        listOf(RayLight.copy(alpha = .08f), RayLight.copy(alpha = 0f)),
                    )
                    nativePaint.maskFilter = BlurMaskFilter((sigma - .5f).coerceAtLeast(.1f) / .57735f, BlurMaskFilter.Blur.NORMAL)
                }
                val m = RayMargin * kx * density
                drawIntoCanvas { it.drawRect(m, 0f, size.width - m, size.height, paint) }
            },
    )
}

private fun DrawScope.drawWater(scene: DeepScene, top: Color?) {
    drawRect(Brush.verticalGradient(0f to Color(0xFF0E0E0E), .52f to Color(0xFF0D1016), 1f to Color(0xFF0B0D12)))
    // radial-gradient(ellipse 70% 55% at 50% 0%, rgba(159,200,255,.07), transparent 70%)
    glow(Offset(size.width / 2f, 0f), .7f * size.width, .55f * size.height, Color(0xFF9FC8FF).copy(alpha = .07f))
    // The screensaver's: ellipse 55% 50% at 78% 72%, rgba(201,214,227,.07).
    if (scene.lowGlow) glow(Offset(.78f * size.width, .72f * size.height), .55f * size.width, .5f * size.height, Color(0xFFC9D6E3).copy(alpha = .07f))
    // The update screen's: ellipse 90% 45% at 50% 18%, the accent at .12.
    if (top != null) glow(Offset(size.width / 2f, .18f * size.height), .9f * size.width, .45f * size.height, top.copy(alpha = .12f))
}

/** An elliptical CSS radial gradient, [color] fading out at 70% of its radii. */
private fun DrawScope.glow(center: Offset, rx: Float, ry: Float, color: Color) {
    scale(1f, ry / rx, center) {
        val top = center.y - (center.y) * rx / ry
        drawRect(
            Brush.radialGradient(0f to color, .7f to color.copy(alpha = 0f), center = center, radius = rx),
            topLeft = Offset(0f, top), size = Size(size.width, size.height * rx / ry),
        )
    }
}

private val SnowInk = Color(0xFFDFE8F5)

/** Marine snow (linear, drifting 18 px right and 190 down), bubbles (ease-in, 460 px up) and fish (linear). */
private fun DrawScope.drawLife(scene: DeepScene, t: Double) {
    val sx = size.width / scene.width
    val sy = size.height / scene.height
    for (s in scene.snow) {
        val p = loop(t, s.duration, s.delay)
        val alpha = track(p, floatArrayOf(0f, .12f, .88f, 1f), floatArrayOf(0f, s.opacity, s.opacity, 0f), LinearEasing)
        val r = s.size / 2f * sx
        drawCircle(SnowInk, r, Offset((s.left + 18f * p) * sx + r, (s.top - 30f + 190f * p) * sy + r), alpha = alpha)
    }
    for (b in scene.bubbles) {
        val p = loop(t, b.duration, b.delay)
        val e = EaseIn.transform(p)
        val alpha = track(p, floatArrayOf(0f, .12f, 1f), floatArrayOf(0f, 1f, 0f), EaseIn)
        val r = b.size / 2f * sx
        val center = Offset((b.left + 14f * e) * sx + r, size.height - (b.bottom + 460f * e) * sy - r)
        // box-shadow: inset 0 0 0 1.5px rgba(255,255,255,.42)
        drawCircle(Color.White.copy(alpha = .42f), r - .75f * sx, center, alpha = alpha, style = Stroke(1.5f * sx))
    }
    for (f in scene.fish) {
        val p = loop(t, f.duration, f.delay)
        val far = scene.width + 160f
        val x = if (f.back) far - (far + 140f) * p else -140f + (far + 140f) * p
        withTransform({
            translate(x * sx, f.top * sy)
            if (f.back) scale(-1f, 1f, Offset(f.width * sx / 2f, f.height * sx / 2f))
        }) {
            val k = minOf(f.width / 60f, f.height / 30f) * sx
            translate((f.width * sx - 60f * k) / 2f, (f.height * sx - 30f * k) / 2f) {
                scale(k, k, Offset.Zero) {
                    drawPath(FishArt.body, f.color)
                    drawPath(FishArt.tail, f.color)
                    drawCircle(Color(0xFF0B0D12), 1.8f, Offset(16f, 13f))
                }
            }
        }
    }
}

private object FishArt {
    val body = PathParser().parsePathString("M8 15 C16 4 38 4 48 15 C38 26 16 26 8 15 Z").toPath()
    val tail = PathParser().parsePathString("M47 15 L60 5 L56 15 L60 25 Z").toPath()
}

/** A strand of weed, swaying (skewX ±7°, 4.6 s alternate) from the bottom centre of its box. */
private class Weed(d: String, val color: Color, val delay: Double, val pivotX: Float) {
    val path = PathParser().parsePathString(d).toPath()
}

/** The sea floor: its svg's height, ground, weed, and the dune in front (with its gradient's top). */
private class Floor(val width: Float, val height: Float, ground: String, val weed: List<Weed>, val weedWidth: Float, dune: String, val duneTop: Float) {
    val ground = PathParser().parsePathString(ground).toPath()
    val dune = PathParser().parsePathString(dune).toPath()
}

private val WeedDark = Color(0xFF18231F)
private val WeedLight = Color(0xFF1B2A24)

private val PhoneFloor = Floor(
    390f, 130f, "M0 70 Q48 48 97 63 T195 59 T292 68 T390 54 V130 H0Z",
    listOf(
        Weed("M31 130 C23 103 53 85 37 71 C25 59 50 50 41 41", WeedDark, 0.0, 36.308f),
        Weed("M39 130 C21 110 51 97 35 87 C19 78 42 71 31 65", WeedLight, -1.6, 34.507f),
        Weed("M343 130 C321 101 351 82 335 67 C317 54 340 44 328 35", WeedDark, -2.2, 335.408f),
        Weed("M358 130 C350 108 380 94 364 83 C352 73 377 66 368 59", WeedLight, -.4, 363.308f),
    ),
    6f, "M0 99 Q65 75 130 92 T260 88 T390 82 V130 H0Z", 75.75f,
)

private val TvFloor = Floor(
    1920f, 220f, "M0 120 Q240 82 480 108 T960 100 T1440 116 T1920 92 V220 H0Z",
    listOf(
        Weed("M153 220 C145 175 175 145 159 121 C147 100 172 85 163 70", WeedDark, 0.0, 158.308f),
        Weed("M192 220 C174 187 204 165 188 147 C172 132 195 121 184 110", WeedLight, -1.6, 187.507f),
        Weed("M1689 220 C1667 172 1697 140 1681 114 C1663 92 1686 76 1674 60", WeedDark, -2.2, 1681.408f),
        Weed("M1766 220 C1758 184 1788 160 1772 140 C1760 124 1785 112 1776 100", WeedLight, -.4, 1771.308f),
    ),
    12f, "M0 168 Q320 128 640 156 T1280 150 T1920 140 V220 H0Z", 130.07f,
)

private fun DrawScope.drawFloor(scene: DeepScene, t: Double) {
    val floor = if (scene.width >= 1000f) TvFloor else PhoneFloor
    val k = size.width / floor.width
    translate(0f, size.height - floor.height * k) {
        scale(k, k, Offset.Zero) {
            drawPath(floor.ground, Color(0xFF11141A))
            for (w in floor.weed) {
                val skew = -7f + 14f * alternate(t, 4.6, w.delay)
                val m = Matrix().apply { values[Matrix.SkewX] = tan(Math.toRadians(skew.toDouble())).toFloat() }
                withTransform({
                    translate(w.pivotX, floor.height)
                    transform(m)
                    translate(-w.pivotX, -floor.height)
                }) {
                    drawPath(w.path, w.color, style = Stroke(floor.weedWidth, cap = StrokeCap.Round))
                }
            }
            drawPath(floor.dune, Brush.verticalGradient(listOf(Color(0xFF101318), Color(0xFF0E0E0E)), startY = floor.duneTop, endY = floor.height))
        }
    }
}

/** The boards' loading ring (a three-quarter circle, `M12 3a9 9 0 1 1-9 9`), turning once every 1.1 s. */
@Composable
fun Spinner(size: Dp, color: Color, modifier: Modifier = Modifier, stroke: Float = 3f) {
    val clock = rememberLoopClock()
    Canvas(modifier.size(size)) {
        val k = this.size.width / 24f
        val turn = clock.seconds()?.let { 360f * loop(it, 1.1) } ?: 0f
        rotate(turn) {
            scale(k, k, Offset.Zero) {
                drawPath(SpinnerArc, color, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
    }
}

private val SpinnerArc = PathParser().parsePathString("M12 3a9 9 0 1 1-9 9").toPath()
