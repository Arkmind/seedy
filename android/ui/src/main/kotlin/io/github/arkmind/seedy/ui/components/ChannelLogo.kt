package io.github.arkmind.seedy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import coil3.compose.AsyncImage
import io.github.arkmind.seedy.core.livetv.LiveTv
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SText
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType

/** A channel's square: its logo on a light tile when it has one, else its initials on its colour (boards' style). */
@Composable
fun ChannelLogo(channel: BaseItemDto, size: Dp, radius: Dp, textSize: TextUnit, modifier: Modifier = Modifier) {
    val images = LocalViewer.current.images
    val tag = channel.imageTags?.get(ImageType.PRIMARY)
    val (bg, ink) = LiveTv.channelColour(channel.name.orEmpty())
    Box(modifier.size(size).clip(RoundedCornerShape(radius)).background(if (tag != null && images != null) Color(0xFF2A2A2A) else Color(bg)), contentAlignment = Alignment.Center) {
        if (tag != null && images != null) {
            AsyncImage(images.item(channel.id, ImageType.PRIMARY, tag, 160), null, Modifier.matchParentSize().padding(size / 8), contentScale = ContentScale.Fit)
        } else {
            SText(LiveTv.initials(channel.name.orEmpty()), size = textSize, weight = 900, color = Color(ink), maxLines = 1)
        }
    }
}
