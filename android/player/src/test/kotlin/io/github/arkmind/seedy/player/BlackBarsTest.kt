package io.github.arkmind.seedy.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlackBarsTest {
    private val w = 320
    private val h = 180

    /** A [w] × [h] picture: black above [top] and below [bottom] rows, [grey] in between, a bright dot at [dot]. */
    private fun frame(top: Int, bottom: Int, grey: Int = 120, dot: Pair<Int, Int>? = null): IntArray = IntArray(w * h) { i ->
        val y = i / w
        val x = i % w
        val v = when {
            dot != null && dot.first == x && dot.second == y -> 255
            y < top || y >= h - bottom -> 0
            else -> grey
        }
        (0xff shl 24) or (v shl 16) or (v shl 8) or v
    }

    @Test
    fun findsLetterboxBars() {
        val s = BlackBars.scan(frame(22, 22), w, h)!!
        assertEquals(22, s.top)
        assertEquals(22, s.bottom)
        assertEquals(0, s.left)
        assertTrue(s.lit)
        val bars = BlackBars.combine(List(8) { s }, 8)!!
        // One row and a little more than the bars, so no black line is left.
        assertEquals(23f / 180 + 0.002f, bars.top, 1e-4f)
        assertEquals(0f, bars.left, 0f)
    }

    @Test
    fun darkScenesAndBlackFramesDontWiden() {
        assertNull(BlackBars.scan(frame(0, 0, grey = 0), w, h))
        // A dark scene reads as taller bars; the brighter pictures decide.
        val dark = BlackBars.scan(frame(40, 40, grey = 30), w, h)!!
        val lit = BlackBars.scan(frame(22, 22), w, h)!!
        val bars = BlackBars.combine(listOf(dark, lit, lit, lit), 3)!!
        assertEquals(23f / 180 + 0.002f, bars.top, 1e-4f)
        // Too few lit pictures: not known yet.
        assertNull(BlackBars.combine(listOf(dark, lit), 3))
    }

    @Test
    fun starsAreNotBars() {
        // A dark row with one bright point is picture.
        assertEquals(10, BlackBars.scan(frame(22, 22, dot = 5 to 10), w, h)!!.top)
    }

    @Test
    fun lopsidedOrThinEdgesAreLeftAlone() {
        val lopsided = BlackBars.scan(frame(40, 4), w, h)!!
        assertFalse(BlackBars.combine(List(4) { lopsided }, 3)!!.any)
        val thin = BlackBars.scan(frame(2, 2), w, h)!!
        assertFalse(BlackBars.combine(List(4) { thin }, 3)!!.any)
    }

    @Test
    fun cropFillsAWideScreen() {
        val bars = Bars(0.13f, 0.13f, 0f, 0f)
        // A 16:9 frame holding a 2.39:1 film on a 2000 × 837 window: fitted, the frame is 1488 × 837; enlarged, its
        // picture spans the width and nearly all the height, centred.
        val r = BlackBars.crop(bars, 1920f, 1080f, 2000f, 837f)!!
        val s = r.width / 2000f
        assertEquals(2000f, 1488f * s, 1f)
        assertTrue(837f * 0.74f * s in 830f..837.5f)
        assertEquals(1000f, r.left + r.width / 2, 0.5f)
        assertEquals(418.5f, r.top + r.height / 2, 0.5f)
        // On a 16:9 screen there is nothing to gain.
        assertNull(BlackBars.crop(bars, 1920f, 1080f, 1920f, 1080f))
    }

    @Test
    fun minLitFollowsTheThumbnails() {
        assertEquals(3, BlackBars.minLit(5))
        assertEquals(8, BlackBars.minLit(200))
        assertEquals(3, BlackBars.minLit(1))
    }
}
