package io.github.arkmind.seedy.core.browse

import io.github.arkmind.seedy.core.format.duration
import io.github.arkmind.seedy.core.format.episodeTag
import io.github.arkmind.seedy.core.format.join
import io.github.arkmind.seedy.core.format.remainingSeconds
import io.github.arkmind.seedy.core.format.ticksToSeconds
import io.github.arkmind.seedy.core.format.timeLeft
import io.github.arkmind.seedy.core.format.yearRange
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.genreApi
import org.jellyfin.sdk.api.client.extensions.personApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import java.text.Normalizer
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/*
 * Search, as the web app's engine (web/src/ui/search/engine.ts): accents don't matter, years and genres work as
 * search words ("thriller 2024"), a person's name brings their titles, and when nothing matches a looser query keeps
 * names within a small typo distance. Results are ranked by how close the name is, then by what the person watches.
 */

enum class HitKind { Movie, Show, Collection, Episode, Person, Channel }

data class Hit(val kind: HitKind, val item: BaseItemDto, val score: Double, val match: String)

data class SearchResults(
    val q: String,
    val best: Hit?,
    val titles: List<Hit>,
    val episodes: List<Hit>,
    val people: List<Hit>,
    val channels: List<Hit> = emptyList(),
    val loose: Boolean = false,
) {
    /** Everything, best first. */
    val all: List<Hit> get() = (titles + episodes + people + channels).sortedByDescending { it.score }.let { list -> best?.let { b -> listOf(b) + (list - b) } ?: list }
    val isEmpty: Boolean get() = titles.isEmpty() && episodes.isEmpty() && people.isEmpty() && channels.isEmpty()
}

/** Lower case, no accents, punctuation as spaces: the clean form Jellyfin keeps for names. */
fun norm(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

/** The parts of [text] that match [query], for the accent-coloured highlight: the whole query first, else each word. */
fun marks(text: String, query: String): List<Pair<String, Boolean>> {
    val q = norm(query)
    if (q.isEmpty() || text.isEmpty()) return listOf(text to false)
    val n = StringBuilder()
    val at = ArrayList<Int>()
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        val len = Character.charCount(cp)
        var c = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
        if (!c.all { it.isLetterOrDigit() }) c = " "
        if (c == " " && n.endsWith(" ")) { i += len; continue }
        for (ch in c) { n.append(ch); at.add(i) }
        i += len
    }
    at.add(text.length)
    val hay = n.toString()
    val ranges = mutableListOf<IntRange>()
    fun add(start: Int, length: Int) {
        val end = start + length
        ranges += at[start] until (if (end < at.size) at[end] else text.length)
    }
    val whole = findWord(hay, q)
    if (whole >= 0) add(whole, q.length) else for (w in q.split(' ')) {
        if (w.length < 2) continue
        val p = findWord(hay, w)
        if (p >= 0) add(p, w.length)
    }
    if (ranges.isEmpty()) return listOf(text to false)
    val out = mutableListOf<Pair<String, Boolean>>()
    var pos = 0
    for (r in ranges.sortedBy { it.first }) {
        val e = r.last + 1
        if (e <= pos) continue
        val from = maxOf(r.first, pos)
        if (from > pos) out += text.substring(pos, from) to false
        out += text.substring(from, e) to true
        pos = e
    }
    if (pos < text.length) out += text.substring(pos) to false
    return out
}

/** Prefers a match at the start of a word ("north" in "Glass Harbor North Pier"). */
private fun findWord(hay: String, needle: String): Int {
    var p = hay.indexOf(needle)
    val first = p
    while (p > 0 && hay[p - 1] != ' ') p = hay.indexOf(needle, p + 1)
    return if (p >= 0) p else first
}

/** Damerau–Levenshtein (optimal string alignment) distance, for typo tolerance. */
private fun distance(a: String, b: String): Int {
    val m = a.length
    val n = b.length
    if (m == 0) return n
    if (n == 0) return m
    val d = Array(m + 1) { IntArray(n + 1) }
    for (i in 0..m) d[i][0] = i
    for (j in 0..n) d[0][j] = j
    for (i in 1..m) for (j in 1..n) {
        val cost = if (a[i - 1] == b[j - 1]) 0 else 1
        d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
        if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
    }
    return d[m][n]
}

private fun typoDistance(q: String, name: String?): Int {
    val n = norm(name ?: return Int.MAX_VALUE)
    if (q.isEmpty() || n.isEmpty()) return Int.MAX_VALUE
    val words = n.split(' ')
    val k = q.split(' ').size
    var best = distance(q, n)
    for (i in 0..(words.size - k)) best = minOf(best, distance(q, words.subList(i, i + k).joinToString(" ")))
    return best
}

private fun typoAllowance(q: String) = when {
    q.length < 4 -> 0
    q.length < 7 -> 1
    q.length < 11 -> 2
    else -> 3
}

private val GenreAliases = mapOf(
    "sci fi" to listOf("science fiction", "sci fi fantasy", "sci fi"), "scifi" to listOf("science fiction", "sci fi fantasy"),
    "sf" to listOf("science fiction", "sci fi fantasy"), "science fiction" to listOf("science fiction", "sci fi fantasy"),
    "fantasy" to listOf("fantasy", "sci fi fantasy"), "action" to listOf("action", "action adventure"),
    "adventure" to listOf("adventure", "action adventure"), "war" to listOf("war", "war politics"),
    "kids" to listOf("kids", "children", "family"), "children" to listOf("children", "kids", "family"),
    "cartoon" to listOf("animation"), "cartoons" to listOf("animation"), "animated" to listOf("animation"),
    "anime" to listOf("anime", "animation"), "doc" to listOf("documentary"), "docs" to listOf("documentary"),
    "documentaries" to listOf("documentary"), "comedies" to listOf("comedy"), "romcom" to listOf("romance", "comedy"),
    "true crime" to listOf("crime", "documentary"), "scary" to listOf("horror"), "musicals" to listOf("musical", "music"),
)

/** A query with its years and genre words taken out ("thriller 2024" → Thriller in 2024). */
data class Parsed(val rest: String, val years: List<Int>, val genres: List<List<String>>)

fun parseQuery(text: String, genres: List<String>): Parsed {
    val years = mutableListOf<Int>()
    val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.filter { w ->
        Regex("^(19\\d\\d|20\\d\\d)$").find(w)?.let { years += it.groupValues[1].toInt(); return@filter false }
        Regex("^(?:(19|20)?(\\d)0)'?s$", RegexOption.IGNORE_CASE).find(w)?.let { m ->
            val century = m.groupValues[1].takeIf { it.isNotEmpty() }?.toInt()?.times(100) ?: if (m.groupValues[2].toInt() >= 3) 1900 else 2000
            val start = century + m.groupValues[2].toInt() * 10
            for (k in 0 until 10) years += start + k
            return@filter false
        }
        true
    }
    val byNorm = genres.associateBy { norm(it) }
    val phrases = HashMap<String, MutableSet<String>>()
    fun put(phrase: String, names: List<String>) {
        val present = names.mapNotNull { byNorm[it] }
        if (present.isNotEmpty()) phrases.getOrPut(phrase) { LinkedHashSet() }.addAll(present)
    }
    for (n in byNorm.keys) {
        put(n, listOf(n))
        put(n + "s", listOf(n))
        if (n.endsWith("y")) put(n.dropLast(1) + "ies", listOf(n))
    }
    for ((alias, targets) in GenreAliases) put(alias, targets)
    val keys = phrases.keys.sortedWith(compareByDescending<String> { it.split(' ').size }.thenByDescending { it.length })
    val found = mutableListOf<List<String>>()
    val keep = mutableListOf<String>()
    val normed = words.map(::norm)
    var i = 0
    while (i < words.size) {
        val hit = keys.firstOrNull { k -> val len = k.split(' ').size; normed.subList(i, minOf(normed.size, i + len)).joinToString(" ") == k }
        if (hit != null) {
            found += phrases.getValue(hit).toList()
            i += hit.split(' ').size
        } else {
            keep += words[i]
            i++
        }
    }
    return Parsed(keep.joinToString(" "), years, found)
}

fun kindOf(item: BaseItemDto): HitKind = when (item.type) {
    BaseItemKind.SERIES -> HitKind.Show
    BaseItemKind.BOX_SET -> HitKind.Collection
    BaseItemKind.EPISODE -> HitKind.Episode
    BaseItemKind.PERSON -> HitKind.Person
    BaseItemKind.TV_CHANNEL -> HitKind.Channel
    else -> HitKind.Movie
}

private fun nameScore(q: String, item: BaseItemDto): Double {
    if (q.isEmpty()) return 0.0
    var best = 0.0
    for (raw in listOfNotNull(item.name, item.originalTitle)) {
        val n = norm(raw)
        val s = when {
            n == q -> 100.0
            n.startsWith("$q ") -> 90.0
            n.startsWith(q) -> 85.0
            " $n".contains(" $q") -> 72.0
            n.contains(q) -> 55.0
            q.split(' ').all { " $n".contains(" $it") } -> 52.0
            q.split(' ').all { n.contains(it) } -> 48.0
            else -> 0.0
        }
        if (s > best) best = s
    }
    return best
}

/** What the person watches counts: in progress, favourites, recently played. */
private fun watchBoost(item: BaseItemDto): Double {
    val ud = item.userData ?: return 0.0
    var b = 0.0
    if ((ud.playbackPositionTicks) > 0) b += 10
    if (item.type == BaseItemKind.SERIES && !ud.played && (ud.playedPercentage ?: 0.0) > 0) b += 10
    if (ud.isFavorite) b += 6
    ud.lastPlayedDate?.let { last ->
        val days = Duration.between(last, LocalDateTime.now()).toDays()
        b += if (days < 30) 6 else if (days < 365) 3 else 1
    }
    return b
}

private fun weight(kind: HitKind) = when (kind) {
    HitKind.Movie, HitKind.Show -> 0.0
    HitKind.Collection -> -4.0
    HitKind.Episode -> -8.0
    HitKind.Person -> -2.0
    HitKind.Channel -> -6.0
}

private fun hit(item: BaseItemDto, base: Double, match: String): Hit {
    val kind = kindOf(item)
    return Hit(kind, item, base + watchBoost(item) + weight(kind), match)
}

/** Runs searches for one signed-in person; keeps the server's genres and each person's line for a while. */
class SearchEngine(private val session: UserSession) {
    private val browse = Browse(session)
    private var genres: List<String>? = null
    private val personLines = ConcurrentHashMap<UUID, String>()
    private val cache = ConcurrentHashMap<String, Pair<Long, SearchResults>>()

    private suspend fun genres(): List<String> = genres ?: runCatching {
        withContext(Dispatchers.IO) {
            session.api.genreApi.getGenres(userId = session.userId, includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES), sortBy = listOf(ItemSortBy.SORT_NAME), enableImages = false)
                .content.items.mapNotNull { it.name }
        }
    }.getOrDefault(emptyList()).also { if (it.isNotEmpty()) genres = it }

    /** The server's genres, for the idle page's chips. */
    suspend fun genreNames(): List<String> = genres()

    private suspend fun items(q: ItemsQuery) = runCatching { browse.items(q.copy(fields = Fields)) }.getOrNull()

    private suspend fun persons(term: String, limit: Int): List<BaseItemDto> = runCatching {
        withContext(Dispatchers.IO) {
            session.api.personApi.getPersons(userId = session.userId, searchTerm = term, limit = limit, enableImageTypes = listOf(ImageType.PRIMARY), imageTypeLimit = 1).content.items
        }
    }.getOrDefault(emptyList())

    /** Everything that matches [raw]: titles, episodes, people, and (with [channels]) Live TV channels. */
    suspend fun search(raw: String, channels: Boolean = false, limit: Int = 30): SearchResults = coroutineScope {
        val text = raw.trim().replace(Regex("\\s+"), " ")
        val key = "$channels|${text.lowercase()}"
        cache[key]?.let { (at, r) -> if (System.currentTimeMillis() - at < 60_000) return@coroutineScope r }

        val parsed = parseQuery(text, genres())
        val filters = parsed.years.isNotEmpty() || parsed.genres.isNotEmpty()
        val words = if (filters) parsed.rest else text
        val nq = norm(text)
        val nw = norm(words)
        val genreList = parsed.genres.flatten().distinct()

        val byName = async { items(ItemsQuery(searchTerm = text, includeTypes = TitleTypes, limit = limit)) }
        val eps = async { items(ItemsQuery(searchTerm = text, includeTypes = listOf(BaseItemKind.EPISODE), limit = limit / 2)) }
        val people = async { if (nw.length >= 2) persons(words, limit / 2) else emptyList() }
        val chans = async { if (channels) items(ItemsQuery(searchTerm = text, includeTypes = listOf(BaseItemKind.TV_CHANNEL), limit = 8, fields = listOf(ItemFields.CHANNEL_INFO))) else null }
        val filtered = async {
            if (!filters) null else items(
                ItemsQuery(
                    searchTerm = parsed.rest.ifBlank { null }, includeTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES), genres = genreList,
                    years = parsed.years, limit = limit * 2, sortBy = listOf(ItemSortBy.COMMUNITY_RATING, ItemSortBy.SORT_NAME), sortOrder = SortOrder.DESCENDING,
                ),
            )
        }

        val titles = LinkedHashMap<UUID, Hit>()
        fun addTitle(h: Hit) {
            val prev = titles[h.item.id]
            if (prev == null || prev.score < h.score) titles[h.item.id] = h
        }
        fun genreOk(item: BaseItemDto) = parsed.genres.all { g -> item.genres.orEmpty().any { it in g } }
        byName.await()?.items?.forEach { addTitle(hit(it, nameScore(nq, it).takeIf { s -> s > 0 } ?: 45.0, text)) }
        filtered.await()?.items?.filter(::genreOk)?.forEach { addTitle(hit(it, if (nw.isNotEmpty()) (nameScore(nw, it).takeIf { s -> s > 0 } ?: 45.0) + 4 else 40.0, parsed.rest)) }

        val peopleHits = people.await().map { hit(it, nameScore(nw, it).takeIf { s -> s > 0 } ?: 45.0, words) }
        val strong = peopleHits.filter { it.score >= 50 }.maxByOrNull { it.score }
        if (strong != null && (filters || titles.size < 4)) {
            items(
                ItemsQuery(
                    personIds = listOf(strong.item.id), includeTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES), genres = genreList, years = parsed.years,
                    limit = limit, sortBy = listOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.SORT_NAME), sortOrder = SortOrder.DESCENDING,
                ),
            )?.items?.filter(::genreOk)?.forEach { addTitle(hit(it, 38.0, "")) }
        }

        var titleHits = titles.values.toList()
        val epHits = eps.await()?.items.orEmpty().map { hit(it, nameScore(nq, it).takeIf { s -> s > 0 } ?: 45.0, text) }
        var personHits = peopleHits
        val channelHits = chans.await()?.items.orEmpty().map { hit(it, nameScore(nq, it).takeIf { s -> s > 0 } ?: 45.0, text) }
        var loose = false
        if (titleHits.isEmpty() && epHits.isEmpty() && personHits.isEmpty() && nq.length >= 3 && !filters) {
            val found = looseSearch(nq, limit)
            titleHits = found.first
            personHits = found.second
            loose = titleHits.isNotEmpty() || personHits.isNotEmpty()
        }
        val byScore = compareByDescending<Hit> { it.score }.thenBy { it.item.name.orEmpty() }
        val sortedTitles = titleHits.sortedWith(byScore)
        val sortedEps = epHits.sortedWith(byScore)
        val best = (sortedTitles + sortedEps).sortedWith(byScore).firstOrNull()
        val r = SearchResults(text, best, sortedTitles, sortedEps, personHits.sortedWith(byScore), channelHits.sortedWith(byScore), loose)
        cache[key] = System.currentTimeMillis() to r
        r
    }

    private suspend fun looseSearch(nq: String, limit: Int): Pair<List<Hit>, List<Hit>> {
        val allow = typoAllowance(nq)
        if (allow == 0) return emptyList<Hit>() to emptyList()
        val words = nq.split(' ').filter { it.length >= 3 }.sortedByDescending { it.length }
        val main = words.firstOrNull() ?: nq
        val tries = mutableListOf<String>()
        if (words.size > 1) tries += words.take(2)
        for (n in listOf(main.length - 1, main.length - 2, (main.length + 1) / 2)) if (n in 3 until main.length) tries += main.take(n)
        if (main.length >= 6) tries += main.takeLast((main.length + 1) / 2)
        fun near(it: BaseItemDto) = minOf(typoDistance(nq, it.name), typoDistance(nq, it.originalTitle))
        val seen = HashSet<UUID>()
        for (term in tries.distinct().take(4)) {
            val ti = items(ItemsQuery(searchTerm = term, includeTypes = TitleTypes, limit = 40))?.items.orEmpty()
            val pe = persons(term, 20)
            fun keep(it: BaseItemDto) = it.id !in seen && near(it) <= allow
            fun score(it: BaseItemDto) = 30.0 * (1 - near(it).toDouble() / maxOf(4, nq.length))
            val titles = ti.filter(::keep).map { hit(it, 0.0, "").copy(score = score(it) + watchBoost(it)) }
            val people = pe.filter(::keep).map { hit(it, 0.0, "").copy(score = score(it)) }
            if (titles.isNotEmpty() || people.isNotEmpty()) return titles.take(limit) to people
            (ti + pe).forEach { seen += it.id }
        }
        return emptyList<Hit>() to emptyList()
    }

    /** "Actor · 4 titles on the server" ([short]: "Actor · 4 titles"); one small request per person, kept. */
    suspend fun personLine(person: BaseItemDto, short: Boolean = false): String {
        val line = personLines[person.id] ?: run {
            suspend fun look(types: List<BaseItemKind>) = runCatching {
                browse.items(ItemsQuery(personIds = listOf(person.id), includeTypes = types, limit = 1, fields = listOf(ItemFields.PEOPLE), images = emptyList()))
            }.getOrNull()
            val titles = look(listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES))
            val (res, unit) = if (titles != null && titles.totalRecordCount > 0) titles to "title" else look(listOf(BaseItemKind.EPISODE)) to "episode"
            val type = res?.items?.firstOrNull()?.people?.firstOrNull { it.id == person.id }?.type?.serialName
            "${Roles[type] ?: "Person"}|${res?.totalRecordCount ?: 0}|$unit".also { personLines[person.id] = it }
        }
        val (role, count, unit) = line.split('|')
        val n = count.toInt()
        val what = if (unit == "title") tn(n, "{n} title", "{n} titles") else tn(n, "{n} episode", "{n} episodes")
        return join(t(role), if (n == 0) null else if (short) what else t("{count} on the server", "count" to what))
    }

    companion object {
        private val TitleTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.BOX_SET)
        private val Fields = listOf(
            ItemFields.PRIMARY_IMAGE_ASPECT_RATIO, ItemFields.GENRES, ItemFields.CHILD_COUNT, ItemFields.RECURSIVE_ITEM_COUNT,
            ItemFields.ORIGINAL_TITLE, ItemFields.DATE_CREATED, ItemFields.CHANNEL_INFO,
        )
        private val Roles = mapOf(
            "Actor" to "Actor", "GuestStar" to "Actor", "Director" to "Director", "Writer" to "Writer", "Producer" to "Producer",
            "Composer" to "Composer", "Creator" to "Creator",
        )
    }
}

/** A result row's line: "2021 · Drama · 1 h 49 min", "2019 · Thriller · not watched", "S1 E2 · 48 min". */
fun rowMeta(item: BaseItemDto): String {
    val ud = item.userData
    val inProgress = (ud?.playbackPositionTicks ?: 0L) > 0 && item.runTimeTicks != null
    val run = item.runTimeTicks?.let { duration(ticksToSeconds(it)) }.orEmpty()
    return when (item.type) {
        BaseItemKind.SERIES -> join(item.yearRange, item.childCount?.takeIf { it > 0 }?.let { tn(it, "{n} season", "{n} seasons") })
        BaseItemKind.BOX_SET -> join(t("Collection"), item.childCount?.takeIf { it > 0 }?.let { tn(it, "{n} title", "{n} titles") })
        BaseItemKind.EPISODE -> join(item.episodeTag, if (inProgress) timeLeft(item.remainingSeconds) else run)
        BaseItemKind.PERSON -> ""
        BaseItemKind.TV_CHANNEL -> join(item.channelNumber?.let { t("Channel {n}", "n" to it) }, item.currentProgram?.name)
        else -> join(
            item.productionYear?.toString(), item.genres?.firstOrNull(),
            if (inProgress) timeLeft(item.remainingSeconds) else if (ud?.played == true) run else if (ud != null) t("not watched") else run,
        )
    }
}
