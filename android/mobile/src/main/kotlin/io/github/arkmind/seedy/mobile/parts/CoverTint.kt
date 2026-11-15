package io.github.arkmind.seedy.mobile.parts

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.get
import androidx.core.graphics.scale
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
 * counting little. The players', the book page's and the mix cards' backdrops take it, brought to the boards'
 * brightness.
 */

/** Found colours by request size and url; an empty array is a cover known to have none. */
private val cache = ConcurrentHashMap<String, FloatArray>()
private val noColour = FloatArray(0)

/**
 * The picture at [url]'s main colour as r, g, b (0–255), loaded at [requestSize] px; null while it loads, without a
 * picture or when it can't be read. With [cacheMisses] a picture without one isn't asked again.
 */
@Composable
fun rememberDominant(url: String?, requestSize: Int, cacheMisses: Boolean): FloatArray? {
    val context = LocalContext.current
    val key = url?.let { "$requestSize:$it" }
    var rgb by remember(url) { mutableStateOf(key?.let { cache[it] }?.takeIf { it.isNotEmpty() }) }
    LaunchedEffect(url) {
        if (url == null || key == null || cache.containsKey(key)) return@LaunchedEffect
        val found = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context).data(url).size(requestSize, requestSize).allowHardware(false).build()
                (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()?.let(::dominantOf)
            }.getOrNull()
        }
        if (found != null) cache[key] = found else if (cacheMisses) cache[key] = noColour
        rgb = found
    }
    return rgb
}

private class Bucket(var n: Double = 0.0, var r: Double = 0.0, var g: Double = 0.0, var b: Double = 0.0)

/** The picture shrunk to 16 × 16, its colours in buckets weighted by how colourful they are; the heaviest one's mean. */
private fun dominantOf(bitmap: Bitmap): FloatArray? {
    val small = if (bitmap.width != 16 || bitmap.height != 16) bitmap.scale(16, 16) else bitmap
    val buckets = HashMap<Int, Bucket>()
    for (y in 0 until small.height) for (x in 0 until small.width) {
        val p = small[x, y]
        val r = (p shr 16) and 0xff
        val g = (p shr 8) and 0xff
        val b = p and 0xff
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        if (max < 24) continue
        val w = 0.15 + (max - min) / 255.0
        val k = ((r shr 5) shl 6) or ((g shr 5) shl 3) or (b shr 5)
        val bucket = buckets.getOrPut(k) { Bucket() }
        bucket.n += w
        bucket.r += r * w
        bucket.g += g * w
        bucket.b += b * w
    }
    val best = buckets.values.maxByOrNull { it.n } ?: return null
    return floatArrayOf((best.r / best.n).toFloat(), (best.g / best.n).toFloat(), (best.b / best.n).toFloat())
}

/** [rgb] scaled so its brightest channel is [top] (the boards' glow like #2f4a66 is 104, the edge 58). */
fun scaledColor(rgb: FloatArray, top: Float): Color {
    val k = top / maxOf(1f, rgb[0], rgb[1], rgb[2])
    return Color((rgb[0] * k / 255f).coerceIn(0f, 1f), (rgb[1] * k / 255f).coerceIn(0f, 1f), (rgb[2] * k / 255f).coerceIn(0f, 1f))
}
