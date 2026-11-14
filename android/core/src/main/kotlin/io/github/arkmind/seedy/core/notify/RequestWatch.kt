package io.github.arkmind.seedy.core.notify

import io.github.arkmind.seedy.core.SeedyCore
import io.github.arkmind.seedy.core.mediarequests.MediaRequest
import io.github.arkmind.seedy.core.requests.SeerrRequest

/** The app's core, for code that runs without a screen (workers): the Application owns it. */
interface CoreOwner {
    val core: SeedyCore
}

/** Where a request is, as far as its notifications go. */
enum class Stage { Waiting, Approved, Ready, Closed }

/** One of the person's requests: [key] is "seerr:12" or "media:ab12". */
data class Watched(val key: String, val stage: Stage)

/** A request that moved on since the last look: approved, or ready. */
data class Change(val key: String, val to: Stage)

/**
 * Notifications for the person's requests (board "Android · Notification shade"): what each request was at the last
 * look, kept per server and profile, against what it is now. The first look only records; after that a request
 * notifies when it's approved (it was waiting) and when it's ready (it wasn't), once.
 */
object RequestWatch {
    fun stageOf(r: SeerrRequest): Stage = when {
        r.status == "available" || (r.status == "partial" && r.requestStatus == "completed") -> Stage.Ready
        r.requestStatus == "declined" || r.requestStatus == "failed" -> Stage.Closed
        r.requestStatus == "pending" -> Stage.Waiting
        else -> Stage.Approved
    }

    fun stageOf(r: MediaRequest): Stage = when (r.status) {
        "ready" -> Stage.Ready
        "pending" -> Stage.Waiting
        "failed", "declined", "cancelled" -> Stage.Closed
        else -> Stage.Approved
    }

    fun keyOf(r: SeerrRequest): String = "seerr:${r.requestId}"

    fun keyOf(r: MediaRequest): String = "media:${r.id}"

    /**
     * What to tell the person: [before] is the last look (null: there wasn't one, so nothing is said). A request seen
     * for the first time that's already ready arrived between two looks, so it counts; one that's approved straight
     * away doesn't (the person just asked).
     */
    fun diff(before: Map<String, Stage>?, now: List<Watched>): List<Change> {
        if (before == null) return emptyList()
        return now.mapNotNull { w ->
            val was = before[w.key]
            when {
                w.stage == Stage.Ready && was != Stage.Ready -> Change(w.key, Stage.Ready)
                w.stage == Stage.Approved && was == Stage.Waiting -> Change(w.key, Stage.Approved)
                else -> null
            }
        }
    }

    /** What to keep for the next look: where each request is now (ones gone from the list are forgotten). */
    fun remember(now: List<Watched>): Map<String, Stage> = now.associate { it.key to it.stage }

    fun encode(seen: Map<String, Stage>): String = seen.entries.joinToString(";") { "${it.key}=${it.value.name}" }

    fun decode(text: String?): Map<String, Stage>? = text?.let {
        it.split(';').filter { p -> '=' in p }.mapNotNull { p ->
            val k = p.substringBeforeLast('=')
            Stage.entries.firstOrNull { s -> s.name == p.substringAfterLast('=') }?.let { s -> k to s }
        }.toMap()
    }
}
