package io.github.arkmind.seedy.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.SText

private val Back = IconDef(1.8f, "M4.5 12a7.5 7.5 0 1 0 2.3-5.4", "M4.5 4v3.5H8")
private val Forward = IconDef(1.8f, "M19.5 12a7.5 7.5 0 1 1-2.3-5.4", "M19.5 4v3.5H16")

/** The boards' seek buttons: a circular arrow with the seconds written inside (7.5 px on the 24 px grid). */
@Composable
fun SeekGlyph(back: Boolean, seconds: Int, size: Dp, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(size)) {
        SIcon(if (back) Back else Forward, size = size, color = color)
        // The board centres the number on x 12.4 (back) or 11.6 (forward), baseline 15.2 of the 24 grid.
        val unit = size.value / 24f
        SText(
            seconds.toString(),
            Modifier.align(Alignment.Center).offsetBy(x = (if (back) 0.4f else -0.4f) * unit, y = 0.9f * unit),
            size = (7.5f * unit).sp, weight = 800, color = color, maxLines = 1,
        )
    }
}

private fun Modifier.offsetBy(x: Float, y: Float): Modifier = this.offset(x.dp, y.dp)
