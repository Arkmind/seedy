package io.github.arkmind.seedy.mobile.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.SText
import kotlin.math.roundToInt

/** The two swipes of Settings · Gestures: up and down on the left half for brightness, on the right half for volume. */
enum class SwipeSide { Brightness, Volume }

/** A swipe under way: its side and where the level is now (0–1). */
data class Swipe(val side: SwipeSide, val level: Float)

/** What the swipes change, 0–1. */
interface SwipeLevels {
    fun get(side: SwipeSide): Float
    fun set(side: SwipeSide, level: Float)
}

/**
 * This window's brightness (only while the player is open: [restore] gives it back to the system) and the media volume,
 * in its steps.
 */
class PhoneSwipeLevels(private val activity: Activity) : SwipeLevels {
    private val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var volume: Float? = null

    override fun get(side: SwipeSide): Float = when (side) {
        SwipeSide.Brightness -> activity.window.attributes.screenBrightness.takeIf { it >= 0f }
            ?: (runCatching { Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128) / 255f).coerceIn(0f, 1f)
        SwipeSide.Volume -> {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
            // Between steps, the swipe's own level (so a slow swipe still moves).
            volume?.takeIf { (it * max).roundToInt() == (now * max).roundToInt() } ?: now
        }
    }

    override fun set(side: SwipeSide, level: Float) {
        when (side) {
            SwipeSide.Brightness -> activity.window.attributes = activity.window.attributes.apply { screenBrightness = level.coerceIn(0.01f, 1f) }
            SwipeSide.Volume -> {
                volume = level
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).roundToInt(), 0)
            }
        }
    }

    fun restore() {
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    }
}

private val Sun = IconDef(2.2f, "M8 12a4 4 0 1 0 8 0a4 4 0 1 0-8 0", "M12 2v2.5M12 19.5V22M4.2 4.2l1.8 1.8M18 18l1.8 1.8M2 12h2.5M19.5 12H22M4.2 19.8L6 18M18 6l1.8-1.8")
private val Speaker = IconDef(2.2f, "M4 9v6h4l5 4V5L8 9z", "M16.5 8.5a5 5 0 0 1 0 7", "M19 6a8.5 8.5 0 0 1 0 12")
private val Info = IconDef(2.2f, "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0-17 0", "M12 11v5", "M12 8h.01")

/**
 * Board "Android · Player swipe gestures": the side being swiped darkens, its 52 × 200 level fills from the bottom with
 * the percentage under it, the other side's outline says what it does, and the hint sits at the foot.
 */
@Composable
fun BoxScope.SwipeOverlay(swipe: Swipe) {
    val left = swipe.side == SwipeSide.Brightness
    Box(
        Modifier.fillMaxSize().background(
            if (left) Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.45f to Color.Transparent)
            else Brush.horizontalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f)),
        ),
    )
    Level(swipe, Modifier.align(if (left) Alignment.CenterStart else Alignment.CenterEnd).padding(start = if (left) 56.dp else 0.dp, end = if (left) 0.dp else 56.dp))
    Other(if (left) SwipeSide.Volume else SwipeSide.Brightness, Modifier.align(if (left) Alignment.CenterEnd else Alignment.CenterStart).padding(start = if (left) 0.dp else 56.dp, end = if (left) 56.dp else 0.dp))
    Row(
        Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xD9141414)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SIcon(Info, size = 16.dp, color = Color(0xFFE0E0E0))
        SText(t("Swipe up or down: left side for brightness, right side for volume"), size = 13.sp, color = Color(0xFFE0E0E0), maxLines = 1)
    }
}

@Composable
private fun Level(swipe: Swipe, modifier: Modifier) {
    val shape = RoundedCornerShape(18.dp)
    val icon = if (swipe.side == SwipeSide.Brightness) Sun else Speaker
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(52.dp, 200.dp).clip(shape).background(Color(0xB8141414)).border(1.dp, Color.White.copy(alpha = 0.1f), shape)) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(swipe.level.coerceIn(0f, 1f)).background(Color(0xFFF0F0F0)))
            // Dark on the fill, light above it.
            SIcon(icon, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), size = 22.dp, color = if (swipe.level * 200 >= 34) Color(0xFF1E1E1E) else Color.White)
        }
        SText("${(swipe.level * 100).roundToInt()}%", size = 14.sp, weight = 800, color = Color.White)
    }
}

/** The other swipe's dashed outline, faint. */
@Composable
private fun Other(side: SwipeSide, modifier: Modifier) {
    Column(modifier.alpha(0.35f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(52.dp, 200.dp).drawBehind {
                val w = 2.dp.toPx()
                drawRoundRect(
                    Color.White.copy(alpha = 0.6f), Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(18.dp.toPx() - w / 2),
                    style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                )
            },
        ) {
            SIcon(if (side == SwipeSide.Brightness) Sun else Speaker, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), size = 22.dp, color = Color.White)
        }
        SText(if (side == SwipeSide.Brightness) t("Brightness") else t("Volume"), size = 12.sp, weight = 700, color = Color.White)
    }
}
