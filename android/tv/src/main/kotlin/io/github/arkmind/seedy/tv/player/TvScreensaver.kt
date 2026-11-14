package io.github.arkmind.seedy.tv.player

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.arkmind.seedy.core.format.episodeTag
import io.github.arkmind.seedy.core.format.join
import io.github.arkmind.seedy.core.format.timeLeft
import io.github.arkmind.seedy.core.i18n.I18n
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.tv.parts.bp
import io.github.arkmind.seedy.tv.parts.bsp
import io.github.arkmind.seedy.ui.components.DeepScenes
import io.github.arkmind.seedy.ui.components.DeepSea
import io.github.arkmind.seedy.ui.components.Medu
import io.github.arkmind.seedy.ui.components.MeduMood
import io.github.arkmind.seedy.ui.components.rememberLoopClock
import io.github.arkmind.seedy.ui.components.seconds
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.floor

/** How long the player sits paused before the screensaver comes. */
const val ScreensaverAfterMs = 3 * 60_000L

private val EaseInOut = CubicBezierEasing(.42f, 0f, .58f, 1f)

/** The screensaver's slow drift (48 s, ease-in-out between the boards' four points), in board pixels. */
private fun drift(t: Double): Pair<Float, Float> {
    val xs = floatArrayOf(0f, -420f, -860f, -380f, 0f)
    val ys = floatArrayOf(0f, 130f, -30f, -150f, 0f)
    val x = (t / 48.0).let { it - floor(it) } * 4
    val i = x.toInt().coerceIn(0, 3)
    val e = EaseInOut.transform((x - i).toFloat())
    return (xs[i] + (xs[i + 1] - xs[i]) * e) to (ys[i] + (ys[i + 1] - ys[i]) * e)
}

/**
 * Board "Android TV · Screensaver": after the player sits paused a while, the deep sea with Medu drifting through it,
 * the clock, what's paused and how far in, and "Press any button to carry on" (any key brings the player back).
 */
@Composable
fun TvScreensaver(item: BaseItemDto, positionMs: Long, durationMs: Long, now: LocalDateTime, is24Hour: Boolean = true) {
    val clock = rememberLoopClock()
    val episode = item.type == BaseItemKind.EPISODE
    val left = ((durationMs - positionMs).coerceAtLeast(0L)) / 1000.0
    Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
        DeepSea(DeepScenes.TvScreensaver)
        Medu(
            MeduMood.Idle, 250.bp,
            Modifier.offset(1240.bp, 250.bp).graphicsLayer {
                val (x, y) = clock.seconds()?.let(::drift) ?: (0f to 0f)
                translationX = (x / 2f).dp.toPx()
                translationY = (y / 2f).dp.toPx()
            },
            height = 330.bp,
        )
        Column(Modifier.align(Alignment.TopEnd).padding(top = 72.bp, end = 96.bp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.bp)) {
            SText(
                now.format(DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm", I18n.locale)),
                // The boards' browser keeps the last digit's negative letter spacing; Compose drops it.
                Modifier.offset(x = 5.bp),
                size = 120.bsp, weight = 400, lineHeight = 1f, tracking = -.03f, color = Color.White.copy(alpha = .82f),
            )
            SText(
                now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", I18n.locale)).replaceFirstChar { it.titlecase(I18n.locale) },
                size = 26.bsp, weight = 600, color = SeedyColors.Text3,
            )
        }
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 96.bp, bottom = 300.bp).width(620.bp),
            verticalArrangement = Arrangement.spacedBy(10.bp),
        ) {
            SText(t("Paused"), size = 20.bsp, weight = 800, tracking = .12f, uppercase = true, color = SeedyColors.Text3)
            SText(
                (if (episode) item.seriesName else null) ?: item.name.orEmpty(),
                size = 48.bsp, weight = 900, lineHeight = 1f, color = SeedyColors.White, maxLines = 1,
            )
            SText(
                if (episode) join(item.episodeTag, item.name, timeLeft(left)) else join(item.productionYear, timeLeft(left)),
                size = 24.bsp, color = Color(0xFFB4B4B4), maxLines = 1,
            )
            Box(Modifier.padding(top = 8.bp).fillMaxWidth().height(8.bp).clip(RoundedCornerShape(4.bp)).background(Color.White.copy(alpha = .16f))) {
                val f = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(4.bp)).background(SeedyColors.White))
            }
        }
        SText(
            t("Press any button to carry on"), Modifier.align(Alignment.BottomEnd).padding(end = 96.bp, bottom = 260.bp),
            size = 22.bsp, weight = 600, color = SeedyColors.Text3,
        )
    }
}
