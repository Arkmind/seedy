package io.github.arkmind.seedy.tv.watchnext

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.tv.TvContract
import android.media.tv.TvContract.WatchNextPrograms
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.arkmind.seedy.core.SeedyCore
import io.github.arkmind.seedy.core.format.Images
import io.github.arkmind.seedy.core.home.WatchNextEntry
import io.github.arkmind.seedy.core.home.watchNext
import io.github.arkmind.seedy.core.notify.CoreOwner
import io.github.arkmind.seedy.core.session.SessionState
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.concurrent.TimeUnit

/**
 * Settings · Home screen › "Continue watching on the Google TV home" (this TV). The profile signed in last on this TV
 * gets its titles in progress, then its next episodes, in the home screen's Continue watching row (Android TV's "Play
 * next"), through Android's Watch Next programs. The row is refreshed when the app goes to the background, when the
 * profile or the setting changes, and every hour (to catch what was watched elsewhere). A card plays its title as
 * that profile ([WatchNextLinks]).
 */
object WatchNext {
    private const val Now = "watch-next"
    private const val Hourly = "watch-next-hourly"
    private const val InProgress = 8
    private const val UpNext = 4
    private const val ArtWidth = 720

    /** One refresh at a time (the hourly one and the app's may meet). */
    private val lock = Mutex()

    /** Keeps the row in step with the setting and whoever is signed in while [activity] lives; refreshes it on leaving. */
    fun watch(activity: ComponentActivity, core: SeedyCore) {
        val context = activity.applicationContext
        activity.lifecycleScope.launch {
            combine(core.device.flow.map { it.googleTvContinue }, core.sessions.state.map(::who)) { on, who -> who?.let { on to it } }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { (on, _) -> if (on) start(context) else stop(context) }
        }
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            // A few seconds later: the player reports where it stopped as it closes.
            if (event == Lifecycle.Event.ON_STOP && core.device.current.googleTvContinue) refresh(context, delaySeconds = 5)
        })
    }

    /** Who the row is for, as far as changes go (null while the app starts). */
    private fun who(state: SessionState): String? = when (state) {
        SessionState.Starting -> null
        SessionState.NoServer -> "none"
        is SessionState.SignedOut -> "out:" + state.server.id
        is SessionState.Unreachable -> "away:" + state.server.id
        is SessionState.SignedIn -> "in:" + state.session.server.id + ":" + state.session.account.userId
    }

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** A refresh as soon as the network allows (one waiting at most). */
    private fun refresh(context: Context, delaySeconds: Long = 0) {
        val request = OneTimeWorkRequestBuilder<WatchNextWorker>().setConstraints(online).setInitialDelay(delaySeconds, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(Now, ExistingWorkPolicy.REPLACE, request)
    }

    private fun start(context: Context) {
        val hourly = PeriodicWorkRequestBuilder<WatchNextWorker>(1, TimeUnit.HOURS).setConstraints(online).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(Hourly, ExistingPeriodicWorkPolicy.KEEP, hourly)
        refresh(context)
    }

    /** The setting is off: no more refreshes, and Seedy's cards leave the home screen. */
    private suspend fun stop(context: Context) {
        WorkManager.getInstance(context).run {
            cancelUniqueWork(Hourly)
            cancelUniqueWork(Now)
        }
        withContext(Dispatchers.IO) { lock.withLock { clear(context) } }
    }

    /**
     * Puts the row in step: the last profile's titles while it can be reached; none once it's signed out (or the
     * setting is off). A server that doesn't answer leaves the row as it was.
     */
    suspend fun sync(context: Context, core: SeedyCore): Unit = withContext(Dispatchers.IO) {
        lock.withLock<Unit> {
            if (!core.device.current.googleTvContinue) return@withLock clear(context)
            val server = core.store.current().currentServer
            val account = server?.account(server.lastUserId) ?: return@withLock clear(context)
            val session = try {
                core.sessions.background(server, account)
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidStatusException) {
                if (e.status == 401) null else return@withLock
            } catch (_: Exception) {
                return@withLock
            } ?: return@withLock clear(context)
            val entries = try {
                session.watchNext(InProgress, UpNext)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withLock
            }
            runCatching { publish(context, session, entries) }.onFailure { Log.w("WatchNext", "The home screen's row couldn't be updated", it) }
        }
    }

    private class Row(val id: Long, val key: String?, val browsable: Boolean, val engaged: Long)

    private fun rows(resolver: ContentResolver): List<Row> {
        val columns = arrayOf(
            WatchNextPrograms._ID, WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, WatchNextPrograms.COLUMN_BROWSABLE,
            WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
        )
        // Android only lets an app see its own programs, and refuses a selection.
        return resolver.query(WatchNextPrograms.CONTENT_URI, columns, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) add(Row(c.getLong(0), c.getString(1), c.getInt(2) != 0, c.getLong(3)))
            }
        }.orEmpty()
    }

    private fun publish(context: Context, session: UserSession, entries: List<WatchNextEntry>) {
        val resolver = context.contentResolver
        val existing = rows(resolver).filter { it.key != null }.associateBy { it.key }
        val images = Images(session.api)
        val episodeImages = (session.seedy?.prefs?.custom?.get("home.episodeImages") as? JsonPrimitive)?.content != "false"
        val keep = mutableSetOf<Long>()
        val art = mutableSetOf<String>()
        for (entry in entries) {
            val key = "${session.server.id}:${session.account.userId}:${entry.item.id}"
            val row = existing[key]
            // Removed from the row by the person: it comes back only once they play it again.
            if (row != null && !row.browsable && entry.lastEngaged <= row.engaged) {
                keep += row.id
                continue
            }
            val picture = images.landscape(entry.item, ArtWidth, episodeImages)?.let { WatchNextArt.save(context, it) }
            picture?.let(art::add)
            val values = values(context, session, entry, key, picture)
            if (row != null && row.browsable) {
                resolver.update(TvContract.buildWatchNextProgramUri(row.id), values, null, null)
                keep += row.id
            } else {
                if (row != null) resolver.delete(TvContract.buildWatchNextProgramUri(row.id), null, null)
                resolver.insert(WatchNextPrograms.CONTENT_URI, values)?.let { keep += ContentUris.parseId(it) }
            }
        }
        for (row in existing.values + rows(resolver).filter { it.key == null }) {
            if (row.id !in keep) resolver.delete(TvContract.buildWatchNextProgramUri(row.id), null, null)
        }
        WatchNextArt.keepOnly(context, art)
    }

    private fun values(context: Context, session: UserSession, entry: WatchNextEntry, key: String, picture: String?): ContentValues {
        val item = entry.item
        val episode = item.type == BaseItemKind.EPISODE
        val link = WatchNextLink(session.server.id, session.account.userId, item.id)
        return ContentValues().apply {
            put(
                WatchNextPrograms.COLUMN_TYPE,
                when (item.type) {
                    BaseItemKind.MOVIE -> WatchNextPrograms.TYPE_MOVIE
                    BaseItemKind.EPISODE -> WatchNextPrograms.TYPE_TV_EPISODE
                    else -> WatchNextPrograms.TYPE_CLIP
                },
            )
            put(WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, if (entry.next) WatchNextPrograms.WATCH_NEXT_TYPE_NEXT else WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            put(WatchNextPrograms.COLUMN_TITLE, (if (episode) item.seriesName else null) ?: item.name.orEmpty())
            put(WatchNextPrograms.COLUMN_EPISODE_TITLE, if (episode) item.name else null)
            put(WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER, if (episode) item.parentIndexNumber?.toString() else null)
            put(WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, if (episode) item.indexNumber?.toString() else null)
            put(WatchNextPrograms.COLUMN_SHORT_DESCRIPTION, item.overview?.take(300))
            put(WatchNextPrograms.COLUMN_RELEASE_DATE, item.productionYear?.toString())
            put(WatchNextPrograms.COLUMN_POSTER_ART_URI, picture?.let { WatchNextArt.uri(context, it).toString() })
            put(WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, WatchNextPrograms.ASPECT_RATIO_16_9)
            putMillis(WatchNextPrograms.COLUMN_DURATION_MILLIS, item.runTimeTicks)
            putMillis(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, if (entry.next) null else item.userData?.playbackPositionTicks)
            put(WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, entry.lastEngaged)
            put(WatchNextPrograms.COLUMN_INTENT_URI, WatchNextLinks.intentUri(context, link))
            put(WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, key)
            put(WatchNextPrograms.COLUMN_CONTENT_ID, item.id.toString())
        }
    }

    /** Jellyfin's ticks (100 ns) as the milliseconds Android wants; none when unknown. */
    private fun ContentValues.putMillis(column: String, ticks: Long?) {
        val ms = ticks?.takeIf { it > 0 }?.div(10_000)?.coerceAtMost(Int.MAX_VALUE.toLong())
        if (ms != null) put(column, ms.toInt()) else putNull(column)
    }

    /** Seedy's cards leave the home screen. */
    private fun clear(context: Context) {
        runCatching { context.contentResolver.delete(WatchNextPrograms.CONTENT_URI, null, null) }
        WatchNextArt.keepOnly(context, emptySet())
    }
}

/** A refresh of the TV home screen's row (right away, or the hourly one). */
class WatchNextWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val core = (applicationContext as? CoreOwner)?.core ?: return Result.success()
        WatchNext.sync(applicationContext, core)
        return Result.success()
    }
}
