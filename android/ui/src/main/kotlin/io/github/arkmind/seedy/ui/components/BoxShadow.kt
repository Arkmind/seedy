package io.github.arkmind.seedy.ui.components

import android.graphics.BlurMaskFilter
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * CSS `box-shadow: 0 [y] [blur] [color]` under a rounded box: a blurred copy of the box drawn behind it (Compose's
 * elevation shadows don't take a colour, offset or blur).
 */
fun Modifier.boxShadow(color: Color, blur: Dp, y: Dp = 0.dp, radius: Dp = 0.dp): Modifier = this.drawBehind {
    val sigma = blur.toPx() / 2f
    val paint = Paint().asFrameworkPaint().apply {
        isAntiAlias = true
        this.color = color.toArgb()
        // Android turns a blur radius into sigma = radius × 0.57735 + 0.5.
        if (sigma > 0.5f) maskFilter = BlurMaskFilter((sigma - 0.5f) / 0.57735f, BlurMaskFilter.Blur.NORMAL)
    }
    val r = radius.toPx()
    val dy = y.toPx()
    drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, dy, size.width, size.height + dy, r, r, paint) }
}
