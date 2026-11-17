package io.github.arkmind.seedy.player

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * Black bars baked into the picture: a 2.39:1 film stored in a 16:9 frame, as on 4K discs. On a screen wider than
 * that frame (a phone held sideways), fitting it leaves black on all four sides, so the player enlarges the frame until
 * only those bars leave the screen. The bars are read from the item's trickplay thumbnails. Same rules as the web
 * player (web/src/player/bars.ts).
 */

/** Share of the frame each bar takes (0 when that side has none). */
data class Bars(val top: Float, val bottom: Float, val left: Float, val right: Float) {
    val any: Boolean get() = top + bottom + left + right > 0f
}

/** Where the frame goes, in the box's units, so its picture without the bars fills the box as far as it can. */
data class CropRect(val left: Float, val top: Float, val width: Float, val height: Float)

/** One picture's black edges, in rows and columns. */
data class BarScan(val top: Int, val bottom: Int, val left: Int, val right: Int, val rows: Int, val cols: Int, val lit: Boolean)

object BlackBars {
    /** A row (or column) is a bar when it is dark on average and has no bright point (stars, a lamp). */
    private const val BAR_MEAN = 20
    private const val BAR_PEAK = 56
    /** A picture counts once its content is bright enough that its edges show. */
    private const val LIT_MEAN = 28

    /** The bars of one [w] × [h] picture in ARGB pixels (as Bitmap.getPixels gives them), or null when it's all black. */
    fun scan(argb: IntArray, w: Int, h: Int): BarScan? {
        val luma = IntArray(w * h) { i ->
            val p = argb[i]
            (((p shr 16) and 0xff) * 54 + ((p shr 8) and 0xff) * 183 + (p and 0xff) * 19) shr 8
        }
        fun isBar(sum: Long, peak: Int, n: Int) = n > 0 && sum / n.toDouble() < BAR_MEAN && peak < BAR_PEAK
        fun rowBar(y: Int): Boolean {
            var sum = 0L
            var peak = 0
            for (x in 0 until w) { val l = luma[y * w + x]; sum += l; if (l > peak) peak = l }
            return isBar(sum, peak, w)
        }
        var top = 0
        while (top < h && rowBar(top)) top++
        if (top >= h * 0.9) return null
        var bottom = 0
        while (bottom < h - top && rowBar(h - 1 - bottom)) bottom++
        // Columns only over the picture's rows, so the bars above and below don't darken them.
        fun colBar(x: Int): Boolean {
            var sum = 0L
            var peak = 0
            for (y in top until h - bottom) { val l = luma[y * w + x]; sum += l; if (l > peak) peak = l }
            return isBar(sum, peak, h - top - bottom)
        }
        var left = 0
        while (left < w && colBar(left)) left++
        var right = 0
        while (right < w - left && colBar(w - 1 - right)) right++
        var sum = 0L
        for (y in top until h - bottom) for (x in left until w - right) sum += luma[y * w + x]
        val area = (h - top - bottom).toLong() * (w - left - right)
        return BarScan(top, bottom, left, right, h, w, area > 0 && sum / area.toDouble() >= LIT_MEAN)
    }

    /**
     * Bars every lit picture agrees on: a dark scene only makes them look taller, so the smallest one wins. A side's
     * pair counts when both are there and about equal (letterboxing is centred); a sliver of picture past the edge
     * reads better than a black line, so they're taken one row and a little more generously. Null while fewer than
     * [minLit] pictures are lit; [Bars] with all zeros when there are none.
     */
    fun combine(scans: List<BarScan>, minLit: Int): Bars? {
        val lit = scans.filter { it.lit }
        if (lit.isEmpty() || lit.size < minLit) return null
        fun pair(a: Float, b: Float, unit: Float): Pair<Float, Float> =
            if (a < 0.02f || b < 0.02f || abs(a - b) > 0.025f || a + b > 0.5f) 0f to 0f else (a + unit + 0.002f) to (b + unit + 0.002f)
        val (top, bottom) = pair(lit.minOf { it.top.toFloat() / it.rows }, lit.minOf { it.bottom.toFloat() / it.rows }, 1f / lit[0].rows)
        val (left, right) = pair(lit.minOf { it.left.toFloat() / it.cols }, lit.minOf { it.right.toFloat() / it.cols }, 1f / lit[0].cols)
        return Bars(top, bottom, left, right)
    }

    /** Short items have only a few thumbnails: half of them, between 3 and 8, must be lit. */
    fun minLit(scanned: Int): Int = min(8, max(3, (scanned + 1) / 2))

    /** Where a [vw] × [vh] frame goes in a [boxW] × [boxH] box so [bars] leave it (null: fitted as usual). */
    fun crop(bars: Bars, vw: Float, vh: Float, boxW: Float, boxH: Float): CropRect? {
        if (vw <= 0f || vh <= 0f || boxW <= 0f || boxH <= 0f) return null
        val k = min(boxW / vw, boxH / vh)
        val fw = vw * k
        val fh = vh * k
        val pw = fw * (1 - bars.left - bars.right)
        val ph = fh * (1 - bars.top - bars.bottom)
        if (pw <= 0f || ph <= 0f) return null
        val s = min(boxW / pw, boxH / ph)
        if (s < 1.01f) return null
        // The picture's centre sits off the frame's when the bars differ a little; bring it to the middle.
        val dx = (bars.left - bars.right) / 2 * fw * s
        val dy = (bars.top - bars.bottom) / 2 * fh * s
        val width = boxW * s
        val height = boxH * s
        return CropRect((boxW - width) / 2 - dx, (boxH - height) / 2 - dy, width, height)
    }
}
