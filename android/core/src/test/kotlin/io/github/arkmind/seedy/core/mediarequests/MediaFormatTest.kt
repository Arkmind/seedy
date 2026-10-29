package io.github.arkmind.seedy.core.mediarequests

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The music and book requests' wording (web: media-parts.tsx, music.tsx, books.tsx), on Thursday 29 Oct 2026. */
class MediaFormatTest {
    private val today = LocalDate.of(2026, 10, 29)
    private val json = Json { ignoreUnknownKeys = true }

    /** A request as Seedy sends it (the dev server's, trimmed). */
    private val artist = json.decodeFromString<MediaRequest>(
        """{"id":"f4d6","kind":"music","service":"lidarr","mode":"artist","foreignId":"998b","title":"Mira Sol","albums":[],"formats":[],"keepNew":true,
        "units":5,"requestedBy":"Alex","requestedByMe":true,"requestedAt":"2026-10-19T17:12:00.0000000Z","updatedAt":"2026-10-19T19:12:00.0000000Z",
        "status":"downloading","attention":false,"note":{"code":"albums","n":3,"total":5,"name":"Glasswork","fraction":0.4},"progress":0.6,
        "parts":[{"format":"music","service":"lidarr","status":"downloading","progress":0.6}],"canSearch":false,"canCancel":true,"canDecide":false,"extra":1}""",
    )

    @Test
    fun requests() {
        assertEquals("Mira Sol · whole discography", MediaFormat.name(artist))
        assertEquals("Artist · 5 albums · asked Mon 19 Oct", MediaFormat.whatLine(artist, everyone = false, today))
        assertEquals("Artist · 5 albums · asked by you Mon 19 Oct", MediaFormat.whatLine(artist, everyone = true, today))
        assertEquals(ToneText("3 of 5 albums imported · Glasswork at 40%", "accent"), MediaFormat.note(artist))
        assertEquals(MediaStage(4), MediaFormat.stage(artist))

        val album = artist.copy(
            mode = "albums", title = "Kestrel Choir", albums = listOf(AlbumRef("a", "Small Hours")), status = "searching", attention = true,
            note = MediaNote("noCopy", days = 6, name = "lossless"),
        )
        assertEquals("Small Hours · Kestrel Choir", MediaFormat.name(album))
        assertEquals("No lossless copy after 6 days · Lidarr retries every 6 hours", MediaFormat.note(album)?.text)
        assertTrue(MediaFormat.stage(album).fail)
        assertTrue(MediaFormat.matches(album, MediaShow.Attention))

        val book = artist.copy(kind = "book", mode = "book", title = "Lantern Season", formats = listOf("audiobook"), status = "pending", note = null)
        assertEquals("Lantern Season · audiobook", MediaFormat.name(book))
        assertEquals(ToneText("Waiting for an admin", "pending"), MediaFormat.note(book))
        assertTrue(MediaFormat.matches(book, MediaShow.Pending))
        assertFalse(MediaFormat.matches(book, MediaShow.Done))
    }

    @Test
    fun albums() {
        val new = MusicRelease("t", title = "Tidelines", artist = "Ada Vale", type = "single", date = "2026-10-16")
        assertEquals("Ada Vale · 16 Oct" to false, MediaFormat.releaseMeta(new, MusicRow.New, today))
        val missing = MusicRelease("w", title = "Winter Radio Live", artist = "Ada Vale", have = 7, of = 8)
        assertEquals("Ada Vale · 7 of 8" to false, MediaFormat.releaseMeta(missing, MusicRow.Missing, today))
        val soon = MusicRelease("l", title = "The Long Light", artist = "Ada Vale", date = "2026-11-06", upcoming = true)
        assertEquals("Ada Vale · 6 Nov" to true, MediaFormat.releaseMeta(soon, MusicRow.Soon, today))
        assertEquals("Kestrel Choir · 4 Dec" to false, MediaFormat.releaseMeta(soon.copy(artist = "Kestrel Choir", date = "2026-12-04"), MusicRow.Soon, today))
        assertEquals("Jan 2027", MediaFormat.releaseMeta(soon.copy(artist = null, date = "2027-01"), MusicRow.Soon, today).first)
        assertEquals("Soon" to "soon", MediaFormat.releaseType(soon))
        assertEquals("2026 · 11 songs · 44 min", MediaFormat.releaseSub(MusicRelease("g", date = "2026-03-02", tracks = 11, lengthMs = 44 * 60000L)))
    }

    @Test
    fun books() {
        val next = BookCard("w", "The Last Thaw", "Mara Linde", series = "Halden", seriesIndex = 5.0, formats = listOf("ebook", "audiobook"), upcoming = true, releaseDate = "2027-03-12")
        assertEquals("Halden 5 · Mar 2027" to true, MediaFormat.bookMeta(next, BookRow.Next, today))
        val comic = BookCard("k", "Kestrel No. 6", series = "Kestrel", seriesIndex = 6.0, content = "comic", formats = listOf("comic"))
        assertEquals("Kestrel · Vol. 6" to false, MediaFormat.bookMeta(comic, BookRow.Next, today))
        assertEquals("Ebook + audio" to "both", MediaFormat.bookType(next.copy(upcoming = false)))
        assertEquals("Comic" to "comic", MediaFormat.bookType(comic))
        assertEquals("Shelfarr · M4B or MP3 folders", MediaFormat.routeLine("audiobook", "shelfarr"))
    }

    @Test
    fun askedCards() {
        val asked = AskedState(cards = mapOf("artist:x" to Asked("waiting", null), "b" to Asked("requested", "Sam")))
        assertEquals("waiting", asked.release(MusicRelease("a", artistMbid = "x")).status)
        assertEquals("server", asked.release(MusicRelease("a", artistMbid = "x", status = "server")).status)
        assertEquals("Sam", asked.book(BookCard("b")).requestedBy)
    }
}
