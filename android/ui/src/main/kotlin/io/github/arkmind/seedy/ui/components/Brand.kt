package io.github.arkmind.seedy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/**
 * The Seedy mark (a light rounded square with the first letter of the server's brand name) and the wordmark. The
 * defaults are the phone boards' header: a 30 px mark, a 16 px letter, the 20 px name 9 px away.
 */
@Composable
fun BrandMark(
    name: String,
    modifier: Modifier = Modifier,
    markSize: Dp = 30.dp,
    radius: Dp = 9.dp,
    letterSize: TextUnit = 16.sp,
    wordSize: TextUnit = 20.sp,
    gap: Dp = 9.dp,
    showName: Boolean = true,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
        Box(Modifier.size(markSize).background(SeedyColors.Inverse, RoundedCornerShape(radius)), contentAlignment = Alignment.Center) {
            SText(name.firstOrNull()?.uppercase() ?: "S", size = letterSize, weight = 900, color = SeedyColors.InkInverse)
        }
        if (showName) SText(name, size = wordSize, weight = 900, color = SeedyColors.White, tracking = -0.01f, maxLines = 1)
    }
}
