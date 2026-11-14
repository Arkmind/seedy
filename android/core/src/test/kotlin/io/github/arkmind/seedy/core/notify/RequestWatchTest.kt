package io.github.arkmind.seedy.core.notify

import io.github.arkmind.seedy.core.mediarequests.MediaRequest
import io.github.arkmind.seedy.core.requests.SeerrRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestWatchTest {
    private fun seerr(id: Int, request: String, status: String) = SeerrRequest(requestId = id, tmdbId = id, requestStatus = request, status = status)

    private fun w(key: String, stage: Stage) = Watched(key, stage)

    @Test
    fun firstLookOnlyRecords() {
        val now = listOf(w("seerr:1", Stage.Ready), w("seerr:2", Stage.Approved))
        assertTrue(RequestWatch.diff(null, now).isEmpty())
        assertEquals(mapOf("seerr:1" to Stage.Ready, "seerr:2" to Stage.Approved), RequestWatch.remember(now))
    }

    @Test
    fun approvedWhenItWasWaiting() {
        val before = mapOf("seerr:1" to Stage.Waiting, "media:a" to Stage.Waiting)
        val now = listOf(w("seerr:1", Stage.Approved), w("media:a", Stage.Waiting))
        assertEquals(listOf(Change("seerr:1", Stage.Approved)), RequestWatch.diff(before, now))
    }

    @Test
    fun readyFromWaitingOrApprovedIsOneNotification() {
        val before = mapOf("seerr:1" to Stage.Waiting, "seerr:2" to Stage.Approved)
        val now = listOf(w("seerr:1", Stage.Ready), w("seerr:2", Stage.Ready))
        assertEquals(listOf(Change("seerr:1", Stage.Ready), Change("seerr:2", Stage.Ready)), RequestWatch.diff(before, now))
    }

    @Test
    fun noDuplicates() {
        val now = listOf(w("seerr:1", Stage.Ready), w("seerr:2", Stage.Approved))
        val first = RequestWatch.diff(mapOf("seerr:1" to Stage.Approved, "seerr:2" to Stage.Waiting), now)
        assertEquals(2, first.size)
        assertTrue(RequestWatch.diff(RequestWatch.remember(now), now).isEmpty())
    }

    @Test
    fun newRequests() {
        // Asked and approved since the last look: the person knows. Asked and already here: worth saying.
        val now = listOf(w("seerr:3", Stage.Approved), w("seerr:4", Stage.Ready), w("seerr:5", Stage.Waiting))
        assertEquals(listOf(Change("seerr:4", Stage.Ready)), RequestWatch.diff(emptyMap(), now))
    }

    @Test
    fun declinedSaysNothing() {
        assertTrue(RequestWatch.diff(mapOf("seerr:1" to Stage.Waiting), listOf(w("seerr:1", Stage.Closed))).isEmpty())
    }

    @Test
    fun stages() {
        assertEquals(Stage.Waiting, RequestWatch.stageOf(seerr(1, "pending", "pending")))
        assertEquals(Stage.Approved, RequestWatch.stageOf(seerr(1, "approved", "processing")))
        assertEquals(Stage.Approved, RequestWatch.stageOf(seerr(1, "approved", "partial")))
        assertEquals(Stage.Ready, RequestWatch.stageOf(seerr(1, "completed", "partial")))
        assertEquals(Stage.Ready, RequestWatch.stageOf(seerr(1, "approved", "available")))
        assertEquals(Stage.Closed, RequestWatch.stageOf(seerr(1, "declined", "none")))
        assertEquals(Stage.Approved, RequestWatch.stageOf(MediaRequest(id = "a", status = "downloading")))
        assertEquals(Stage.Ready, RequestWatch.stageOf(MediaRequest(id = "a", status = "ready")))
        assertEquals(Stage.Waiting, RequestWatch.stageOf(MediaRequest(id = "a", status = "pending")))
        assertEquals(Stage.Closed, RequestWatch.stageOf(MediaRequest(id = "a", status = "cancelled")))
    }

    @Test
    fun storesAndReadsBack() {
        val seen = mapOf("seerr:1" to Stage.Ready, "media:a=b" to Stage.Waiting)
        assertEquals(seen, RequestWatch.decode(RequestWatch.encode(seen)))
        assertEquals(null, RequestWatch.decode(null))
        assertEquals(emptyMap<String, Stage>(), RequestWatch.decode(""))
    }
}
