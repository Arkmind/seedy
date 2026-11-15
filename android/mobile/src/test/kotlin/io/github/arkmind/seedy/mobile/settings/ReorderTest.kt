package io.github.arkmind.seedy.mobile.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReorderTest {
    private fun state(vararg heights: Pair<String, Int>) = ReorderState<String>().apply { this.heights.putAll(heights) }

    @Test
    fun movesPastHalfOfEachNeighbour() {
        val r = state("a" to 100, "b" to 100, "c" to 100, "d" to 100)
        r.start(listOf("a", "b", "c", "d"), "a")
        r.move(49f)
        assertEquals(listOf("a", "b", "c", "d"), r.order)
        r.move(2f)
        assertEquals(listOf("b", "a", "c", "d"), r.order)
        assertEquals(-49f, r.offset)
        r.move(200f)
        assertEquals(listOf("b", "c", "d", "a"), r.order)
        val drop = r.end()!!
        assertEquals(listOf("b", "c", "d", "a"), drop.order)
        assertEquals("a", drop.moved)
        assertNull(r.order)
        assertNull(r.drag)
        assertEquals(0f, r.offset)
    }

    @Test
    fun movesUpAndStopsAtTheFloor() {
        val r = state("a" to 60, "b" to 60, "c" to 60)
        r.start(listOf("a", "b", "c"), "c")
        r.move(-31f)
        assertEquals(listOf("a", "c", "b"), r.order)
        r.move(-100f, floor = 1, clampAtFloor = true)
        assertEquals(listOf("a", "c", "b"), r.order)
        assertEquals(-8f, r.offset)
        r.move(-100f)
        assertEquals(listOf("c", "a", "b"), r.order)
    }

    @Test
    fun endWithoutADragIsNothing() {
        val r = state()
        r.move(50f)
        assertNull(r.end())
    }

    @Test
    fun shownFollowsTheDragOnlyWhileItCoversEveryItem() {
        val r = state()
        val items = listOf("x" to 1, "y" to 2, "z" to 3)
        assertEquals(items, r.shown(items) { it.first })
        r.start(listOf("z", "x", "y"), "z")
        assertEquals(listOf("z" to 3, "x" to 1, "y" to 2), r.shown(items) { it.first })
        val more = items + ("w" to 4)
        assertEquals(more, r.shown(more) { it.first })
    }
}
