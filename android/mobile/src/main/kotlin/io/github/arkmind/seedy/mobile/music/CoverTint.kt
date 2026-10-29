package io.github.arkmind.seedy.mobile.music

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import io.github.arkmind.seedy.ui.components.CoverPalettes
import io.github.arkmind.seedy.ui.components.coverPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/* A cover's own colour for the players' backdrops and the mix cards (web/src/audio/cover.tsx useCoverTint and
   useNearestPalette): the picture shrunk to 16×16, its most common colourful shade. */

private val dominantCache = ConcurrentHashMap<String, FloatArray>()
private val noColour = FloatArray(0)

/** The main colour of a picture: its most common colourful shade (greys, black and white count little). */
private fun dominant(bitmap: Bitmap): FloatArray? {
    val small = Bitmap.createScaledBitmap(bitmap, 16, 16, true)
    data class Bucket(var n: Double = 0.0, var r: Double = 0.0, var g: Double = 0.0, var b: Double = 0.0)
    val buckets = HashMap<Int, Bucket>()
    for (y in 0 until 16) for (x in 0 until 16) {
        val p = small.getPixel(x, y)
        val r = (p shr 16) and 0xff
        val g = (p shr 8) and 0xff
        val b = p and 0xff
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        if (hi < 24) continue
        val w = 0.15 + (hi - lo) / 255.0
        val k = ((r shr 5) shl 6) or ((g shr 5) shl 3) or (b shr 5)
        val x2 = buckets.getOrPut(k) { Bucket() }
        x2.n += w; x2.r += r * w; x2.g += g * w; x2.b += b * w
    }
    val best = buckets.values.maxByOrNull { it.n } ?: return null
    return floatArrayOf((best.r / best.n).toFloat(), (best.g / best.n).toFloat(), (best.b / best.n).toFloat())
}

/** The picture at [url]'s main colour (0–255 channels), null until it's known or when there's none. */
@Composable
fun rememberDominant(url: String?): FloatArray? {
    val context = LocalContext.current
    var rgb by remember(url) { mutableStateOf(url?.let { dominantCache[it] }?.takeIf { it.isNotEmpty() }) }
    LaunchedEffect(url) {
        if (url == null || dominantCache.containsKey(url)) return@LaunchedEffect
        val found = runCatching {
            val request = ImageRequest.Builder(context).data(url).size(64).allowHardware(false).build()
            val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult
            result?.image?.toBitmap()?.let { withContext(Dispatchers.Default) { dominant(it) } }
        }.getOrNull()
        dominantCache[url] = found ?: noColour
        rgb = found
    }
    return rgb
}

private fun scaled(rgb: FloatArray, top: Float): Color {
    val k = top / max(1f, rgb.max())
    return Color(red = (rgb[0] * k).roundToInt().coerceIn(0, 255), green = (rgb[1] * k).roundToInt().coerceIn(0, 255), blue = (rgb[2] * k).roundToInt().coerceIn(0, 255))
}

/**
 * Two colours for a player's backdrop: the cover's own, brought to the boards' brightness (the glow like #2f4a66, the
 * edge like #1c2a3a), or the drawn palette's (for [seedId], the cover's album).
 */
@Composable
fun rememberCoverTint(url: String?, seedId: String?): Pair<Color, Color> {
    val rgb = rememberDominant(url)
    if (rgb == null) {
        val pal = coverPalette(seedId)
        return pal[1] to pal[0]
    }
    return scaled(rgb, 104f) to scaled(rgb, 58f)
}

/** The drawn palette closest to a picture's colours (mix cards, the artist's hero), else [fallbackId]'s. */
@Composable
fun rememberNearestPalette(url: String?, fallbackId: String?): List<Color> {
    val rgb = rememberDominant(url) ?: return coverPalette(fallbackId)
    val want = FloatArray(3).also { val k = 100f / max(1f, rgb.max()); for (i in 0..2) it[i] = rgb[i] * k }
    return CoverPalettes.minBy { p ->
        val c = p[1]
        val have = floatArrayOf(c.red * 255, c.green * 255, c.blue * 255)
        val k = 100f / max(1f, have.max())
        (0..2).sumOf { i -> ((have[i] * k - want[i]) * (have[i] * k - want[i])).toDouble() }
    }
}
