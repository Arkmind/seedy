package io.github.arkmind.seedy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import coil3.compose.AsyncImage
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/** The boards' profile colours: light, blue, green, orange (with the letter colour each one takes). */
val ProfileColours = listOf(
    SeedyColors.Inverse to SeedyColors.InkInverse,
    Color(0xFF3B6FD8) to SeedyColors.White,
    Color(0xFF1F7A3A) to SeedyColors.White,
    Color(0xFFC9852B) to SeedyColors.InkInverse,
)

/**
 * A profile's square: their picture, or their initial on one of the [ProfileColours]. [locked] adds the padlock badge
 * (the profile needs its password on this device).
 */
@Composable
fun ProfileSquare(
    name: String,
    imageUrl: String?,
    colour: Int,
    size: Dp,
    radius: Dp,
    letterSize: TextUnit,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
    badgeSize: Dp = 28.dp,
    badgeInset: Dp = 8.dp,
) {
    val (bg, ink) = ProfileColours[colour.mod(ProfileColours.size)]
    val shape = RoundedCornerShape(radius)
    Box(modifier.size(size).clip(shape).background(bg), contentAlignment = Alignment.Center) {
        SText(name.firstOrNull()?.uppercase() ?: "?", size = letterSize, weight = 900, color = ink)
        if (imageUrl != null) {
            AsyncImage(model = imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
        if (locked) LockBadge(badgeSize, badgeInset)
    }
}

@Composable
private fun BoxScope.LockBadge(size: Dp, inset: Dp) {
    Box(
        Modifier.align(Alignment.BottomEnd).padding(end = inset, bottom = inset).size(size).background(SeedyColors.Scrim, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        SIcon(WebIcons.Lock, size = size / 2, color = SeedyColors.White, stroke = 2.4f)
    }
}

/** The dashed "Add account" square. */
@Composable
fun AddProfileSquare(size: Dp, radius: Dp, modifier: Modifier = Modifier, plusSize: TextUnit = 46.sp, dash: Dp = 3.dp) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = dash.toPx()
            drawRoundRect(
                color = Color(0xFF3A3A3A),
                topLeft = Offset(w / 2, w / 2),
                size = Size(this.size.width - w, this.size.height - w),
                cornerRadius = CornerRadius(radius.toPx() - w / 2),
                style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 3, w * 3))),
            )
        }
        SText("+", size = plusSize, weight = 900, color = SeedyColors.Text3)
    }
}
