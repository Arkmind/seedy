package io.github.arkmind.seedy.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/**
 * The web's countdown ring (Up next, the end of a movie): an 11/26 circle with a faint track, the white arc of what's
 * [fraction] left, from the top, easing a second at a time.
 */
@Composable
fun CountdownRing(fraction: Float, size: Dp, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(1000, easing = LinearEasing), label = "ring")
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 26f
        val stroke = Stroke(3f * k, cap = StrokeCap.Round)
        val r = 11f * k
        val topLeft = Offset(center.x - r, center.y - r)
        drawCircle(Color.White.copy(alpha = .18f), r, center, style = Stroke(3f * k))
        if (f > 0f) drawArc(Color.White, -90f, 360f * f, false, topLeft, Size(2 * r, 2 * r), style = stroke)
    }
}

/**
 * Rounds a video's corners by covering them in [color] (a SurfaceView can't be clipped): draw it over the video, the
 * size of its box.
 */
@Composable
fun CornerMask(radius: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val r = radius.toPx()
        val path = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, this@Canvas.size))
            addRoundRect(RoundRect(androidx.compose.ui.geometry.Rect(Offset.Zero, this@Canvas.size), CornerRadius(r)))
        }
        drawPath(path, color)
    }
}
