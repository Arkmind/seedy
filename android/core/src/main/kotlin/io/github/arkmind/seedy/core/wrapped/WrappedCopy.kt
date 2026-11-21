package io.github.arkmind.seedy.core.wrapped

import io.github.arkmind.seedy.core.i18n.I18n
import io.github.arkmind.seedy.core.i18n.formatNumber
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tc
import io.github.arkmind.seedy.core.i18n.tn
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/*
 * Every sentence Seedy Wrapped says, written from the year's numbers: the web's screens/wrapped/copy.ts, function for
 * function, with the same English (so the French comes from the web's i18n/fr/wrapped.ts). Keep both in step.
 */

/** JavaScript's Math.round: halves go up. */
private fun round(x: Double): Long = floor(x + 0.5).toLong()

fun hoursOf(ms: Long): Long = max(0L, round(ms / 3_600_000.0))

// ── Cover ──

/** Each story stays this long: nine take about two minutes. */
const val StorySeconds = 16

fun coverHint(stories: Int): String {
    val minutes = max(1L, round(stories * StorySeconds / 60.0)).toInt()
    return listOf(tn(stories, "{n} story", "{n} stories"), tn(minutes, "about a minute", "about {n} minutes"), t("tap to skip ahead")).joinToString(" · ")
}

// ── Hours ──

/** A chip, a stat line: the big part and what it means. */
data class Chip(val big: String, val small: String)

/** The three chips under the hours: days back to back (or plays, under a day), against last year, place on the server. */
fun hourChips(w: Wrapped): List<Chip> {
    val chips = mutableListOf<Chip>()
    val days = round(w.watchMs / 86_400_000.0).toInt()
    chips += if (days >= 1) Chip(tn(days, "{n} day", "{n} days"), t("back to back"))
    else Chip(tn(w.plays, "{n} play", "{n} plays"), t("this year"))
    val prev = w.prevWatchMs
    if (prev != null && prev > 0) {
        val pct = round((w.watchMs - prev).toDouble() / prev * 100)
        chips += Chip("${if (pct >= 0) "+" else "−"}${formatNumber(abs(pct))}%", t("vs {year}", "year" to w.year - 1))
    }
    w.rank?.takeIf { it != 0 }?.let { chips += Chip("#${formatNumber(it)}", t("on your server")) }
    return chips
}

// ── Shows or movies? ──

/** Which side won the year, by time: shows, unless movies took longer. */
fun loverShows(w: Wrapped): Boolean = w.showMs >= w.movieMs

private fun isOne(n: Int): Boolean =
    runCatching { android.icu.text.PluralRules.forLocale(I18n.locale).select(n.toDouble()) == "one" }.getOrElse { n == 1 }

/** "shows" or "movies" after a big number, in the number's form. */
fun loverUnit(n: Int, shows: Boolean): String = tc(if (shows) (if (isOne(n)) "show" else "shows") else (if (isOne(n)) "movie" else "movies"), "unit")

/** "1 h 52" (or "48 min"): a movie's average length. */
fun averageTime(ms: Double): String {
    val min = max(1L, round(ms / 60_000.0))
    val h = min / 60
    return if (h > 0) t("{h} h {mm}", "h" to formatNumber(h), "mm" to (min % 60).toString().padStart(2, '0')) else t("{m} min", "m" to formatNumber(min))
}

fun loverTitle(w: Wrapped): Pair<String, String> = t("You’re a") to (if (loverShows(w)) t("TV show lover") else t("movie lover"))

fun loverChips(w: Wrapped): List<String> {
    val hours = { ms: Long -> tn(hoursOf(ms).toInt(), "{n} hour", "{n} hours") }
    if (loverShows(w)) {
        return listOfNotNull(
            tn(w.episodes, "{n} episode", "{n} episodes"),
            hours(w.showMs),
            if (w.showsFinished > 0) tn(w.showsFinished, "{n} show finished", "{n} shows finished") else null,
        )
    }
    return listOfNotNull(
        hours(w.movieMs),
        if (w.moviesPlayed > 0) t("{time} on average", "time" to averageTime(w.movieMs.toDouble() / w.moviesPlayed)) else null,
        // English says "23 rewatched" either way; French agrees with the number (revu, revus).
        if (w.moviesRewatched > 0) rewatched(w.moviesRewatched) else null,
    )
}

private fun rewatched(n: Int): String {
    val key = "{n} rewatched|" + (if (isOne(n)) "one" else "other")
    val out = t(key, "n" to formatNumber(n))
    return if (out.endsWith("|one") || out.endsWith("|other")) t("{n} rewatched", "n" to formatNumber(n)) else out
}

/** The winner's share of the time, 0–100. */
fun loverPct(w: Wrapped): Int {
    val all = w.showMs + w.movieMs
    return if (all > 0) round((if (loverShows(w)) w.showMs else w.movieMs) * 100.0 / all).toInt() else 100
}

/** The other side, on the band: its count and what it took. */
fun loverOther(w: Wrapped): Pair<Int, String> {
    if (loverShows(w)) {
        val n = w.moviesPlayed
        return n to if (n > 0) "${tn(hoursOf(w.movieMs).toInt(), "{n} hour", "{n} hours")} · ${t("{time} on average", "time" to averageTime(w.movieMs.toDouble() / n))}" else t("Not one movie this year.")
    }
    val n = w.showsWatched
    return n to if (n > 0) "${tn(w.episodes, "{n} episode", "{n} episodes")} · ${tn(hoursOf(w.showMs).toInt(), "{n} hour", "{n} hours")}" else t("Not one show this year.")
}

fun loverMedu(w: Wrapped): String = if (loverShows(w)) t("Movies miss you a little.") else t("Shows miss you a little.")

/** What a screen reader hears for the split bar. */
fun loverSplitLabel(w: Wrapped): String {
    val pct = loverPct(w)
    val (win, lose) = if (loverShows(w)) t("Shows") to t("Movies") else t("Movies") to t("Shows")
    return t("{a} {pa}%, {b} {pb}% of your hours", "a" to win, "pa" to pct, "b" to lose, "pb" to 100 - pct)
}

// ── Genres ──

private val GenreNames: Map<String, () -> String> = mapOf(
    "thriller" to { tc("Thriller", "genre") }, "drama" to { tc("Drama", "genre") }, "comedy" to { tc("Comedy", "genre") }, "scifi" to { tc("Sci-fi", "genre") },
    "documentary" to { tc("Documentary", "genre") }, "animation" to { tc("Animation", "genre") }, "action" to { tc("Action", "genre") }, "adventure" to { tc("Adventure", "genre") },
    "horror" to { tc("Horror", "genre") }, "crime" to { tc("Crime", "genre") }, "romance" to { tc("Romance", "genre") }, "fantasy" to { tc("Fantasy", "genre") },
    "mystery" to { tc("Mystery", "genre") }, "family" to { tc("Family", "genre") }, "history" to { tc("History", "genre") }, "war" to { tc("War", "genre") },
    "western" to { tc("Western", "genre") }, "music" to { tc("Music", "genre") }, "kids" to { tc("Kids", "genre") }, "reality" to { tc("Reality", "genre") },
)

/** A known genre in the app's language ("Sci-Fi & Fantasy" → "Sci-fi"); others as the library names them. */
fun genreName(g: WrappedGenre): String = g.key?.let { GenreNames[it] }?.invoke() ?: g.name

/** "a third", "half"…: a share the way people say it. */
fun shareWords(pct: Int): String = when {
    pct in 45..55 -> t("half")
    pct > 55 -> t("most")
    pct >= 30 -> t("a third")
    pct >= 22 -> t("a quarter")
    pct >= 17 -> t("a fifth")
    else -> "${formatNumber(pct)}%"
}

/** The line under "Your year in genres", for the top genre. */
fun genreLead(w: Wrapped): String {
    val top = w.genres.firstOrNull() ?: return ""
    val share = shareWords(top.pct)
    return when (top.key) {
        "thriller" -> t("You went dark this year: {share} of your nights were thrillers.", "share" to share)
        "drama" -> t("You came for the feelings: {share} of your time went to drama.", "share" to share)
        "comedy" -> t("You kept it light: {share} of your time was comedy.", "share" to share)
        "scifi" -> t("Your head was in the stars: {share} of your time was sci-fi.", "share" to share)
        "documentary" -> t("Always learning: {share} of your time went to documentaries.", "share" to share)
        "animation" -> t("Drawn to it: {share} of your time was animation.", "share" to share)
        "action" -> t("Strap in: {share} of your time was action.", "share" to share)
        "horror" -> t("Lights off, volume up: {share} of your time was horror.", "share" to share)
        "crime" -> t("Case open: {share} of your time went to crime.", "share" to share)
        "romance" -> t("Hopeless romantic: {share} of your time was romance.", "share" to share)
        "fantasy" -> t("Dragons welcome: {share} of your time was fantasy.", "share" to share)
        "mystery" -> t("Whodunnit? {share} of your time went to mysteries.", "share" to share)
        else -> t("{genre} won your year: {share} of everything you watched.", "genre" to genreName(top), "share" to share)
    }
}

fun genreOther(w: Wrapped): String = if (w.otherPct > 0) t("The other {pct}% was a bit of everything.", "pct" to formatNumber(w.otherPct)) else ""

fun genreMedu(w: Wrapped): String = when (w.genres.firstOrNull()?.key) {
    "thriller" -> t("That many thrillers… should I be worried?")
    "drama" -> t("I cried too. Twice.")
    "comedy" -> t("You laughed. I heard.")
    "scifi" -> t("Take me with you next time.")
    "documentary" -> t("Fun fact: jellyfish have no brain. I’m doing fine.")
    "horror" -> t("I watched through my tentacles.")
    "animation" -> t("Cartoons are for everyone.")
    "crime" -> t("It wasn’t me. I was at home.")
    "romance" -> t("Aww. Pass the tissues.")
    "fantasy" -> t("Is that a dragon? Can I keep it?")
    "mystery" -> t("I knew it was the butler.")
    else -> t("Good taste, if you ask me.")
}

// ── Top shows ──

fun showMeta(s: WrappedTitle): String =
    "${tn(s.episodes, "{n} episode", "{n} episodes")} · ${t("{n} h", "n" to formatNumber(hoursOf(s.watchMs)))}"

fun showsFootnote(w: Wrapped): String {
    val f = w.seasonsFinished
    val s = w.showsStarted
    return when {
        f > 0 && s > 0 -> "${tn(f, "You finished {n} season", "You finished {n} seasons")} ${tn(s, "and started {n} new show.", "and started {n} new shows.")}"
        f > 0 -> tn(f, "You finished {n} season.", "You finished {n} seasons.")
        s > 0 -> tn(s, "You started {n} new show.", "You started {n} new shows.")
        else -> ""
    }
}

fun showsMedu(w: Wrapped): String =
    w.shows.firstOrNull()?.let { tn(it.episodes, "{n} episode of {show}. I counted.", "{n} episodes of {show}. I counted.", "show" to it.name) }.orEmpty()

// ── The movie ──

fun movieTitle(w: Wrapped): String = if ((w.movie?.views ?: 0) >= 2) t("The one you couldn’t let go") else t("Your movie of the year")

fun duration(ms: Long): String {
    val min = max(1L, round(ms / 60_000.0))
    val h = min / 60
    val m = min % 60
    return when {
        h == 0L -> t("{m} min", "m" to formatNumber(m))
        m == 0L -> t("{h} h", "h" to formatNumber(h))
        else -> t("{h} h {m} min", "h" to formatNumber(h), "m" to formatNumber(m))
    }
}

/** A yyyy-MM-dd day as the app's language writes it ([pattern]: java.time's, e.g. "d MMMM"). */
private fun day(iso: String, pattern: String): String =
    runCatching { LocalDate.parse(iso.take(10)).format(DateTimeFormatter.ofPattern(pattern, I18n.locale)) }.getOrDefault(iso.take(10))

fun movieMeta(w: Wrapped): String {
    val m = w.movie ?: return ""
    val last = m.dates.lastOrNull()
    val all = t("{time} all in", "time" to duration(m.watchMs))
    return if (last != null) "$all · ${t("last time on {date}", "date" to day(last, "d MMMM"))}" else all
}

/** One ticket per view (at most six, the latest), named by its month. */
fun movieTickets(w: Wrapped): List<String> = w.movie?.dates.orEmpty().takeLast(6).map { day(it, "LLL").replaceFirst(".", "") }

fun movieMedu(w: Wrapped): String = if ((w.movie?.views ?: 0) >= 2) t("Same, honestly.") else t("Good pick.")

// ── The binge ──

fun bingeLine(w: Wrapped): String {
    val b = w.binge ?: return ""
    val season = when {
        b.season == null -> b.name
        b.seasonTo != null -> t("{show}, seasons {from}–{to}", "show" to b.name, "from" to b.season, "to" to b.seasonTo)
        else -> t("{show}, season {n}", "show" to b.name, "n" to b.season)
    }
    return "$season · ${day(b.start, "EEE d MMMM")}"
}

fun timeOf(localIso: String): String = if (localIso.length >= 16) localIso.substring(11, 16) else ""

fun bingeEpisode(w: Wrapped): String {
    val b = w.binge ?: return ""
    return if (b.lastSeason != null && b.lastEpisode != null) "${b.name} · ${t("S{s} E{e}", "s" to b.lastSeason, "e" to b.lastEpisode)}" else b.name
}

fun bingeText(w: Wrapped): String {
    val s = w.still
    if (s.asked > 0) {
        val asked = tn(s.asked, "We asked “Still watching?” {n} time this year.", "We asked “Still watching?” {n} times this year.")
        val yes = when {
            s.yes >= s.asked -> if (s.asked == 1) t("You said yes.") else t("You said yes every single time.")
            else -> tn(s.yes, "You said yes {n} time.", "You said yes {n} times.")
        }
        return "$asked $yes"
    }
    val b = w.binge ?: return ""
    val ms = runCatching { Duration.between(LocalDateTime.parse(b.start), LocalDateTime.parse(b.end)).toMillis() }.getOrDefault(0L)
    return t("{time} from the first episode to the last.", "time" to duration(max(60_000L, ms)))
}

// ── The clock ──

/** "You’re a" and "night owl": the second part is set in the accent. */
fun clockTitle(w: Wrapped): Pair<String, String> = when (w.clock?.kind) {
    "morning" -> t("You’re an") to t("early bird")
    "afternoon" -> t("You’re an") to t("afternoon person")
    "evening" -> t("You’re a") to t("prime-time regular")
    else -> t("You’re a") to t("night owl")
}

/** "{pct} of your watching happened after 22:00." with the percentage left in, to set in bold. */
fun clockShare(w: Wrapped): String = when (w.clock?.kind) {
    "morning" -> t("{pct} of your watching happened before noon.")
    "afternoon" -> t("{pct} of your watching happened between noon and 18:00.")
    "evening" -> t("{pct} of your watching happened between 18:00 and 22:00.")
    else -> t("{pct} of your watching happened after 22:00.")
}

fun clockDay(w: Wrapped): String {
    val nights = w.clock?.kind == "night" || w.clock?.kind == "evening"
    return when (w.clock?.day) {
        1 -> if (nights) t("Mondays were the big nights.") else t("Mondays were the big days.")
        2 -> if (nights) t("Tuesdays were the big nights.") else t("Tuesdays were the big days.")
        3 -> if (nights) t("Wednesdays were the big nights.") else t("Wednesdays were the big days.")
        4 -> if (nights) t("Thursdays were the big nights.") else t("Thursdays were the big days.")
        5 -> if (nights) t("Fridays were the big nights.") else t("Fridays were the big days.")
        6 -> if (nights) t("Saturdays were the big nights.") else t("Saturdays were the big days.")
        else -> if (nights) t("Sundays were the big nights.") else t("Sundays were the big days.")
    }
}

fun clockMedu(w: Wrapped): String = when (w.clock?.kind) {
    "morning" -> t("Coffee first? No? Okay.")
    "afternoon" -> t("A matinee a day keeps the boredom away.")
    "evening" -> t("Dinner, then a show. Classic.")
    else -> t("Some of us need our sleep, you know.")
}

/** Medu's mood on the clock story, by the web's mood names: "sleepy", "popcorn" or "wave". */
fun clockMood(w: Wrapped): String = when (w.clock?.kind) {
    "night" -> "sleepy"
    "evening" -> "popcorn"
    else -> "wave"
}

// ── The personality ──

fun personalityName(key: String): String = when (key) {
    "sprinter" -> t("The Season Sprinter")
    "rewatcher" -> t("The Rewatcher")
    "cinephile" -> t("The Movie Buff")
    "explorer" -> t("The Explorer")
    "regular" -> t("The Daily Regular")
    "slowburner" -> t("The Slow Burner")
    "weekender" -> t("The Weekend Warrior")
    else -> t("The Easygoing Viewer")
}

fun personalityText(key: String): String = when (key) {
    "sprinter" -> t("Once you start a season, nothing gets in the way. Not sleep, not intros, not Medu.")
    "rewatcher" -> t("Why gamble on something new when you know what you love? Comfort, on repeat.")
    "cinephile" -> t("Lights down, phone away. For you, a night in means a proper film.")
    "explorer" -> t("A bit of everything, from everywhere. You never stayed in one place for long.")
    "regular" -> t("Rain or shine, there was something on. Seedy was part of your day.")
    "slowburner" -> t("You take your time and savour every episode. Good things are worth the wait.")
    "weekender" -> t("Weekdays are for everything else. Weekends are for the couch.")
    else -> t("No marathons, no pressure. You watch what you like, when you like.")
}

fun personalityMedu(key: String): String = when (key) {
    "sprinter" -> t("Teach me your ways.")
    "rewatcher" -> t("Again? Again!")
    "cinephile" -> t("Popcorn’s on me.")
    "explorer" -> t("Where to next?")
    "regular" -> t("Same time tomorrow?")
    "slowburner" -> t("No spoilers, I promise.")
    "weekender" -> t("See you Saturday!")
    else -> t("Quality over quantity.")
}

/** One number under the personality: the big part and what it means. */
fun statLine(s: WrappedStat): Chip {
    val v = s.value
    val n = round(v).toInt()
    return when (s.key) {
        "seasonDays" -> Chip(tn(v, "{n} day", "{n} days"), t("to finish a season, on average"))
        "introsSkipped" -> {
            val saved = s.extra ?: 0.0
            val hours = round(saved / 3_600_000).toInt()
            val small = if (hours >= 1) tn(hours, "skipped. That’s {n} hour you got back", "skipped. That’s {n} hours you got back")
            else tn(max(1, round(saved / 60_000).toInt()), "skipped. That’s {n} minute you got back", "skipped. That’s {n} minutes you got back")
            Chip(tn(n, "{n} intro", "{n} intros"), small)
        }
        "gapSeconds" -> Chip(
            if (v < 60) tn(n, "{n} second", "{n} seconds") else tn(round(v / 60).toInt(), "{n} minute", "{n} minutes"),
            t("between episodes, on average"),
        )
        "seasonsFinished" -> Chip(tn(n, "{n} season", "{n} seasons"), t("finished, start to end"))
        "movies" -> Chip(tn(n, "{n} movie", "{n} movies"), t("watched to the end"))
        "titles" -> Chip(tn(n, "{n} title", "{n} titles"), t("different movies and shows"))
        "genres" -> Chip(tn(n, "{n} genre", "{n} genres"), t("explored along the way"))
        "rewatches" -> Chip(tn(n, "{n} rewatch", "{n} rewatches"), t("of things you’d already seen"))
        "streak" -> Chip(tn(n, "{n} day", "{n} days"), t("in a row, your longest streak"))
        "activeDays" -> Chip(tn(n, "{n} day", "{n} days"), t("with something on"))
        "bigDay" -> {
            val h = round(v / 360_000) / 10.0
            Chip(tn(h, "{n} hour", "{n} hours"), t("on your biggest day"))
        }
        "weekendPct" -> Chip("${formatNumber(n)}%", t("of your watching on weekends"))
        "showsStarted" -> Chip(tn(n, "{n} new show", "{n} new shows"), t("started from episode one"))
        else -> Chip(formatNumber(n), "")
    }
}

// ── Summary ──

fun summaryAlso(w: Wrapped): String {
    val parts = mutableListOf<String>()
    val music = hoursOf(w.musicMs)
    if (music > 0) parts += t("{n} h of music", "n" to formatNumber(music))
    if (w.books > 0) parts += tn(w.books, "{n} book", "{n} books")
    return if (parts.isNotEmpty()) t("Also {list}", "list" to parts.joinToString(" · ")) else ""
}

/** What a screen reader hears for the share card. */
fun summaryLabel(w: Wrapped): String {
    val bits = mutableListOf(tn(hoursOf(w.watchMs).toInt(), "{n} hour", "{n} hours"))
    w.shows.firstOrNull()?.let { bits += t("top show {name}", "name" to it.name) }
    w.genres.firstOrNull()?.let { bits += t("top genre {name}", "name" to genreName(it)) }
    bits += personalityName(w.personality.key)
    return t("My {year} on Seedy: {list}", "year" to w.year, "list" to bits.joinToString(", "))
}

// ── Home banner ──

fun bannerText(watchMs: Long, stories: Int): String =
    t("{hours} in {stories}. Medu took notes.", "hours" to tn(hoursOf(watchMs).toInt(), "{n} hour", "{n} hours"), "stories" to tn(stories, "{n} story", "{n} stories"))
