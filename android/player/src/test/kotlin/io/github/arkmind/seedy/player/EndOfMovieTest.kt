package io.github.arkmind.seedy.player

import io.github.arkmind.seedy.core.settings.StillWatchingRules
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class EndOfMovieTest {
    private val minute = 60_000L

    @Test
    fun picksComeUpAtTheCredits() {
        // A credits segment wins; else 10 % before the end, at least 20 s and at most 2 min.
        assertEquals(100 * minute, moviePicksAt(110 * minute, 100 * minute))
        assertEquals(118 * minute, moviePicksAt(120 * minute, null))
        assertEquals(9 * minute, moviePicksAt(10 * minute, null))
        assertEquals(20_000L, moviePicksAt(40_000L, null))
    }

    @Test
    fun flowFollowsThePosition() {
        assertEquals(EndFlow.Idle, endFlowAt(EndFlow.Idle, 99_000, 100_000))
        assertEquals(EndFlow.Shown, endFlowAt(EndFlow.Idle, 100_000, 100_000))
        // Watch credits keeps it away until a seek back before the credits.
        assertEquals(EndFlow.Dismissed, endFlowAt(EndFlow.Dismissed, 105_000, 100_000))
        assertEquals(EndFlow.Dismissed, endFlowAt(EndFlow.Dismissed, 99_000, 100_000))
        assertEquals(EndFlow.Idle, endFlowAt(EndFlow.Dismissed, 97_000, 100_000))
        assertEquals(EndFlow.Idle, endFlowAt(EndFlow.Shown, 97_000, 100_000))
    }

    @Test
    fun countdownHoldsWhilePaused() {
        assertEquals(11, countdownStep(12, playing = true, ended = false))
        assertEquals(12, countdownStep(12, playing = false, ended = false))
        assertEquals(11, countdownStep(12, playing = false, ended = true))
    }

    @Test
    fun countdownEndAsksByTheMovieRule() {
        val one = StillWatchingRules(episodesOn = false, minutesOn = false, movies = 1, moviesOn = true)
        // One movie in a row is enough: the pick waits behind "Still watching?".
        assertEquals(StillWatchingAsk(1, 0, byTime = false, movies = true), stillWatchingAsk(one, movie = true, run = 0, idleMs = 0))
        val two = one.copy(movies = 2)
        assertNull(stillWatchingAsk(two, movie = true, run = 0, idleMs = 0))
        assertNotNull(stillWatchingAsk(two, movie = true, run = 1, idleMs = 0))
        // The time rule asks whatever the run.
        val time = two.copy(minutes = 90, minutesOn = true)
        assertEquals(StillWatchingAsk(1, 95, byTime = true, movies = true), stillWatchingAsk(time, movie = true, run = 0, idleMs = 95 * minute))
    }

    @Test
    fun picksLeaveOutTheMovieShowsAndFolders() {
        fun item(name: String, type: BaseItemKind = BaseItemKind.MOVIE, folder: Boolean = false) =
            BaseItemDto(id = UUID.nameUUIDFromBytes(name.toByteArray()), type = type, name = name, isFolder = folder)
        val self = item("Paper Lanterns")
        val similar = listOf(self, item("Show", BaseItemKind.SERIES), item("Box", BaseItemKind.BOX_SET, folder = true), item("A"), item("B"), item("C"), item("D"))
        assertEquals(listOf("A", "B", "C"), moviePicks(similar, self).map { it.name })
    }
}
