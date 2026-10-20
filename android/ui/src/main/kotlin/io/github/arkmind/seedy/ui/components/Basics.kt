package io.github.arkmind.seedy.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SeedyColors

/** An image from the server over a flat surface, which is what shows while it loads or when there's none. */
@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier, placeholder: Color = SeedyColors.Surface, scale: ContentScale = ContentScale.Crop) {
    Box(modifier.background(placeholder)) {
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = scale, modifier = Modifier.matchParentSize())
    }
}

/** A thin progress bar: [fraction] of the track filled, rounded ends. */
@Composable
fun ProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
    track: Color = SeedyColors.Track,
    fill: Color = SeedyColors.White,
) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height)).background(track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(fill, RoundedCornerShape(height)))
    }
}

/** The boards' switch: 48×28, the accent when on, a dark 20 px knob. */
@Composable
fun SeedySwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val track by animateColorAsState(if (checked) LocalAccent.current else SeedyColors.Surface2, label = "track")
    val knob by animateDpAsState(if (checked) 24.dp else 4.dp, label = "knob")
    Box(
        modifier
            .size(48.dp, 28.dp)
            .clip(CircleShape)
            .background(track)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Switch) { onChange(!checked) },
    ) {
        Box(
            Modifier.align(Alignment.CenterStart).offset(x = knob).size(20.dp)
                .background(if (checked) SeedyColors.InkInverse else SeedyColors.Text2, CircleShape),
        )
    }
}
