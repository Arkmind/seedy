package io.github.arkmind.seedy.mobile.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import io.github.arkmind.seedy.mobile.parts.rememberDominant
import io.github.arkmind.seedy.mobile.parts.scaledColor
import io.github.arkmind.seedy.ui.components.CoverPalettes
import io.github.arkmind.seedy.ui.components.coverPalette
import kotlin.math.max

/* A cover's own colour for the players' backdrops and the mix cards (web/src/audio/cover.tsx useCoverTint and
   useNearestPalette), from its main colour (parts' rememberDominant). */

/**
 * Two colours for a player's backdrop: the cover's own, brought to the boards' brightness (the glow like #2f4a66, the
 * edge like #1c2a3a), or the drawn palette's (for [seedId], the cover's album).
 */
@Composable
fun rememberCoverTint(url: String?, seedId: String?): Pair<Color, Color> {
    val rgb = rememberDominant(url, requestSize = 64, cacheMisses = true)
    if (rgb == null) {
        val pal = coverPalette(seedId)
        return pal[1] to pal[0]
    }
    return scaledColor(rgb, 104f) to scaledColor(rgb, 58f)
}

/** The drawn palette closest to a picture's colours (mix cards, the artist's hero), else [fallbackId]'s. */
@Composable
fun rememberNearestPalette(url: String?, fallbackId: String?): List<Color> {
    val rgb = rememberDominant(url, requestSize = 64, cacheMisses = true) ?: return coverPalette(fallbackId)
    val want = FloatArray(3).also { val k = 100f / max(1f, rgb.max()); for (i in 0..2) it[i] = rgb[i] * k }
    return CoverPalettes.minBy { p ->
        val c = p[1]
        val have = floatArrayOf(c.red * 255, c.green * 255, c.blue * 255)
        val k = 100f / max(1f, have.max())
        (0..2).sumOf { i -> ((have[i] * k - want[i]) * (have[i] * k - want[i])).toDouble() }
    }
}
