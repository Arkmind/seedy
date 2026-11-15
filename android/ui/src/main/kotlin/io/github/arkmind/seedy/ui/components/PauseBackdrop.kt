package io.github.arkmind.seedy.ui.components

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser

/** The player's dark: rgba(5, 5, 5). */
private val Ink = Color(0xFF050505)

/**
 * The player's full-screen pauses ("Still watching?"): [art] across the screen, a touch less saturated and a touch
 * brighter (CSS `saturate(.9) brightness(1.05)`), darkened from the left (94 % → 78 % at 42 % → 22 %) and from the
 * bottom (85 % → none at 35 %) so the text on it reads.
 */
@Composable
fun PauseBackdrop(art: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Ink)) {
        Box(Modifier.fillMaxSize().filtered(SaturateBrighten)) { art() }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Ink.copy(alpha = 0f), .65f to Ink.copy(alpha = 0f), 1f to Ink.copy(alpha = .85f))))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Ink.copy(alpha = .94f), .42f to Ink.copy(alpha = .78f), 1f to Ink.copy(alpha = .22f))))
    }
}

/** CSS saturate(0.9) then brightness(1.05). */
private val SaturateBrighten: ColorMatrix = run {
    val s = 0.9f
    val b = 1.05f
    ColorMatrix(
        floatArrayOf(
            (.213f + .787f * s) * b, (.715f - .715f * s) * b, (.072f - .072f * s) * b, 0f, 0f,
            (.213f - .213f * s) * b, (.715f + .285f * s) * b, (.072f - .072f * s) * b, 0f, 0f,
            (.213f - .213f * s) * b, (.715f - .715f * s) * b, (.072f + .928f * s) * b, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}

/** Draws the content through [matrix] (a layer the size of the box). */
private fun Modifier.filtered(matrix: ColorMatrix): Modifier = drawWithCache {
    val paint = Paint().apply { colorFilter = ColorFilter.colorMatrix(matrix) }
    onDrawWithContent {
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(Offset.Zero, size), paint)
            drawContent()
            canvas.restore()
        }
    }
}

/**
 * The boards' stand-in artwork (the night sky over mountains with an aurora, `viewBox="0 0 1600 900"`), cropped to
 * [viewBox] and covering the box like `preserveAspectRatio="xMidYMid slice"`. Screenshot tests draw it where the
 * server's pictures would be.
 */
@Composable
fun BoardScene(modifier: Modifier = Modifier, viewBox: Rect = Rect(0f, 0f, 1600f, 900f)) {
    val paths = remember { SceneArt() }
    Canvas(modifier) {
        val k = maxOf(size.width / viewBox.width, size.height / viewBox.height)
        clipRect {
            translate((size.width - viewBox.width * k) / 2f - viewBox.left * k, (size.height - viewBox.height * k) / 2f - viewBox.top * k) {
                withTransform({ scale(k, k, Offset.Zero) }) {
                    drawRect(Brush.verticalGradient(0f to Color(0xFF0B131D), .55f to Color(0xFF1B2A3A), 1f to Color(0xFF0A0F15), startY = 0f, endY = 900f), size = androidx.compose.ui.geometry.Size(1600f, 900f))
                    for ((x, y, r, a) in Stars) drawCircle(Color.White.copy(alpha = a), r, Offset(x, y))
                    drawIntoCanvas { it.drawPath(paths.aurora, paths.auroraPaint) }
                    drawPath(paths.far, Color(0xFF162130))
                    drawPath(paths.near, Color(0xFF0E161F))
                    drawPath(paths.ground, Color(0xFF1D2834))
                }
            }
        }
    }
}

private data class Star(val x: Float, val y: Float, val r: Float, val a: Float)

private val Stars = listOf(
    Star(120f, 80f, 1.6f, .7f), Star(300f, 150f, 1.2f, .5f), Star(520f, 60f, 1.8f, .8f), Star(760f, 130f, 1.1f, .5f),
    Star(980f, 70f, 1.5f, .7f), Star(1180f, 160f, 1.2f, .4f), Star(1400f, 90f, 1.7f, .7f), Star(1520f, 200f, 1.1f, .5f),
    Star(640f, 220f, 1f, .4f), Star(860f, 40f, 1.3f, .6f),
)

private class SceneArt {
    private fun p(d: String): Path = PathParser().parsePathString(d).toPath()

    val aurora = p("M-100 330 C200 180 420 300 700 220 C980 140 1200 260 1700 150 L1700 260 C1300 360 1000 260 720 330 C440 400 200 300 -100 420 Z")
    val far = p("M0 560 L180 470 L300 520 L470 410 L620 500 L760 440 L930 520 L1100 430 L1260 500 L1420 450 L1600 520 V900 H0Z")
    val near = p("M0 650 L240 590 L420 640 L640 580 L860 650 L1080 600 L1300 655 L1600 610 V900 H0Z")
    val ground = p("M0 760 C300 720 600 770 900 735 C1200 700 1400 745 1600 720 V900 H0Z")

    /** The aurora's band, green to blue to violet across its width, blurred (stdDeviation 28). */
    val auroraPaint = Paint().apply {
        shader = LinearGradientShader(
            Offset(-100f, 0f), Offset(1700f, 0f),
            listOf(Color(0x004FD1A1), Color(0x8C4FD1A1), Color(0x667FB8FF), Color(0x00C58CFF)),
            listOf(0f, .35f, .7f, 1f),
        )
        // Android's blur radius → sigma is 0.57735 r + 0.5.
        asFrameworkPaint().maskFilter = BlurMaskFilter((28f - .5f) / .57735f, BlurMaskFilter.Blur.NORMAL)
    }
}
