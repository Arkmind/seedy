package io.github.arkmind.seedy.mobile.books

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/*
 * The main colour of a cover (web audio/cover.tsx `dominant`): its most common colourful shade, greys, black and white
 * counting little. The players' and the book page's backdrops take it, brought to the boards' brightness.
 */

private val cache = ConcurrentHashMap<String, FloatArray>()

/** The cover's main colour as r, g, b (0–255), or null while it loads, without a picture or when it can't be read. */
@Composable
fun rememberDominant(url: String?): FloatArray? {
    val context = LocalContext.current
    var rgb by remember(url) { mutableStateOf(url?.let { cache[it] }) }
    LaunchedEffect(url) {
        if (url == null || rgb != null) return@LaunchedEffect
        val found = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context).data(url).size(16, 16).allowHardware(false).build()
                (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()?.let(::dominantOf)
            }.getOrNull()
        }
        if (found != null) {
            cache[url] = found
            rgb = found
        }
    }
    return rgb
}

private class Bucket(var n: Float = 0f, var r: Float = 0f, var g: Float = 0f, var b: Float = 0f)

private fun dominantOf(bitmap: Bitmap): FloatArray? {
    val small = if (bitmap.width != 16 || bitmap.height != 16) Bitmap.createScaledBitmap(bitmap, 16, 16, true) else bitmap
    val buckets = HashMap<Int, Bucket>()
    for (y in 0 until small.height) for (x in 0 until small.width) {
        val p = small.getPixel(x, y)
        val r = (p shr 16) and 0xff
        val g = (p shr 8) and 0xff
        val b = p and 0xff
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        if (max < 24) continue
        val w = 0.15f + (max - min) / 255f
        val k = ((r shr 5) shl 6) or ((g shr 5) shl 3) or (b shr 5)
        val bucket = buckets.getOrPut(k) { Bucket() }
        bucket.n += w
        bucket.r += r * w
        bucket.g += g * w
        bucket.b += b * w
    }
    val best = buckets.values.maxByOrNull { it.n } ?: return null
    return floatArrayOf(best.r / best.n, best.g / best.n, best.b / best.n)
}

/** [rgb] scaled so its brightest channel is [top] (the boards' glow like #2f4a66 is 104, the edge 58). */
fun scaledColor(rgb: FloatArray, top: Float): Color {
    val k = top / maxOf(1f, rgb[0], rgb[1], rgb[2])
    return Color((rgb[0] * k / 255f).coerceIn(0f, 1f), (rgb[1] * k / 255f).coerceIn(0f, 1f), (rgb[2] * k / 255f).coerceIn(0f, 1f))
}
