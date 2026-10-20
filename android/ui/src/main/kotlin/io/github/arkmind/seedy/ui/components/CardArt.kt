package io.github.arkmind.seedy.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.arkmind.seedy.core.format.CardArt
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/** Where a card writes its title on the art, and how big (the boards' stand-in for a title with no logo). */
data class ArtTitle(val size: TextUnit, val start: Dp, val end: Dp, val bottom: Dp)

/**
 * A 16:9 library card's picture, as the web draws it: the thumb or backdrop, the logo set on it, or the title written
 * on it in capitals; a poster sits contained over its own blur. [art] null (previews, tests) writes the title.
 */
@Composable
fun CardArtwork(art: CardArt?, title: String, style: ArtTitle, modifier: Modifier = Modifier) {
    Box(modifier.background(SeedyColors.Surface)) {
        val src = art?.src
        if (src != null) {
            if (art.contain) {
                if (Build.VERSION.SDK_INT >= 31) {
                    AsyncImage(src, null, Modifier.matchParentSize().blur(22.dp), contentScale = ContentScale.Crop)
                }
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.45f)))
                AsyncImage(src, null, Modifier.matchParentSize(), contentScale = ContentScale.Fit)
            } else {
                AsyncImage(src, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            }
        }
        val logo = art?.logo
        if (logo != null) {
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
            BoxWithConstraints(Modifier.matchParentSize().padding(start = style.start, bottom = style.bottom)) {
                AsyncImage(
                    logo, null,
                    Modifier.align(Alignment.BottomStart).sizeIn(maxWidth = maxWidth * 0.58f, maxHeight = maxHeight * 0.38f),
                    contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
                )
            }
        } else if (art == null || art.titleOnArt) {
            SText(
                title,
                Modifier.align(Alignment.BottomStart).padding(start = style.start, end = style.end, bottom = style.bottom),
                size = style.size, weight = 900, lineHeight = 0.95f, color = SeedyColors.Inverse, uppercase = true, maxLines = 3,
            )
        }
    }
}

/** The boards' watched mark: a check in a dark circle in the card's top right corner. */
@Composable
fun BoxScope.WatchedMark(size: Dp, inset: Dp, check: Dp, stroke: Float) {
    Box(
        Modifier.align(Alignment.TopEnd).padding(top = inset, end = inset).size(size).background(SeedyColors.Bg.copy(alpha = 0.85f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        SIcon(WebIcons.Check, size = check, color = SeedyColors.White, stroke = stroke)
    }
}

/** A thin bar along the card's bottom edge for a title in progress. */
@Composable
fun BoxScope.CardProgress(fraction: Float, side: Dp, bottom: Dp, height: Dp) {
    Box(
        Modifier.align(Alignment.BottomCenter).padding(start = side, end = side, bottom = bottom).fillMaxWidth().height(height)
            .clip(RoundedCornerShape(height)).background(Color.White.copy(alpha = 0.24f)),
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(SeedyColors.White))
    }
}
