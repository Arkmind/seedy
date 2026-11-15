package io.github.arkmind.seedy.ui.components

import android.graphics.BlurMaskFilter
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.nativePaint
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * CSS `box-shadow: 0 [y] [blur] [color]` under a rounded box: a blurred copy of the box drawn behind it (Compose's
 * elevation shadows don't take a colour, offset or blur).
 */
fun Modifier.boxShadow(color: Color, blur: Dp, y: Dp = 0.dp, radius: Dp = 0.dp): Modifier = this.drawBehind {
    val sigma = blur.toPx() / 2f
    val paint = Paint().nativePaint.apply {
        isAntiAlias = true
        this.color = color.toArgb()
        // Android turns a blur radius into sigma = radius × 0.57735 + 0.5.
        if (sigma > 0.5f) maskFilter = BlurMaskFilter((sigma - 0.5f) / 0.57735f, BlurMaskFilter.Blur.NORMAL)
    }
    val r = radius.toPx()
    val dy = y.toPx()
    drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, dy, size.width, size.height + dy, r, r, paint) }
}

/**
 * The browser's focus ring on the boards (Chrome's `outline: auto` on a focused button): 2 [px] of near-black
 * straddling the edge, 1 [px] of white outside it, round with the box's [radius]. [px] is one board pixel.
 */
fun Modifier.focusOutline(radius: Dp, px: Dp = 1.dp): Modifier = this.drawWithContent {
    drawContent()
    val p = px.toPx()
    val r = radius.toPx()
    fun ring(color: Color, at: Float, width: Float) = drawRoundRect(
        color, Offset(-at, -at), Size(size.width + 2 * at, size.height + 2 * at), CornerRadius(r + at), style = Stroke(width),
    )
    ring(Color.White, 1.5f * p, p)
    ring(Color(0xFF101010), 0f, 2 * p)
}
