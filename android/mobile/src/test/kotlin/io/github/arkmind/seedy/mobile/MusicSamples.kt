package io.github.arkmind.seedy.mobile

import io.github.arkmind.seedy.core.music.AlbumViewModel
import io.github.arkmind.seedy.core.music.ArtistViewModel
import io.github.arkmind.seedy.core.music.Discography
import io.github.arkmind.seedy.core.music.LyricLine
import io.github.arkmind.seedy.core.music.Lyrics
import io.github.arkmind.seedy.core.music.Mix
import io.github.arkmind.seedy.core.music.MusicHome
import io.github.arkmind.seedy.core.music.MusicLibraryViewModel
import io.github.arkmind.seedy.core.music.PlaylistPreview
import io.github.arkmind.seedy.core.music.TopArtist
import io.github.arkmind.seedy.mobile.music.Playing
import io.github.arkmind.seedy.player.audio.AudioMode
import io.github.arkmind.seedy.player.audio.AudioState
import io.github.arkmind.seedy.player.audio.AudioTime
import io.github.arkmind.seedy.player.audio.Entry
import io.github.arkmind.seedy.player.audio.Source
import io.github.arkmind.seedy.player.audio.SourceKind
import io.github.arkmind.seedy.ui.components.coverSeed
import io.github.arkmind.seedy.ui.components.webId
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.NameGuidPair
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.util.UUID

/** The music boards' sample data ("Android · Music", "· Album", "· Artist", "· Now playing"). */
object MusicSamples {
    private const val SEC = 10_000_000L

    /** An id whose drawn cover is the boards' palette [palette] (the covers are seeded by id, as on the web). */
    fun idFor(name: String, palette: Int): UUID {
        var k = 0
        while (true) {
            val id = UUID.nameUUIDFromBytes("$name#$k".toByteArray())
            if (coverSeed(webId(id)) % 9 == palette) return id
            k++
        }
    }

    private fun data(id: UUID, plays: Int = 0, favorite: Boolean = false) =
        UserItemDataDto(playbackPositionTicks = 0, playCount = plays, isFavorite = favorite, played = plays > 0, key = id.toString(), itemId = id)

    private val flac = MediaStream(
        index = 0, type = MediaStreamType.AUDIO, codec = "flac", isDefault = true, isHearingImpaired = false, isInterlaced = false, isForced = false,
        isOriginal = false, isExternal = false, isTextSubtitleStream = false, supportsExternalStream = false,
    )

    val adaVale = idFor("Ada Vale", 0).let { id ->
        BaseItemDto(id = id, type = BaseItemKind.MUSIC_ARTIST, name = "Ada Vale", userData = data(id, favorite = true))
    }
    private val ada = NameGuidPair(name = "Ada Vale", id = adaVale.id)

    fun album(name: String, palette: Int, artist: String = "Ada Vale", year: Int = 2024, count: Int = 10, favorite: Boolean = false): BaseItemDto {
        val id = idFor(name, palette)
        return BaseItemDto(
            id = id, type = BaseItemKind.MUSIC_ALBUM, name = name, albumArtist = artist, productionYear = year, childCount = count,
            albumArtists = if (artist == "Ada Vale") listOf(ada) else listOf(NameGuidPair(name = artist, id = idFor(artist, 3))), userData = data(id, favorite = favorite),
        )
    }

    val northline = album("Northline", 0, count = 12, favorite = true)

    private fun clock(s: String) = s.split(":").let { it[0].toLong() * 60 + it[1].toLong() } * SEC

    fun track(name: String, album: BaseItemDto, n: Int, time: String, artists: List<String> = listOf("Ada Vale"), plays: Int = 0): BaseItemDto {
        val id = UUID.nameUUIDFromBytes("${album.name}/$name".toByteArray())
        return BaseItemDto(
            id = id, type = BaseItemKind.AUDIO, name = name, album = album.name, albumId = album.id, albumArtist = album.albumArtist, artists = artists,
            artistItems = listOf(ada), albumArtists = album.albumArtists, indexNumber = n, parentIndexNumber = 1, runTimeTicks = clock(time),
            mediaStreams = listOf(flac), mediaType = MediaType.AUDIO, userData = data(id, plays),
        )
    }

    val northlineTracks: List<BaseItemDto> = listOf(
        "First Light" to "3:42", "Northline" to "4:12", "Paper Boats" to "3:05", "Halden" to "5:20", "Glass Weather" to "3:48", "Static Hymn" to "4:01",
        "Long Exposure" to "6:12", "Ferry Song" to "2:58", "Lanterns" to "3:33", "Tidewater" to "4:40", "Coastline" to "4:45", "Last Light" to "4:44",
    ).mapIndexed { i, (n, time) -> track(n, northline, i + 1, time, if (i == 8) listOf("Ada Vale", "Oren Moss") else listOf("Ada Vale")) }

    val playingTrack = northlineTracks[1].let { it.copy(userData = data(it.id, favorite = true)) }

    val audio = AudioState(
        mode = AudioMode.Music, current = Entry("np", playingTrack), context = northlineTracks.map { Entry(it.id.toString(), it) }, pos = 1,
        source = Source(SourceKind.Album, northline.id.toString(), "Northline"), shuffle = true, playing = true,
    )
    val time = AudioTime(108.0, 252.0)
    val miniTime = AudioTime(0.43 * 252, 252.0)
    val playing = Playing.of(audio)

    /* ── Music ── */

    private val music = BaseItemDto(id = idFor("Music", 5), type = BaseItemKind.COLLECTION_FOLDER, name = "Music", collectionType = CollectionType.MUSIC)

    private fun playlist(name: String, palette: Int, count: Int) =
        idFor(name, palette).let { BaseItemDto(id = it, type = BaseItemKind.PLAYLIST, name = name, mediaType = MediaType.AUDIO, childCount = count) }

    val library = MusicLibraryViewModel.State(
        view = music,
        home = MusicHome(
            jump = listOf(northline, playlist("Late night", 1, 26), album("Lumen Fields", 2, "Lumen Fields"), album("Field Recordings", 3, year = 2016)),
            mixes = listOf(
                Mix("artist", webId(idFor("ada-radio", 0))!!, "Ada Vale radio", "Ada Vale and 24 artists like them", listOf(northline, album("Harbor Lights", 7, "Harbor Lights"))),
                Mix("favs", webId(idFor("favs", 1))!!, "Your favourites mix", "Songs you love, and more like them", listOf(album("Late night", 1), album("Night Ferry", 8, "Oren Moss"))),
                Mix("new", webId(idFor("new", 2))!!, "Something new", "Albums on the server you’ve never played", listOf(album("Undertow", 2, "Oren Moss"), album("Field Recordings", 3))),
            ),
            recent = listOf(
                album("Blue Hour", 4, "Lumen Fields", 2026), album("Copper Line", 6, "The Quiet Engine", 2025), album("Harbor Lights", 7, "Harbor Lights", 2025),
                album("Small Hours", 5, "Kestrel Choir"), album("Undertow", 2, "Oren Moss"),
            ),
            playlists = listOf(PlaylistPreview(playlist("Focus", 4, 11), listOf(northline), "11 songs · 42 min")),
            top = listOf(TopArtist(adaVale, 1240)),
            genres = emptyList(),
        ),
    )

    private val allAlbums = listOf(
        northline, album("Blue Hour", 4, "Lumen Fields", 2026), album("Copper Line", 6, "The Quiet Engine", 2025), album("Harbor Lights", 7, "Harbor Lights", 2025),
        album("Small Hours", 5, "Kestrel Choir"), album("Undertow", 2, "Oren Moss"), album("Field Recordings", 3, year = 2016),
    )

    val albumsTab = library.copy(
        tab = io.github.arkmind.seedy.core.music.MusicTab.Albums,
        albums = io.github.arkmind.seedy.core.music.Paged(allAlbums, 14, loaded = true),
    )

    val songsTab = library.copy(
        tab = io.github.arkmind.seedy.core.music.MusicTab.Songs, favorites = true,
        songs = io.github.arkmind.seedy.core.music.Paged(northlineTracks, 117, loaded = true),
    )

    val homeMore = library.copy(
        home = library.home.copy(
            playlists = listOf(
                PlaylistPreview(playlist("Focus", 4, 11), listOf(northline, album("Blue Hour", 4), album("Copper Line", 6), album("Undertow", 2)), "11 songs · 42 min"),
                PlaylistPreview(playlist("Late night", 1, 26), listOf(album("Late night", 1)), "26 songs · 1 h 42 min"),
            ),
            top = listOf(TopArtist(adaVale, 1240), TopArtist(idFor("Oren Moss", 8).let { BaseItemDto(id = it, type = BaseItemKind.MUSIC_ARTIST, name = "Oren Moss") }, 312)),
            genres = listOf("Indie folk" to 5, "Ambient" to 2, "Chamber pop" to 2, "Electronic" to 2).mapIndexed { i, (g, n) ->
                io.github.arkmind.seedy.core.music.GenreSample(BaseItemDto(id = idFor(g, i), type = BaseItemKind.MUSIC_GENRE, name = g, albumCount = n), allAlbums[i + 1])
            },
        ),
    )

    /* ── Album ── */

    val albumState = AlbumViewModel.State(item = northline, tracks = northlineTracks, canDownload = true)

    val playlistState = AlbumViewModel.State(
        item = playlist("Road trip", 6, 33),
        tracks = listOf(northlineTracks[0], track("Copper Line", album("Copper Line", 6, "The Quiet Engine"), 3, "3:58", listOf("The Quiet Engine")), northlineTracks[1],
            track("Harbor Lights", album("Harbor Lights", 7, "Harbor Lights"), 1, "4:20", listOf("Harbor Lights")), track("Undertow", album("Undertow", 2, "Oren Moss"), 2, "5:02", listOf("Oren Moss"))),
    )

    /* ── Artist ── */

    private val winterRadio = album("Winter Radio", 7, year = 2021)

    val artistState = ArtistViewModel.State(
        item = adaVale,
        songs = listOf(
            northlineTracks[1].copy(userData = data(northlineTracks[1].id, 1204)),
            northlineTracks[4].copy(userData = data(northlineTracks[4].id, 851)),
            northlineTracks[2].copy(userData = data(northlineTracks[2].id, 688)),
            track("Winter Radio", winterRadio, 1, "4:02", plays = 612),
            northlineTracks[8].copy(userData = data(northlineTracks[8].id, 540)),
        ),
        disc = Discography(
            albums = listOf(northline, winterRadio, album("Field Recordings", 3, year = 2016)),
            singles = listOf(album("Tidelines", 5, year = 2026, count = 1)),
        ),
    )

    /* ── Now playing ── */

    val queued = audio.copy(added = listOf(Entry("a1", track("Undertow", album("Undertow", 2, "Oren Moss"), 2, "5:02", listOf("Oren Moss")))))

    val lyrics = Lyrics(
        playingTrack.id,
        listOf(
            LyricLine("We drove until the coast ran out", 90.0), LyricLine("and every mile the radio", 104.0),
            LyricLine("kept singing things we didn’t know", 112.0), LyricLine("about the north, about the snow", 120.0),
        ),
    )
}
