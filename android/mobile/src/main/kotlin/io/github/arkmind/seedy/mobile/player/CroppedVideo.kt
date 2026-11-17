package io.github.arkmind.seedy.mobile.player

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.state.rememberPresentationState
import io.github.arkmind.seedy.player.Bars
import io.github.arkmind.seedy.player.BlackBars
import kotlin.math.roundToInt

/**
 * The video fitted to its box; with [bars] baked into the frame, enlarged so they leave a box wider than the frame (a
 * phone held sideways) and the picture fills it.
 */
@OptIn(UnstableApi::class)
@Composable
fun CroppedVideo(player: Player, bars: Bars?) {
    val video = rememberPresentationState(player).videoSizeDp
    Box(Modifier.fillMaxSize().clipToBounds()) {
        ContentFrame(
            player = player,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().layout { measurable, constraints ->
                val w = constraints.maxWidth
                val h = constraints.maxHeight
                val r = if (bars != null && video != null && video.isSpecified && constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
                    BlackBars.crop(bars, video.width, video.height, w.toFloat(), h.toFloat())
                } else {
                    null
                }
                if (r == null) {
                    val p = measurable.measure(constraints)
                    layout(p.width, p.height) { p.place(0, 0) }
                } else {
                    val p = measurable.measure(Constraints.fixed(r.width.roundToInt(), r.height.roundToInt()))
                    layout(w, h) { p.place(r.left.roundToInt(), r.top.roundToInt()) }
                }
            },
        )
    }
}
