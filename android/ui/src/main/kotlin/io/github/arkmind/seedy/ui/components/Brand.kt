package io.github.arkmind.seedy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/** The server's uploaded logo (Seedy admin · Branding), shown in place of the lettered mark; null for the mark. */
val LocalBrandLogo = staticCompositionLocalOf<String?> { null }

/**
 * The Seedy mark (a light rounded square with the first letter of the server's brand name, or the server's own
 * logo) and the wordmark. The defaults are the phone boards' header: a 30 px mark, a 16 px letter, the 20 px name
 * 9 px away.
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
        val logo = LocalBrandLogo.current
        if (logo != null) {
            // As the web: the logo at the mark's height, up to three times as wide.
            AsyncImage(logo, null, Modifier.height(markSize).widthIn(max = markSize * 3), contentScale = ContentScale.Fit)
        } else {
            Box(Modifier.size(markSize).background(SeedyColors.Inverse, RoundedCornerShape(radius)), contentAlignment = Alignment.Center) {
                SText(name.firstOrNull()?.uppercase() ?: "S", size = letterSize, weight = 900, color = SeedyColors.InkInverse)
            }
        }
        if (showName) SText(name, size = wordSize, weight = 900, color = SeedyColors.White, tracking = -0.01f, maxLines = 1)
    }
}
