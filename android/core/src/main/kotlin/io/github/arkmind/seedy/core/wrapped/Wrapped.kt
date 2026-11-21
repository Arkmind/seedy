package io.github.arkmind.seedy.core.wrapped

import io.github.arkmind.seedy.core.seedy.SeedyApi
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.TimeZone

/*
 * Seedy Wrapped (GET /Seedy/Me/Wrapped): each profile's year on Seedy, built once in December (1 January to 30 November,
 * in the time zone the apps report) and kept. The apps write every sentence themselves from these numbers
 * (WrappedCopy.kt, the web's screens/wrapped/copy.ts). Shapes: the plugin's Api/WrappedDtos.cs.
 */

/** Which years the profile can open, and the one Home offers while it's December. */
@Serializable
data class WrappedStatus(val years: List<Int> = emptyList(), val current: WrappedTeaser? = null)

/** What the Home banner says about this year's Wrapped. */
@Serializable
data class WrappedTeaser(val year: Int, val watchMs: Long = 0, val stories: Int = 0)

@Serializable
data class Wrapped(
    /** The snapshot's format: each year's Wrapped is kept for good, so later apps may meet older ones. */
    val v: Int = 1,
    val year: Int,
    val builtAt: String = "",
    val zone: String = "",
    /** The stories to show, in order; names this app doesn't know are skipped ([storiesOf]). */
    val stories: List<String> = emptyList(),
    /** Video watched (shows, movies, Live TV, other videos), ms. */
    val watchMs: Long = 0,
    val plays: Int = 0,
    val prevWatchMs: Long? = null,
    val rank: Int? = null,
    val viewers: Int = 0,
    val genres: List<WrappedGenre> = emptyList(),
    val otherPct: Int = 0,
    val shows: List<WrappedTitle> = emptyList(),
    val seasonsFinished: Int = 0,
    val showsStarted: Int = 0,
    val movie: WrappedMovie? = null,
    val movies: Int = 0,
    /** Movies played (to the end or not), movies watched on two days or more, and the time on movies. */
    val moviesPlayed: Int = 0,
    val moviesRewatched: Int = 0,
    val movieMs: Long = 0,
    /** Shows with an episode watched, shows whose last episode was watched to the end, their episodes and time. */
    val showsWatched: Int = 0,
    val showsFinished: Int = 0,
    val episodes: Int = 0,
    val showMs: Long = 0,
    val binge: WrappedBinge? = null,
    val still: WrappedStill = WrappedStill(),
    val clock: WrappedClock? = null,
    val personality: WrappedPersonality = WrappedPersonality(),
    val musicMs: Long = 0,
    val books: Int = 0,
)

@Serializable
data class WrappedGenre(val name: String = "", val key: String? = null, val pct: Int = 0)

/** A show: [item] is the series (for its pictures), null once it left the library. */
@Serializable
data class WrappedTitle(val id: String = "", val name: String = "", val episodes: Int = 0, val watchMs: Long = 0, val item: BaseItemDto? = null)

@Serializable
data class WrappedMovie(
    val id: String = "",
    val name: String = "",
    val views: Int = 0,
    val watchMs: Long = 0,
    /** The day of each view (yyyy-MM-dd), oldest first. */
    val dates: List<String> = emptyList(),
    val item: BaseItemDto? = null,
)

@Serializable
data class WrappedBinge(
    val id: String = "",
    val name: String = "",
    val episodes: Int = 0,
    val season: Int? = null,
    val seasonTo: Int? = null,
    val lastSeason: Int? = null,
    val lastEpisode: Int? = null,
    /** Local time, yyyy-MM-ddTHH:mm. */
    val start: String = "",
    val end: String = "",
    /** Minutes watched of each episode, in play order (absent on a Wrapped built before the plugin counted them). */
    val minutes: List<Int>? = null,
    val item: BaseItemDto? = null,
)

@Serializable
data class WrappedStill(val asked: Int = 0, val yes: Int = 0)

@Serializable
data class WrappedClock(
    /** Video watched in each hour of the day (local, 0-23), ms. */
    val hours: List<Long> = emptyList(),
    /** The busiest ten minutes (HH:mm). */
    val peak: String = "",
    /** night, morning, afternoon or evening: the part of the day the peak falls in. */
    val kind: String = "night",
    val pct: Int = 0,
    /** The busiest day of the week, 1 = Monday … 7 = Sunday. */
    val day: Int = 7,
)

@Serializable
data class WrappedPersonality(val key: String = "easygoing", val stats: List<WrappedStat> = emptyList())

@Serializable
data class WrappedStat(val key: String = "", val value: Double = 0.0, val extra: Double? = null)

@Serializable
internal data class Moment(val kind: String, val itemId: String? = null, val ms: Long? = null)

/** The stories, in the server's order. */
enum class StoryKey(val key: String) {
    Cover("cover"), Hours("hours"), Lover("lover"), Genres("genres"), Shows("shows"), Movie("movie"), Binge("binge"), Clock("clock"),
    Personality("personality"), Summary("summary"),
}

/** The stories this app knows, in the server's order. */
fun storiesOf(w: Wrapped): List<StoryKey> = w.stories.mapNotNull { s -> StoryKey.entries.firstOrNull { it.key == s } }

/** The device's time zone, which the server counts days and hours in. */
fun deviceZone(): String = runCatching { TimeZone.getDefault().id }.getOrDefault("UTC")

/** A moment the players report for Wrapped. */
enum class MomentKind(val key: String) { IntroSkipped("introSkipped"), StillAsked("stillAsked"), StillYes("stillYes"), StillStop("stillStop") }

/** Fire-and-forget reports: an intro skipped, "Still watching?" asked or answered (servers with Seedy only). */
object Moments {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun record(session: UserSession, kind: MomentKind, itemId: String? = null, ms: Long? = null) {
        val api: SeedyApi = session.seedyApi ?: return
        scope.launch { runCatching { api.moment(kind.key, itemId, ms?.takeIf { it > 0 }) } }
    }
}
