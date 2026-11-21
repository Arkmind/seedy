package io.github.arkmind.seedy.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTest {
    @Test
    fun aDroppedConnectionOrARestartingServerIsLost() {
        assertTrue(Connection.lost(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertTrue(Connection.lost(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        assertTrue(Connection.lost(PlaybackException.ERROR_CODE_TIMEOUT))
        // The proxy in front of the server while it restarts.
        assertTrue(Connection.lost(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 502))
        assertTrue(Connection.lost(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 503))
        assertTrue(Connection.lost(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 504))
    }

    @Test
    fun theServersOwnAnswersAndDecodingAreNotLost() {
        assertFalse(Connection.lost(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 500))
        assertFalse(Connection.lost(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 404))
        assertFalse(Connection.lost(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, null))
        assertFalse(Connection.lost(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertFalse(Connection.lost(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
    }

    @Test
    fun theBufferStartsQuickAndWaitsLongerAfterRunningDry() {
        assertTrue(Connection.StartMs < Connection.RebufferMs)
        assertTrue(Connection.RebufferMs <= Connection.BufferMs)
        assertTrue(Connection.BufferMs > 50_000)
    }

    @Test
    fun attemptsWaitLongerEachTimeThenGiveUp() {
        val r = Reconnects(delaysMs = listOf(1_000, 3_000, 5_000))
        assertEquals(1_000L, r.next(0))
        assertEquals(3_000L, r.next(2_000))
        assertEquals(5_000L, r.next(6_000))
        assertNull(r.next(12_000))
        assertEquals(3, r.spent)
    }

    @Test
    fun theDefaultAttemptsCoverAServerRestart() {
        assertTrue(Connection.ReconnectDelaysMs.sum() >= 90_000)
        assertTrue(Connection.ReconnectDelaysMs.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun steadyPlaybackRefillsTheAttempts() {
        val r = Reconnects(delaysMs = listOf(1_000, 3_000), steadyMs = 30_000)
        r.next(0)
        r.next(1_000)
        assertNull(r.next(5_000))
        // Playing again, but not long enough yet.
        r.playing(true, 10_000)
        r.playing(true, 39_000)
        assertNull(r.next(39_500))
        // A hitch starts the count over.
        r.playing(false, 40_000)
        r.playing(true, 41_000)
        r.playing(true, 70_000)
        assertNull(r.next(70_500))
        r.playing(true, 71_000)
        assertEquals(0, r.spent)
        assertEquals(1_000L, r.next(80_000))
    }

    @Test
    fun itGivesUpAfterAFewMinutesWhateverIsLeft() {
        val r = Reconnects(delaysMs = List(100) { 1_000L }, giveUpMs = 180_000)
        assertEquals(1_000L, r.next(10_000))
        assertEquals(1_000L, r.next(190_000))
        assertNull(r.next(190_001))
        // After steady playback the clock starts again from the next break.
        r.playing(true, 200_000)
        r.playing(true, 230_000)
        assertEquals(1_000L, r.next(500_000))
    }

    @Test
    fun aNewVideoStartsWithEveryAttempt() {
        val r = Reconnects(delaysMs = listOf(1_000))
        r.next(0)
        assertNull(r.next(1))
        r.reset()
        assertEquals(1_000L, r.next(2))
    }
}
