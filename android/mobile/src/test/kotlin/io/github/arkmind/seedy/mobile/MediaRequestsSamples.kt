package io.github.arkmind.seedy.mobile

import io.github.arkmind.seedy.core.mediarequests.AlbumRef
import io.github.arkmind.seedy.core.mediarequests.AskedState
import io.github.arkmind.seedy.core.mediarequests.BookCard
import io.github.arkmind.seedy.core.mediarequests.BookRequestViewModel
import io.github.arkmind.seedy.core.mediarequests.BookRow
import io.github.arkmind.seedy.core.mediarequests.MediaFormat
import io.github.arkmind.seedy.core.mediarequests.MediaFormatRoute
import io.github.arkmind.seedy.core.mediarequests.MediaKindSummary
import io.github.arkmind.seedy.core.mediarequests.MediaNote
import io.github.arkmind.seedy.core.mediarequests.MediaOptions
import io.github.arkmind.seedy.core.mediarequests.MediaRequest
import io.github.arkmind.seedy.core.mediarequests.MediaRequestsViewModel
import io.github.arkmind.seedy.core.mediarequests.MediaSummary
import io.github.arkmind.seedy.core.mediarequests.MediaTab
import io.github.arkmind.seedy.core.mediarequests.MusicArtist
import io.github.arkmind.seedy.core.mediarequests.MusicArtistRef
import io.github.arkmind.seedy.core.mediarequests.MusicArtistViewModel
import io.github.arkmind.seedy.core.mediarequests.MusicRelease
import io.github.arkmind.seedy.core.mediarequests.MusicRow
import io.github.arkmind.seedy.core.mediarequests.RequestService
import io.github.arkmind.seedy.mobile.requests.ServiceTabs
import java.time.LocalDate

/** Boards "Android · Requests · Music" and "… Books" sample data. */
object MediaRequestsSamples {
    /** The boards' day: The Long Light (Fri 6 Nov) is eight days off, so it's lit. */
    val today: LocalDate = LocalDate.of(2026, 10, 29)

    /** An id whose web hash draws the board's cover [i] (the art cycles every 36 for albums, 8 for books). */
    private fun seed(prefix: String, i: Int, cycle: Int): String =
        generateSequence(0) { it + 1 }.map { "$prefix-$it" }.first { MediaFormat.hash(it) % cycle == i.toLong() }

    private fun album(i: Int, prefix: String) = seed(prefix, i, 36)
    private fun book(i: Int, prefix: String) = seed(prefix, i, 8)

    fun services(current: RequestService) = ServiceTabs(RequestService.entries, current) {}

    private fun rel(i: Int, title: String, type: String, artist: String? = null, date: String? = null, status: String = "none", upcoming: Boolean = false, have: Int? = null, of: Int? = null) =
        MusicRelease(album(i, title), artistMbid = "artist-$title", title = title, artist = artist, type = type, date = date, status = status, upcoming = upcoming, have = have, of = of)

    private val musicMine = listOf(
        MediaRequest(
            "m1", kind = "music", mode = "artist", foreignId = album(3, "mira"), title = "Mira Sol", units = 5, requestedByMe = true,
            requestedAt = "2026-10-19T17:12:00Z", status = "downloading", progress = 0.6,
            note = MediaNote("albums", n = 3, total = 5, name = "Glasswork", fraction = 0.4),
        ),
        MediaRequest(
            "m2", kind = "music", mode = "albums", foreignId = "kestrel", title = "Kestrel Choir", albums = listOf(AlbumRef(album(5, "small"), "Small Hours")),
            requestedByMe = true, requestedAt = "2026-10-15T10:00:00Z", status = "searching", attention = true, canSearch = true, canCancel = true,
            lowerQuality = "MP3 320", note = MediaNote("noCopy", days = 6, name = "lossless"),
        ),
    )

    val music = MediaRequestsViewModel.State(
        summary = MediaSummary(music = MediaKindSummary(true, open = 3), books = MediaKindSummary(true, open = 3)),
        music = MediaRequestsViewModel.Lists(mine = musicMine),
        musicRows = listOf(
            MediaRequestsViewModel.MusicRowState(
                MusicRow.New,
                listOf(
                    rel(2, "Tidelines", "single", "Ada Vale", "2026-10-16"),
                    rel(6, "Engine Room", "album", "The Quiet Engine"),
                    rel(8, "Signal Fires", "ep", "Lumen Fields", status = "requested"),
                    rel(0, "Glasswork", "album", "Mira Sol", status = "downloading"),
                ),
            ),
            MediaRequestsViewModel.MusicRowState(
                MusicRow.Missing,
                listOf(
                    rel(7, "Winter Radio Live", "album", "Ada Vale", have = 7, of = 8),
                    rel(4, "Early Songs", "album", "Lumen Fields", have = 4, of = 6),
                    rel(1, "Ghost Stations", "album", "Oren Moss", have = 2, of = 4),
                    rel(5, "Northern Lights", "single", "Harbor Lights"),
                ),
            ),
            MediaRequestsViewModel.MusicRowState(MusicRow.Trending, emptyList()),
            MediaRequestsViewModel.MusicRowState(
                MusicRow.Soon,
                listOf(
                    rel(1, "The Long Light", "album", "Ada Vale", "2026-11-06", upcoming = true),
                    rel(5, "Midwinter", "album", "Kestrel Choir", "2026-12-04", upcoming = true),
                    rel(3, "Harbour Songs", "album", null, "2027-01", upcoming = true),
                    rel(7, "Second Light", "album", null, "2027-02", upcoming = true),
                ),
            ),
        ),
        asked = AskedState(),
    )

    /** No board: Music · My requests (the RequestsMine board's rows on the phone). */
    val musicMineTab = music.copy(
        tab = MediaTab.Mine,
        music = MediaRequestsViewModel.Lists(
            mine = musicMine + listOf(
                MediaRequest(
                    "m3", kind = "music", mode = "albums", foreignId = "lumen", title = "Lumen Fields", albums = listOf(AlbumRef(album(8, "sig"), "Signal Fires")),
                    requestedByMe = true, requestedAt = "2026-10-29T08:00:00Z", status = "pending", canCancel = true,
                ),
                MediaRequest(
                    "m4", kind = "music", mode = "albums", foreignId = "ada", title = "Ada Vale", albums = listOf(AlbumRef(album(2, "tide"), "Tidelines")),
                    requestedByMe = true, requestedAt = "2026-10-17T08:00:00Z", status = "ready", itemId = "0123456789abcdef0123456789abcdef", canCancel = true,
                ),
            ),
        ),
    )

    private fun bk(i: Int, title: String, author: String, formats: List<String>, content: String = "book", status: String = "none", series: String? = null, index: Double? = null, upcoming: Boolean = false, releaseDate: String? = null) =
        BookCard(book(i, title), title, author, content = content, formats = formats, status = status, series = series, seriesIndex = index, upcoming = upcoming, releaseDate = releaseDate)

    private val booksMine = listOf(
        MediaRequest("b1", kind = "book", service = "shelfarr", mode = "book", foreignId = book(4, "frost"), title = "The Long Frost", subtitle = "Mara Linde", formats = listOf("audiobook"), requestedByMe = true, requestedAt = "2026-10-26T10:00:00Z", status = "searching", note = MediaNote("found", n = 2, name = "Shelfarr")),
        MediaRequest("b2", kind = "book", service = "librarr", mode = "book", foreignId = book(5, "kestrel"), title = "Kestrel No. 6", subtitle = "Hale & Ito", formats = listOf("comic"), content = "comic", requestedByMe = true, requestedAt = "2026-10-29T10:00:00Z", status = "pending", canCancel = true),
        MediaRequest("b3", kind = "book", service = "shelfarr", mode = "book", foreignId = "copper", title = "Copper Line", subtitle = "Tess Varga", formats = listOf("ebook"), requestedByMe = true, requestedAt = "2026-10-20T10:00:00Z", readyAt = "2026-10-28T10:00:00Z", status = "ready", itemId = "0123456789abcdef0123456789abcdef"),
    )

    val books = MediaRequestsViewModel.State(
        summary = MediaSummary(music = MediaKindSummary(true, open = 3), books = MediaKindSummary(true, open = 3)),
        books = MediaRequestsViewModel.Lists(mine = booksMine),
        bookRows = listOf(
            MediaRequestsViewModel.BookRowState(
                BookRow.Next,
                listOf(
                    bk(0, "Black Ice", "Dana Holm", listOf("ebook", "audiobook"), series = "Coldwater Mysteries", index = 7.0),
                    bk(7, "Kestrel No. 6", "Hale & Ito", listOf("comic"), content = "comic", status = "requested", series = "Kestrel", index = 6.0),
                    bk(6, "The Last Thaw", "Mara Linde", listOf("ebook", "audiobook"), series = "Halden", index = 5.0, upcoming = true, releaseDate = "2027-03-12"),
                    bk(3, "Ember Run 3", "Sato & Price", listOf("comic"), content = "comic", series = "Ember Run", index = 3.0),
                ),
            ),
            MediaRequestsViewModel.BookRowState(
                BookRow.Audiobooks,
                listOf(
                    bk(1, "Halden", "Mara Linde", listOf("audiobook")),
                    bk(4, "The Long Frost", "Mara Linde", listOf("audiobook"), status = "downloading"),
                    bk(6, "Copper Line", "Tess Varga", listOf("audiobook"), status = "server"),
                    bk(0, "North of Nowhere", "Dana Holm", listOf("audiobook")),
                ),
            ),
            MediaRequestsViewModel.BookRowState(
                BookRow.Trending,
                listOf(
                    bk(2, "The Night Garden", "Iris Callow", listOf("ebook", "audiobook")),
                    bk(5, "Saltwater Saints", "M. Okafor", listOf("ebook")),
                ),
            ),
        ),
    )

    /** No board: the artist window (RequestMusicArtist's content in Request seasons' frame). */
    val artist = run {
        val mbid = album(4, "mira-artist")
        MusicArtistViewModel.State(
            target = MusicArtistViewModel.Target(mbid, "Mira Sol"),
            artist = MusicArtist(
                MusicArtistRef(mbid, "Mira Sol", type = "Person", area = "Lisbon"),
                listOf(
                    MusicRelease(album(0, "glass"), title = "Glasswork", type = "album", date = "2026-03-02", tracks = 11, lengthMs = 44 * 60000L),
                    MusicRelease(album(3, "harbour"), title = "Harbour Weather", type = "album", date = "2024-05-01", tracks = 10, lengthMs = 39 * 60000L),
                    MusicRelease(album(6, "salt"), title = "Salt Hymns", type = "album", date = "2022-01-01", tracks = 12, lengthMs = 51 * 60000L, status = "server"),
                    MusicRelease(album(8, "lisbon"), title = "Lisbon Tapes", type = "album", date = "2020-01-01", tracks = 9, lengthMs = 33 * 60000L),
                    MusicRelease(album(1, "first"), title = "First Light", type = "album", date = "2018-01-01", tracks = 8, lengthMs = 29 * 60000L, status = "requested", requestedBy = "Léa"),
                    MusicRelease(album(2, "ep1"), title = "Coastline", type = "ep", date = "2019-01-01"),
                    MusicRelease(album(5, "s1"), title = "Tide", type = "single", date = "2025-01-01"),
                ),
                albumsOnServer = 1,
            ),
            options = MediaOptions(kind = "music", canRequest = true, limit = 5, used = 2, quality = "Lossless (FLAC)", formats = listOf(MediaFormatRoute("music", "lidarr", "Music"))),
            picked = setOf(album(0, "glass"), album(3, "harbour")),
        )
    }

    /** No board: the book window (RequestBook's content in Request seasons' frame). */
    val book = BookRequestViewModel.State(
        target = BookRequestViewModel.Target(bk(0, "Black Ice", "Dana Holm", listOf("ebook", "audiobook"), series = "Coldwater Mysteries", index = 7.0), BookRow.Next),
        details = bk(0, "Black Ice", "Dana Holm", listOf("ebook", "audiobook"), series = "Coldwater Mysteries", index = 7.0).copy(
            year = 2026, pages = 384, readFrom = 1.0, readTo = 6.0, languages = listOf("eng"),
            description = "The lake freezes early and a car is found under the ice with its engine still warm.",
        ),
        options = MediaOptions(
            kind = "book", canRequest = true, limit = 8, used = 3, period = "month", autoApprove = true, scanOnReady = true,
            formats = listOf(MediaFormatRoute("ebook", "shelfarr", "Books"), MediaFormatRoute("audiobook", "shelfarr", "Audiobooks")),
        ),
        picked = setOf("ebook", "audiobook"),
        lang = "en",
    )
}
