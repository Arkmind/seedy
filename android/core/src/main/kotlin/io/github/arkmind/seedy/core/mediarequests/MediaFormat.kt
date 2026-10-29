package io.github.arkmind.seedy.core.mediarequests

import io.github.arkmind.seedy.core.format.dayMonth
import io.github.arkmind.seedy.core.format.fullDate
import io.github.arkmind.seedy.core.format.monthYear
import io.github.arkmind.seedy.core.format.parseDay
import io.github.arkmind.seedy.core.format.weekdayDate
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/*
 * The words of the music and book request pages (web: screens/requests/media-parts.tsx, music.tsx, books.tsx,
 * mine.tsx and the dialogs): request names, the lines under them, where they are on their way, card states.
 */

/** The services a person can request from, in the web's order. */
enum class RequestService { Movies, Music, Books }

/** Where a request is: 1 Asked, 2 Approved, 3 Searching, 4 Downloading, 5 Ready; [fail] when it's stuck there. */
data class MediaStage(val n: Int, val fail: Boolean = false, val label: String? = null)

/** A line in a tone: ok, pending, accent, muted, danger or light. */
data class ToneText(val text: String, val tone: String)

/** A card's state on its cover: the words and the dot's colour key (ok, pending, accent). */
data class CardState(val text: String, val dot: String)

/** My requests' "Show" choices. */
enum class MediaShow(val key: String, val label: String) {
    All("all", "Open and finished"),
    Open("open", "Open only"),
    Pending("pending", "Waiting for approval"),
    Attention("attention", "Needs a look"),
    Done("done", "Finished"),
}

object MediaFormat {
    val ServiceNames = mapOf("lidarr" to "Lidarr", "shelfarr" to "Shelfarr", "librarr" to "Librarr", "readarr" to "Readarr")

    fun serviceName(service: String): String = ServiceNames[service] ?: service

    /** The web's string hash ((h * 31 + c) | 0, made positive): the request pages' drawn covers pick their art from it. */
    fun hash(s: String): Long = kotlin.math.abs(s.hashCode().toLong())

    /** "1", "2.5". */
    fun num(n: Double?): String = when {
        n == null -> ""
        n % 1.0 == 0.0 -> n.toLong().toString()
        else -> String.format(Locale.US, "%.1f", n)
    }

    /** A request's day, in the device's time zone. */
    fun day(iso: String?): LocalDate? {
        val s = iso?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (s.length > 10) runCatching { return OffsetDateTime.parse(s).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate() }
        if (s.length == 7) return runCatching { YearMonth.parse(s).atDay(1) }.getOrNull()
        return parseDay(s)
    }

    // ── Requests ──

    /** "Mira Sol · whole discography", "Blue Hour (Deluxe) · Lumen Fields", "Lantern Season · audiobook". */
    fun name(r: MediaRequest): String {
        if (r.music) {
            if (r.mode == "artist") return t("{artist} · whole discography", "artist" to r.title)
            if (r.mode == "future") return t("{artist} · new releases", "artist" to r.title)
            if (r.albums.size == 1) return "${r.albums[0].title} · ${r.title}"
            return tn(r.albums.size, "{artist} · {n} album", "{artist} · {n} albums", "artist" to r.title)
        }
        if (r.formats.size == 1 && r.formats[0] == "audiobook") return t("{title} · audiobook", "title" to r.title)
        return r.title
    }

    fun formatsLabel(formats: List<String>, content: String? = null): String = when {
        content == "comic" || "comic" in formats -> t("Comic")
        "ebook" in formats && "audiobook" in formats -> t("Ebook and audiobook")
        formats.firstOrNull() == "audiobook" -> t("Audiobook")
        else -> t("Ebook")
    }

    /** "Artist · 5 albums", "Artist, future albums only", "Album", "Ebook and audiobook". */
    fun kindLine(r: MediaRequest): String = when {
        !r.music -> formatsLabel(r.formats, r.content)
        r.mode == "artist" -> t("Artist") + " · " + tn(r.units, "{n} album", "{n} albums")
        r.mode == "future" -> t("Artist, future albums only")
        r.albums.size == 1 -> t("Album")
        else -> tn(r.albums.size, "{n} album", "{n} albums")
    }

    /** "asked today", "approved yesterday", "asked by Mia Sat 17 Oct". */
    fun actLine(act: String, iso: String?, today: LocalDate, name: String = ""): String {
        val d = day(iso) ?: return ""
        val ago = ChronoUnit.DAYS.between(d, today)
        val date = if (d.year == today.year) weekdayDate(d) else fullDate(d)
        val forms = when (act) {
            "approved" -> listOf("approved today", "approved yesterday", "approved {date}")
            "askedBy" -> listOf("asked by {name} today", "asked by {name} yesterday", "asked by {name} {date}")
            else -> listOf("asked today", "asked yesterday", "asked {date}")
        }
        return t(forms[if (ago <= 0) 0 else if (ago == 1L) 1 else 2], "date" to date, "name" to name)
    }

    private fun who(r: MediaRequest) = if (r.requestedByMe) t("you") else r.requestedBy.ifEmpty { t("someone") }

    /** The line under a request on My requests: what, and when (and by whom on All requests). */
    fun whatLine(r: MediaRequest, everyone: Boolean, today: LocalDate): String =
        listOf(kindLine(r), if (everyone) actLine("askedBy", r.requestedAt, today, who(r)) else actLine("asked", r.requestedAt, today))
            .filter { it.isNotBlank() }.joinToString(" · ")

    private fun pct(f: Double?): Int = Math.round((f ?: 0.0).coerceIn(0.0, 1.0) * 100).toInt()

    private fun qualityWord(q: String?): String = when {
        q.isNullOrEmpty() -> t("good")
        q == "lossless" -> t("lossless")
        else -> q
    }

    /** The extra line on My requests, in the tone it's shown in. */
    fun note(r: MediaRequest): ToneText? {
        val n = r.note
        if (r.status == "pending") return ToneText(t("Waiting for an admin"), "pending")
        if (r.status == "declined") return ToneText(t("Declined by an admin"), "danger")
        if (n == null) return if (r.status == "downloading") ToneText(t("Downloading · {p}%", "p" to pct(r.progress)), "accent") else null
        val service = n.name.orEmpty()
        return when (n.code) {
            "albums" -> if (r.status != "downloading") {
                ToneText(t("{n} of {total} albums imported", "n" to (n.n ?: 0), "total" to (n.total ?: 0)), "light")
            } else {
                ToneText(
                    if (n.name != null) t("{n} of {total} albums imported · {album} at {p}%", "n" to (n.n ?: 0), "total" to (n.total ?: 0), "album" to n.name, "p" to pct(n.fraction))
                    else t("{n} of {total} albums imported", "n" to (n.n ?: 0), "total" to (n.total ?: 0)),
                    "accent",
                )
            }
            "noCopy" -> ToneText(
                tn(n.days ?: 0, "No {quality} copy after {n} day · Lidarr retries every 6 hours", "No {quality} copy after {n} days · Lidarr retries every 6 hours", "quality" to qualityWord(n.name)),
                "danger",
            )
            "found" -> ToneText(tn(n.n ?: 0, "{service} found {n} release · picking the best", "{service} found {n} releases · picking the best", "service" to service), "light")
            "notFound" -> ToneText(t("{service} found nothing yet · it keeps looking", "service" to service), "danger")
            "purchase" -> ToneText(t("{service} only found it for sale", "service" to service), "danger")
            "service" -> ToneText(n.text.orEmpty(), if (r.attention) "danger" else "light")
            "unreachable" -> ToneText(t("Couldn’t reach {service} · Seedy tries again every minute", "service" to service), "danger")
            "future" -> ToneText(t("Lidarr grabs new albums when they come out"), "light")
            "scanning" -> ToneText(t("Adding it to the library"), "accent")
            "gone" -> ToneText(t("{service} no longer has this request", "service" to service), "danger")
            else -> null
        }
    }

    fun stage(r: MediaRequest): MediaStage = when (r.status) {
        "pending" -> MediaStage(1)
        "declined" -> MediaStage(2, fail = true, label = t("Declined"))
        "approved" -> MediaStage(2, fail = r.attention)
        "searching" -> MediaStage(3, fail = r.attention)
        "downloading" -> MediaStage(4, fail = r.attention)
        "failed" -> MediaStage(3, fail = true)
        else -> MediaStage(5)
    }

    val Stages = listOf("Asked", "Approved", "Searching", "Downloading", "Ready")

    fun matches(r: MediaRequest, show: MediaShow): Boolean {
        val st = stage(r)
        return when (show) {
            MediaShow.Open -> st.n < 5 && !(st.fail && st.label != null)
            MediaShow.Pending -> r.status == "pending"
            MediaShow.Attention -> st.fail
            MediaShow.Done -> st.n == 5
            MediaShow.All -> true
        }
    }

    /** Where a finished request opens: the reader for ebooks and comics ("Read"), the title's page otherwise ("Play"). */
    fun readyLabel(r: MediaRequest): String? {
        if (r.status != "ready" || r.itemId.isNullOrEmpty()) return null
        return if (!r.music && ("ebook" in r.formats || "comic" in r.formats)) t("Read") else t("Play")
    }

    fun opensReader(r: MediaRequest): Boolean = !r.music && ("ebook" in r.formats || "comic" in r.formats)

    // ── Cards ──

    /** "On the server", "Requested", "Waiting", "Downloading" on covers. */
    fun cardStatus(status: String, requestedBy: String?, short: Boolean = false): CardState? = when (status) {
        "server" -> CardState(t("On the server"), "ok")
        "waiting" -> CardState(
            when {
                short -> t("Waiting")
                requestedBy != null -> t("Requested by {name}", "name" to requestedBy)
                else -> t("Waiting for approval")
            },
            "pending",
        )
        "downloading" -> CardState(t("Downloading"), "accent")
        "requested" -> CardState(if (requestedBy != null && !short) t("Requested by {name}", "name" to requestedBy) else t("Requested"), "pending")
        else -> null
    }

    /** "Fri 16 Oct" within a month either way, "4 Dec" this year, "Jan 2027", else the year; soon when it's out within two weeks. */
    private fun releaseWhen(date: String?, today: LocalDate): Pair<String, Boolean> {
        val d = if (date != null && date.length == 4) null else day(date)
        if (d == null) return (date?.take(4) ?: "") to false
        val days = ChronoUnit.DAYS.between(today, d)
        return when {
            kotlin.math.abs(days) <= 31 -> weekdayDate(d) to (days in 0..14)
            days > 0 && d.year == today.year -> dayMonth(d) to false
            days > 0 && date!!.length >= 7 -> monthYear(d) to false
            else -> d.year.toString() to false
        }
    }

    /** The line under an album: "Ada Vale · Fri 16 Oct", "Ada Vale · you have 7 of 8"; lit when it's out soon. */
    fun releaseMeta(r: MusicRelease, row: MusicRow?, today: LocalDate): Pair<String, Boolean> {
        val artist = r.artist.orEmpty()
        if (row == MusicRow.Missing && r.of != null) return t("{artist} · you have {n} of {total}", "artist" to artist, "n" to (r.have ?: 0), "total" to r.of) to false
        if (row == MusicRow.Trending) return listOf(artist, r.date?.take(4).orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ") to false
        val (text, soon) = releaseWhen(r.date, today)
        return listOf(artist, text).filter { it.isNotEmpty() }.joinToString(" · ") to (r.upcoming && soon)
    }

    /** "2026 · 11 songs · 44 min", or "Out 6 Nov 2026" for what isn't out yet. */
    fun releaseSub(r: MusicRelease): String {
        if (r.upcoming) {
            val d = day(r.date)
            return if (d != null && (r.date?.length ?: 0) >= 10) t("Out {date}", "date" to fullDate(d)) else t("Announced")
        }
        val parts = mutableListOf(r.date?.take(4).orEmpty())
        if ("live" in r.secondary) parts += t("Live") else if ("soundtrack" in r.secondary) parts += t("Soundtrack")
        r.tracks?.takeIf { it > 0 }?.let { parts += tn(it, "{n} song", "{n} songs") }
        r.lengthMs?.takeIf { it > 0 }?.let { parts += t("{n} min", "n" to Math.round(it / 60000.0)) }
        return parts.filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** The album's badge: Album, EP, Single, or Soon for what isn't out yet; and its colour key. */
    fun releaseType(r: MusicRelease): Pair<String, String> = when {
        r.upcoming -> t("Soon") to "soon"
        r.type == "ep" -> t("EP") to "ep"
        r.type == "single" -> t("Single") to "single"
        else -> t("Album") to "album"
    }

    /** "Ebook + audio", "Audiobook", "Comic", "Ebook", or Soon; and the badge's colour key. */
    fun bookType(b: BookCard): Pair<String, String> = when {
        b.upcoming -> t("Soon") to "soon"
        b.content == "comic" -> t("Comic") to "comic"
        "ebook" in b.formats && "audiobook" in b.formats -> t("Ebook + audio") to "both"
        "audiobook" in b.formats -> t("Audiobook") to "audio"
        else -> t("Ebook") to "ebook"
    }

    private fun bookWhen(b: BookCard, today: LocalDate): String {
        val d = day(b.releaseDate) ?: return b.year?.toString().orEmpty()
        val days = ChronoUnit.DAYS.between(today, d)
        return when {
            days in 1..120 -> dayMonth(d) + if (d.year != today.year) " ${d.year}" else ""
            days > 0 -> monthYear(d)
            else -> d.year.toString()
        }
    }

    /** The line under a book: its series ("Halden 5", "Kestrel · Vol. 6"), its date when it's coming, rating… */
    fun bookMeta(b: BookCard, row: BookRow?, today: LocalDate): Pair<String, Boolean> {
        val series = b.series?.let {
            if (b.content == "comic") t("{series} · Vol. {n}", "series" to it, "n" to num(b.seriesIndex)) else "$it ${num(b.seriesIndex)}".trim()
        }.orEmpty()
        return when (row) {
            BookRow.Next -> if (b.upcoming) listOf(series, bookWhen(b, today)).filter { it.isNotEmpty() }.joinToString(" · ") to true
            else (series.ifEmpty { b.author.orEmpty() }) to false
            BookRow.Trending -> listOfNotNull(b.year?.toString(), b.rating?.takeIf { it > 0 }?.let { "★ " + String.format(Locale.US, "%.1f", it) }).joinToString(" · ") to false
            BookRow.Audiobooks -> b.author.orEmpty() to false
            null -> listOfNotNull(b.author, b.year?.toString()).joinToString(" · ") to false
        }
    }

    /** "Shelfarr · indexers first": what the app does with this format. */
    fun routeLine(format: String, service: String): String {
        val name = serviceName(service)
        return when (service) {
            "shelfarr" -> if (format == "audiobook") t("{app} · M4B or MP3 folders", "app" to name) else t("{app} · indexers first", "app" to name)
            "librarr" -> if (format == "comic") t("{app} · CBZ or CBR", "app" to name) else t("{app} · direct sources", "app" to name)
            else -> name
        }
    }
}
