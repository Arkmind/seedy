package io.github.arkmind.seedy.player

import io.github.arkmind.seedy.core.settings.StillWatchingRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StillWatchingTest {
    private val minute = 60_000L
    private val rules = StillWatchingRules(episodes = 3, minutes = 90, minutesOn = true, movies = 2)

    @Test
    fun asksAfterTheEpisodes() {
        // The first by hand, two auto-played, and the third's end asks.
        assertNull(stillWatchingAsk(rules, movie = false, run = 0, idleMs = 50 * minute))
        assertNull(stillWatchingAsk(rules, movie = false, run = 1, idleMs = 80 * minute))
        assertEquals(StillWatchingAsk(3, 85, byTime = false), stillWatchingAsk(rules, movie = false, run = 2, idleMs = 85 * minute))
        assertNull(stillWatchingAsk(rules.copy(episodesOn = false, minutesOn = false), movie = false, run = 9, idleMs = 85 * minute))
    }

    @Test
    fun asksAfterTheTime() {
        assertEquals(StillWatchingAsk(2, 95, byTime = true), stillWatchingAsk(rules, movie = false, run = 1, idleMs = 95 * minute))
        // Both at once: the run is the reason given.
        assertEquals(StillWatchingAsk(3, 120, byTime = false), stillWatchingAsk(rules, movie = false, run = 2, idleMs = 120 * minute))
        // Unticked: only the run counts.
        assertNull(stillWatchingAsk(rules.copy(minutesOn = false), movie = false, run = 0, idleMs = 600 * minute))
    }

    @Test
    fun moviesCountApart() {
        assertNull(stillWatchingAsk(rules, movie = true, run = 0, idleMs = 10 * minute))
        assertEquals(StillWatchingAsk(2, 10, byTime = false, movies = true), stillWatchingAsk(rules, movie = true, run = 1, idleMs = 10 * minute))
        assertNull(stillWatchingAsk(rules.copy(moviesOn = false), movie = true, run = 5, idleMs = 10 * minute))
    }

    @Test
    fun allOffNeverAsks() {
        val off = StillWatchingRules(episodesOn = false, minutesOn = false, moviesOn = false)
        assertNull(stillWatchingAsk(off, movie = false, run = 10, idleMs = 600 * minute))
        assertNull(stillWatchingAsk(off, movie = true, run = 10, idleMs = 600 * minute))
    }

    @Test
    fun countdownReads() {
        assertEquals("15:00", countdown(900))
        assertEquals("14:32", countdown(14 * 60 + 32))
        assertEquals("0:05", countdown(5))
    }
}
