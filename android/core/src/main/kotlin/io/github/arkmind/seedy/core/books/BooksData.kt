package io.github.arkmind.seedy.core.books

import io.github.arkmind.seedy.core.audio.AudioPart
import io.github.arkmind.seedy.core.audio.Reading
import io.github.arkmind.seedy.core.audio.authorLine
import io.github.arkmind.seedy.core.audio.authorsOf
import io.github.arkmind.seedy.core.audio.mergePlace
import io.github.arkmind.seedy.core.format.duration
import io.github.arkmind.seedy.core.format.ticksToSeconds
import io.github.arkmind.seedy.core.i18n.t
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * What the books screens make of a books library (web/src/screens/books/api.ts, BooksLibrary.tsx and parts.tsx): one
 * card per work, series with their volumes, authors with their books, and the sorts the tabs offer.
 */

fun isAudioBook(item: BaseItemDto): Boolean = item.type == BaseItemKind.AUDIO_BOOK

/** "the glassorchard" → "glassorchard": titles and names compared without articles, case or punctuation. */
fun normalize(s: String?): String =
    s.orEmpty().lowercase().replace(Leading, "").replace(NonWord, "")

private val Leading = Regex("^(the|a|an)\\s+")
private val NonWord = Regex("[^\\p{L}\\p{N}]+")

/**
 * The series a book is part of. Jellyfin also names a "series" after the folder a book sits in (the author's), with no
 * number: that isn't one.
 */
fun seriesOf(item: BaseItemDto): String? {
    val name = item.seriesName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (item.indexNumber == null) return null
    if (authorsOf(item).any { normalize(it.first) == normalize(name) }) return null
    return name
}

/** One work whatever its format: the EPUB, the PDF and the audiobook of a book share it. */
fun workKey(item: BaseItemDto): String = "${normalize(item.name)}|${normalize(authorLine(item))}"

fun yearOfBook(item: BaseItemDto): Int? = item.productionYear ?: item.premiereDate?.year

/** "about 3 h", "1 h 30 min", "25 min": rough reading time for cards. */
fun roughTime(minutes: Double): String {
    val m = max(1, minutes.roundToInt())
    if (m < 60) return "${max(5, (m / 5.0).roundToInt() * 5)} min"
    if (m < 120) {
        val r = (m / 10.0).roundToInt() * 10
        return if (r % 60 != 0) "${r / 60} h ${(r % 60).toString().padStart(2, '0')} min" else "${r / 60} h"
    }
    return "${(m / 60.0).roundToInt()} h"
}

/** Under a cover in the grids: "Lantern Season — The Halden Cycle, 4", else "Author · 2026". */
fun bookSub(item: BaseItemDto): String {
    seriesOf(item)?.let { return "$it, ${item.indexNumber}" }
    val year = yearOfBook(item)?.toString() ?: item.genres?.firstOrNull()?.lowercase()
    return listOfNotNull(authorLine(item).takeIf { it.isNotEmpty() }, year).joinToString(" · ")
}

/** Comics: "Kestrel · new", "Kestrel · reading" in a running series, else "Author · 2025" (or "One-shot"). */
fun comicSub(item: BaseItemDto, reading: Reading?, running: Boolean): String {
    val state = when {
        item.userData?.played == true || reading?.finished == true -> t("read")
        reading != null -> t("reading")
        else -> t("new")
    }
    val series = seriesOf(item)
    if (series != null && running) return "$series · $state"
    return listOfNotNull(authorLine(item).takeIf { it.isNotEmpty() }, yearOfBook(item)?.toString()).joinToString(" · ").ifEmpty { t("One-shot") }
}

/** An audiobook's length: "14 h 20 min". */
fun audioLength(item: BaseItemDto): String = item.runTimeTicks?.takeIf { it > 0 }?.let { duration(ticksToSeconds(it)) }.orEmpty()

/** Jellyfin's dates are UTC. */
fun millisOf(d: LocalDateTime?): Long = d?.toInstant(ZoneOffset.UTC)?.toEpochMilli() ?: 0L

fun millisOf(iso: String?): Long = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

/** Seedy's ids come without dashes ("54429509d9e4…"); Jellyfin's UUIDs with them. */
fun uuidOf(s: String?): UUID? {
    val v = s?.trim().orEmpty()
    if (v.length == 32) return runCatching { UUID.fromString("${v.substring(0, 8)}-${v.substring(8, 12)}-${v.substring(12, 16)}-${v.substring(16, 20)}-${v.substring(20)}") }.getOrNull()
    return runCatching { UUID.fromString(v) }.getOrNull()
}

/** A series with at least two volumes here: its name, who wrote it and how far this profile is. Times in epoch millis. */
data class SeriesGroup(
    val name: String,
    val key: String,
    val author: String,
    val volumes: List<BaseItemDto>,
    val read: Int,
    val latest: Long,
    val readAt: Long,
)

/** Everything in a books library, books and audiobooks, with this profile's places in them. */
data class BooksData(
    val all: List<BaseItemDto>,
    /** One card per work (the EPUB over the PDF of a book). */
    val books: List<BaseItemDto>,
    val comics: List<BaseItemDto>,
    val audio: List<BaseItemDto>,
    val reading: Map<UUID, Reading>,
    val series: List<SeriesGroup>,
    /** The files of each audiobook in several files, by the book's id. */
    val parts: Map<UUID, List<AudioPart>> = emptyMap(),
) {
    /** Whether a book belongs to a series with more than one volume here. */
    fun running(item: BaseItemDto): Boolean = seriesOf(item)?.let { s -> series.any { it.key == normalize(s) } } == true

    fun seriesNamed(name: String?): SeriesGroup? = name?.let { n -> series.firstOrNull { it.key == normalize(n) } }
}

/** Formats in the order a card shows a work: the reader's first. */
private val Preferred = listOf(BookFormat.Epub, BookFormat.Cbz, BookFormat.Pdf, BookFormat.Cbr, BookFormat.Other)

private fun preferred(item: BaseItemDto) = Preferred.indexOf(formatOf(item)).let { if (it < 0) Preferred.size else it }

private fun sortKey(item: BaseItemDto) = (item.sortName ?: item.name).orEmpty().lowercase()

/**
 * The library's items and places as the screens use them. Audiobooks in one file take Seedy's place in them (books in
 * several files have it from the server).
 */
fun booksData(items: List<BaseItemDto>, readings: List<Reading>, parts: Map<UUID, List<AudioPart>> = emptyMap()): BooksData {
    val map = readings.mapNotNull { r -> uuidOf(r.itemId)?.let { it to r } }.toMap()
    val all = items.map { if (isAudioBook(it) && it.id !in parts) mergePlace(it, map[it.id]) else it }
    return BooksData(
        all = all,
        books = oneEach(all.filter { !isAudioBook(it) && !isComic(it) }),
        comics = oneEach(all.filter { !isAudioBook(it) && isComic(it) }),
        audio = all.filter(::isAudioBook),
        reading = map,
        series = groupSeries(all),
        parts = parts,
    )
}

/** One card per work: the EPUB and the PDF of a book show as the book (its page lists both), the reader's format first. */
fun oneEach(items: List<BaseItemDto>): List<BaseItemDto> {
    val best = LinkedHashMap<String, BaseItemDto>()
    for (it in items) {
        val key = workKey(it) + "|" + (it.indexNumber ?: "")
        val cur = best[key]
        if (cur == null || preferred(it) < preferred(cur)) best[key] = it
    }
    val keep = best.values.map { it.id }.toSet()
    return items.filter { it.id in keep }
}

/** Series with at least two volumes here. */
fun groupSeries(items: List<BaseItemDto>): List<SeriesGroup> {
    val map = LinkedHashMap<String, MutableList<BaseItemDto>>()
    for (it in items) {
        val name = seriesOf(it) ?: continue
        if (isAudioBook(it)) continue
        map.getOrPut(normalize(name)) { mutableListOf() }.add(it)
    }
    return map.map { (key, vols) ->
        // One cover per volume: the EPUB and the PDF of one book count once.
        val seen = HashSet<String>()
        val volumes = vols.sortedWith(compareBy<BaseItemDto>({ it.indexNumber ?: 9999 }, { sortKey(it) }, { preferred(it) }))
            .filter { seen.add("${it.indexNumber ?: ""}|${normalize(it.name)}") }
        val authors = volumes.map(::authorLine).filter { it.isNotEmpty() }.distinct()
        SeriesGroup(
            name = seriesOf(volumes.first())!!,
            key = key,
            author = when {
                authors.size == 1 -> authors.first()
                authors.size > 1 -> t("Various authors")
                else -> ""
            },
            volumes = volumes,
            read = volumes.count { it.userData?.played == true },
            readAt = volumes.maxOf { millisOf(it.userData?.lastPlayedDate) }.coerceAtLeast(0),
            latest = volumes.maxOf { millisOf(it.dateCreated) },
        )
    }.filter { it.volumes.size > 1 }
}

/** The sorts the tabs offer (web SORTS). */
enum class BookSort(val label: String) {
    Added("Recently added"), Title("Title"), Author("Author"), Released("Release date"), Read("Recently read"),
}

private fun released(it: BaseItemDto): Long = it.premiereDate?.let(::millisOf)
    ?: it.productionYear?.let { y -> LocalDateTime.of(y, 1, 1, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli() } ?: 0L

private fun lastRead(it: BaseItemDto, reading: Map<UUID, Reading>): Long =
    max(millisOf(reading[it.id]?.updatedAt), millisOf(it.userData?.lastPlayedDate))

/** "Mara Linde" sorts under L. */
private fun surname(name: String): String {
    val parts = name.trim().split(Regex("\\s+"))
    return (if (parts.size > 1) parts.last() + " " + parts.dropLast(1).joinToString(" ") else name).lowercase()
}

private fun authorSort(it: BaseItemDto): String = authorsOf(it).firstOrNull()?.first?.let(::surname)?.ifEmpty { null } ?: "￿"

fun sortItems(items: List<BaseItemDto>, sort: BookSort, reading: Map<UUID, Reading>): List<BaseItemDto> {
    val byTitle = compareBy<BaseItemDto> { sortKey(it) }
    return when (sort) {
        BookSort.Title -> items.sortedWith(byTitle)
        BookSort.Author -> items.sortedWith(
            compareBy<BaseItemDto>({ authorSort(it) }, { it.seriesName.orEmpty() }, { it.indexNumber ?: 0 }).then(byTitle),
        )
        BookSort.Released -> items.sortedWith(compareByDescending<BaseItemDto> { released(it) }.then(byTitle))
        BookSort.Read -> items.sortedWith(compareByDescending<BaseItemDto> { lastRead(it, reading) }.then(byTitle))
        BookSort.Added -> items.sortedWith(compareByDescending<BaseItemDto> { millisOf(it.dateCreated) }.then(byTitle))
    }
}

/** The series tab's order. */
fun sortSeries(groups: List<SeriesGroup>, sort: BookSort): List<SeriesGroup> = when (sort) {
    BookSort.Title -> groups.sortedBy { it.name.lowercase() }
    BookSort.Author -> groups.sortedWith(compareBy<SeriesGroup>({ it.author.lowercase() }, { it.name.lowercase() }))
    BookSort.Read -> groups.sortedWith(compareByDescending<SeriesGroup> { it.readAt }.thenByDescending { it.latest })
    else -> groups.sortedByDescending { it.latest }
}

/** The overview's "Series you're in": series being read first, then the newest. */
fun seriesInProgress(data: BooksData): List<SeriesGroup> =
    data.series.sortedWith(compareByDescending<SeriesGroup> { it.readAt }.thenByDescending { it.latest })

/**
 * Three covers of a series fanned out: the first, the one being read and the next, else the first three.
 */
fun seriesFan(volumes: List<BaseItemDto>): List<BaseItemDto> {
    if (volumes.size <= 3) return volumes
    val current = volumes.indexOfFirst { it.userData?.played != true }
    val pick = mutableListOf(0)
    if (current > 0) pick += current
    if (current >= 0 && current + 1 < volumes.size) pick += current + 1
    var i = 1
    while (pick.size < 3 && i < volumes.size) {
        if (i !in pick) pick += i
        i++
    }
    return pick.sorted().take(3).map { volumes[it] }
}

/** An author's card: their books and audiobooks here. */
data class AuthorGroup(
    val key: String,
    val name: String,
    val books: List<BaseItemDto>,
    val audio: List<BaseItemDto>,
    val added: Long,
    val readAt: Long,
    val released: Long,
)

fun authorGroups(data: BooksData, sort: BookSort): List<AuthorGroup> {
    val map = LinkedHashMap<String, AuthorGroup>()
    // One card per work for books (the EPUB and PDF of a title count once).
    val shown = (data.books + data.comics).map { it.id }.toSet()
    for (it in data.all) {
        if (!isAudioBook(it) && it.id !in shown) continue
        val authors = authorsOf(it).map { a -> a.first }.ifEmpty { listOf("") }
        for (a in authors) {
            val key = normalize(a).ifEmpty { "~" }
            val g = map[key] ?: AuthorGroup(key, a, emptyList(), emptyList(), 0, 0, 0)
            map[key] = g.copy(
                books = if (isAudioBook(it)) g.books else g.books + it,
                audio = if (isAudioBook(it)) g.audio + it else g.audio,
                added = max(g.added, millisOf(it.dateCreated)),
                readAt = max(g.readAt, lastRead(it, data.reading)),
                released = max(g.released, released(it)),
            )
        }
    }
    val byName = Comparator<AuthorGroup> { a, b ->
        when {
            a.name.isEmpty() && b.name.isEmpty() -> 0
            a.name.isEmpty() -> 1
            b.name.isEmpty() -> -1
            else -> surname(a.name).compareTo(surname(b.name))
        }
    }
    val list = map.values.toList()
    return when (sort) {
        BookSort.Added -> list.sortedWith(compareByDescending<AuthorGroup> { it.added }.then(byName))
        BookSort.Read -> list.sortedWith(compareByDescending<AuthorGroup> { it.readAt }.then(byName))
        BookSort.Released -> list.sortedWith(compareByDescending<AuthorGroup> { it.released }.then(byName))
        else -> list.sortedWith(byName)
    }
}

/** "3 books · 1 audiobook". */
fun authorCounts(g: AuthorGroup): String = listOfNotNull(
    g.books.size.takeIf { it > 0 }?.let { io.github.arkmind.seedy.core.i18n.tn(it, "{n} book", "{n} books") },
    g.audio.size.takeIf { it > 0 }?.let { io.github.arkmind.seedy.core.i18n.tn(it, "{n} audiobook", "{n} audiobooks") },
).joinToString(" · ")

/** A volume of a series: "Book 2 · read", "Book 3 · 46%", "Book 4 · new" (new: added in the last 30 days). */
data class VolumeLabel(val text: String, val read: Boolean, val inProgress: Boolean)

fun volumeLabel(item: BaseItemDto, reading: Reading?, now: Long): VolumeLabel {
    val n = item.indexNumber
    val read = item.userData?.played == true || reading?.finished == true
    val inProgress = !read && reading != null && reading.percent > 0
    val fresh = !read && reading == null && item.dateCreated != null && now - millisOf(item.dateCreated) < 30L * 86_400_000L
    val label = n?.let { if (isComic(item)) t("No. {n}", "n" to it) else t("Book {n}", "n" to it) }
    val state = when {
        read -> t("read")
        inProgress -> "${reading!!.percent.roundToInt()}%"
        fresh -> t("new")
        else -> null
    }
    return VolumeLabel(listOfNotNull(label, state).joinToString(" · "), read, inProgress)
}
