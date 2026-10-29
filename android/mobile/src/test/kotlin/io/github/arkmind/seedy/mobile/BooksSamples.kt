package io.github.arkmind.seedy.mobile

import io.github.arkmind.seedy.core.audio.Reading
import io.github.arkmind.seedy.core.books.BookInfo
import io.github.arkmind.seedy.core.books.BookViewModel
import io.github.arkmind.seedy.core.books.BooksView
import io.github.arkmind.seedy.core.books.BooksViewModel
import io.github.arkmind.seedy.core.books.audioEntry
import io.github.arkmind.seedy.core.books.booksData
import io.github.arkmind.seedy.core.books.readingEntry
import io.github.arkmind.seedy.player.audio.AudioMode
import io.github.arkmind.seedy.player.audio.AudioState
import io.github.arkmind.seedy.player.audio.AudioTime
import io.github.arkmind.seedy.player.audio.BookChapter
import io.github.arkmind.seedy.player.audio.Entry
import io.github.arkmind.seedy.player.audio.Sleep
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.BaseItemPerson
import org.jellyfin.sdk.model.api.ChapterInfo
import org.jellyfin.sdk.model.api.PersonKind
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/** The books boards' sample data ("Android · Books", "· Book details", "· Audiobook"). */
object BooksSamples {
    private const val SEC = 10_000_000L

    /** The boards' "today": 29 October 2026, noon. */
    val nowDate: LocalDateTime = LocalDateTime.of(2026, 10, 29, 12, 0)
    val now: Long = nowDate.toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun id(name: String): UUID = UUID.nameUUIDFromBytes(name.toByteArray())

    /** An id whose drawn cover is the boards' blue (palette 7), for the audiobook playing. */
    val fogBookId: UUID = UUID.fromString("5eed0000-0000-4000-8000-000000000005")

    private fun data(id: UUID, played: Boolean = false, position: Long = 0, lastPlayed: LocalDateTime? = null) = UserItemDataDto(
        playbackPositionTicks = position, playCount = 0, isFavorite = false, played = played, key = id.toString(), itemId = id, lastPlayedDate = lastPlayed,
    )

    private fun person(name: String, kind: PersonKind) = BaseItemPerson(name = name, id = id("person $name"), type = kind)

    fun book(
        name: String,
        authors: List<String>,
        ext: String = "epub",
        series: String? = null,
        n: Int? = null,
        played: Boolean = false,
        added: LocalDateTime = nowDate.minusDays(120),
        year: Int? = null,
        lastPlayed: LocalDateTime? = null,
        key: String = "$name.$ext",
        overview: String? = null,
    ): BaseItemDto {
        val id = id(key)
        return BaseItemDto(
            id = id, type = BaseItemKind.BOOK, name = name, path = "/media/Books/$name.$ext", seriesName = series, indexNumber = n,
            people = authors.map { person(it, PersonKind.AUTHOR) }, userData = data(id, played, lastPlayed = lastPlayed), dateCreated = added,
            productionYear = year, overview = overview,
        )
    }

    fun audiobook(
        id: UUID,
        name: String,
        author: String,
        narrator: String,
        seconds: Long,
        position: Long = 0,
        chapters: List<ChapterInfo>? = null,
        lastPlayed: LocalDateTime? = null,
    ) = BaseItemDto(
        id = id, type = BaseItemKind.AUDIO_BOOK, name = name, path = "/media/Audiobooks/$author/$name/$name.m4b", runTimeTicks = seconds * SEC,
        people = listOf(person(author, PersonKind.AUTHOR), person(narrator, PersonKind.NARRATOR)), albumArtist = author,
        userData = data(id, position = position * SEC, lastPlayed = lastPlayed), chapters = chapters, dateCreated = nowDate.minusDays(60),
    )

    /* ───────── A Field Guide to Fog: 10 h, 18 chapters, 5 h 48 min in (chapter 7, "The Lighthouse", 18:02 of 30:42) ───────── */

    private val fogStarts: List<Double> = buildList {
        for (i in 0 until 6) add(i * 19798.0 / 6)
        add(19798.0)
        for (i in 0 until 11) add(21640.0 + i * (36000.0 - 21640.0) / 11)
    }
    private val fogNames = listOf(
        "Morning Haar", "The Inlet", "Salt Roads", "A Grey Map", "Foghorns", "The Keeper", "The Lighthouse", "Low Cloud", "Drift", "The Long Shore",
        "Weather Glass", "Night Ferry", "Sea Smoke", "Clearing", "Lantern Hours", "The Bar", "Landfall", "Afterword",
    )
    val fogPosition = 20880.0
    val fogTotal = 36000.0

    val fog = audiobook(
        fogBookId, "A Field Guide to Fog", "Ines Northey", "Sam Okafor", fogTotal.toLong(), fogPosition.toLong(),
        chapters = fogStarts.mapIndexed { i, s -> ChapterInfo(startPositionTicks = (s * SEC).toLong(), name = fogNames[i], imageDateModified = nowDate) },
        lastPlayed = nowDate.minusMinutes(5),
    )

    val fogBook = io.github.arkmind.seedy.player.audio.BookInfo(
        id = fogBookId.toString(), name = "A Field Guide to Fog", author = "Ines Northey", narrator = "Sam Okafor", duration = fogTotal,
        chapters = fogStarts.mapIndexed { i, s -> BookChapter(i, fogNames[i], s, fogStarts.getOrNull(i + 1) ?: fogTotal) },
    )

    /** The audiobook playing: chapter 7, 1.2×, sleeping at the end of the chapter. */
    val playing = AudioState(
        mode = AudioMode.Audiobook, current = Entry("fog", fog), context = listOf(Entry("fog", fog)), playing = true, speed = 1.2f,
        sleep = Sleep.Chapter(6), book = fogBook,
    )
    val playingTime = AudioTime(fogPosition, fogTotal)

    /* ───────── Board "Android · Books" ───────── */

    private val linde = listOf("Mara Linde")
    private val haleIto = listOf("Hale", "Ito")

    private val glassOrchard = book("The Glass Orchard", linde, series = "The Halden Cycle", n = 3, played = true, year = 2025, lastPlayed = nowDate.minusDays(1))
    private val halden = listOf(
        book("Halden", linde, series = "The Halden Cycle", n = 1, played = true, lastPlayed = nowDate.minusDays(40)),
        book("The Long Frost", linde, series = "The Halden Cycle", n = 2, played = true, lastPlayed = nowDate.minusDays(20)),
        glassOrchard,
        book("Lantern Season", linde, series = "The Halden Cycle", n = 4, added = nowDate.minusDays(2)),
        book("The Last Thaw", linde, series = "The Halden Cycle", n = 5),
    )
    private val kestrel = (1..12).map { n ->
        book("Kestrel No. $n", haleIto, ext = "cbz", series = "Kestrel", n = n, played = n <= 4, lastPlayed = if (n <= 4) nowDate.minusDays(30L - n) else null)
    }
    private val recent = listOf(
        book("Salt & Signal", listOf("Jonah Reyes"), added = nowDate.minusDays(1)),
        book("The Quiet Engine", listOf("R. K. Amsel"), added = nowDate.minusDays(3)),
        book("Small Harbours", listOf("Eli Brandt"), added = nowDate.minusDays(4)),
        book("Copper Line", listOf("Tess Varga"), added = nowDate.minusDays(5)),
        book("North of Nowhere", listOf("Dana Holm"), added = nowDate.minusDays(6)),
    )

    private val libraryItems = recent + halden + kestrel + fog

    private val readings = listOf(
        Reading(itemId = glassOrchard.id.toString().replace("-", ""), locator = "epubcfi(/6/24!/4)", percent = 46.0, chapter = "Frost", chapterIndex = 12, chapterCount = 28, updatedAt = "2026-10-28T20:00:00Z"),
        Reading(itemId = kestrel[3].id.toString().replace("-", ""), locator = "11", percent = 37.0, updatedAt = "2026-10-27T20:00:00Z"),
    )

    private val libraryData = booksData(libraryItems, readings)

    val books = BooksViewModel.State(
        name = "Books", data = libraryData, loading = false, view = BooksView.All, playing = fogBookId,
        cont = listOf(
            readingEntry(glassOrchard, readings[0], BookInfo(format = "epub", pages = 412, minutes = 315)),
            audioEntry(fog),
            readingEntry(kestrel[3], readings[1], BookInfo(format = "cbz", pages = 32)),
        ),
    )

    /* ───────── Board "Android · Book details" ───────── */

    private const val GLASS_OVERVIEW =
        "Ilse wakes to find the orchard silvered and a tree standing where none should be. The third Halden book follows her through one long winter of counting, mending and listening."

    private val orchardEpub = book("The Glass Orchard", linde, series = "The Halden Cycle", n = 3, year = 2025, overview = GLASS_OVERVIEW, added = nowDate.minusDays(90))
    private val orchardPdf = book("The Glass Orchard", linde, ext = "pdf", series = "The Halden Cycle", n = 3, year = 2025, added = nowDate.minusDays(90))
    private val orchardAudio = audiobook(id("Glass Orchard audio"), "The Glass Orchard", "Mara Linde", "Sam Okafor", 14 * 3600L + 20 * 60L)
    private val detailsSeries = listOf(
        book("Halden", linde, series = "The Halden Cycle", n = 1, played = true),
        book("The Long Frost", linde, series = "The Halden Cycle", n = 2, played = true),
        orchardEpub,
        book("Lantern Season", linde, series = "The Halden Cycle", n = 4, added = nowDate.minusDays(3)),
        book("The Last Thaw", linde, series = "The Halden Cycle", n = 5),
    )
    private val orchardReading = Reading(
        itemId = orchardEpub.id.toString().replace("-", ""), locator = "epubcfi(/6/24!/4)", percent = 46.0, chapter = "Frost", chapterIndex = 12, chapterCount = 28,
        updatedAt = "2026-10-28T20:00:00Z",
    )

    val details = BookViewModel.State(
        item = orchardEpub, loading = false, libraryId = id("lib Books"),
        data = booksData(detailsSeries + orchardPdf + orchardAudio + recent, listOf(orchardReading)),
        info = BookInfo(format = "epub", pages = 412, minutes = 315),
        reading = orchardReading,
        sizes = mapOf(orchardEpub.id to 2_202_010L, orchardPdf.id to 9_017_754L),
        policyDownload = true,
    )

    val bookmarks = listOf(
        io.github.arkmind.seedy.core.books.Bookmark("4512.0", 1, "The Inlet", 0),
        io.github.arkmind.seedy.core.books.Bookmark("20870.0", 6, "The Lighthouse", 0),
    )

    /** The audiobook of The Glass Orchard, half-way, with its EPUB alongside. */
    val audioDetails = details.copy(
        item = orchardAudio.copy(
            userData = orchardAudio.userData?.copy(playbackPositionTicks = 7 * 3600L * SEC, lastPlayedDate = nowDate.minusHours(3)),
            overview = GLASS_OVERVIEW,
            chapters = (0 until 28).map { ChapterInfo(startPositionTicks = it * 1843L * SEC, name = "Chapter ${it + 1}", imageDateModified = nowDate) },
        ),
        reading = null, info = null, twinReading = orchardReading,
    )
}
