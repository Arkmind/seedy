package io.github.arkmind.seedy.core.music

import io.github.arkmind.seedy.core.audio.AudioApi
import io.github.arkmind.seedy.core.browse.Browse
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.artistApi
import org.jellyfin.sdk.api.client.extensions.genreApi
import org.jellyfin.sdk.api.client.extensions.instantMixApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.playlistApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlayCommand
import org.jellyfin.sdk.model.api.SessionInfoDto
import org.jellyfin.sdk.model.api.SortOrder
import java.util.UUID

/** A sort of a music tab (web tabs.tsx): its id, its English label, Jellyfin's keys and the order. */
data class MusicSort(val id: String, val label: String, val by: List<ItemSortBy>, val descending: Boolean)

object MusicSorts {
    val Albums = listOf(
        MusicSort("DateCreated", "Date added", listOf(ItemSortBy.DATE_CREATED, ItemSortBy.SORT_NAME), true),
        MusicSort("SortName", "Title", listOf(ItemSortBy.SORT_NAME), false),
        MusicSort("Artist", "Artist", listOf(ItemSortBy.ALBUM_ARTIST, ItemSortBy.PRODUCTION_YEAR, ItemSortBy.SORT_NAME), false),
        MusicSort("Year", "Release date", listOf(ItemSortBy.PRODUCTION_YEAR, ItemSortBy.PREMIERE_DATE, ItemSortBy.SORT_NAME), true),
        MusicSort("DatePlayed", "Recently played", listOf(ItemSortBy.DATE_PLAYED, ItemSortBy.SORT_NAME), true),
        MusicSort("Random", "Random", listOf(ItemSortBy.RANDOM), false),
    )
    val Artists = listOf(
        MusicSort("SortName", "Name", listOf(ItemSortBy.SORT_NAME), false),
        MusicSort("DateCreated", "Date added", listOf(ItemSortBy.DATE_CREATED, ItemSortBy.SORT_NAME), true),
        MusicSort("Random", "Random", listOf(ItemSortBy.RANDOM), false),
    )

    /** Songs' sort names start with their track number: titles sort by name. */
    val Songs = listOf(
        MusicSort("SortName", "Title", listOf(ItemSortBy.NAME), false),
        MusicSort("Artist", "Artist", listOf(ItemSortBy.ALBUM_ARTIST, ItemSortBy.ALBUM, ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER), false),
        MusicSort("Album", "Album", listOf(ItemSortBy.ALBUM, ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER), false),
        MusicSort("DateCreated", "Date added", listOf(ItemSortBy.DATE_CREATED, ItemSortBy.NAME), true),
        MusicSort("PlayCount", "Most played", listOf(ItemSortBy.PLAY_COUNT, ItemSortBy.NAME), true),
        MusicSort("DatePlayed", "Recently played", listOf(ItemSortBy.DATE_PLAYED, ItemSortBy.NAME), true),
        MusicSort("Random", "Random", listOf(ItemSortBy.RANDOM), false),
    )
}

/** An instant mix card ("Made for you"): two covers, and its songs (loaded when it's played when [tracks] is null). */
data class Mix(
    val key: String,
    /** What the mix grew from (its source id, and the drawn colours when the covers have no picture). */
    val seed: String,
    val title: String,
    val sub: String,
    val covers: List<BaseItemDto>,
    val tracks: List<BaseItemDto>? = null,
)

data class PlaylistPreview(val playlist: BaseItemDto, val covers: List<BaseItemDto>, val sub: String)

data class TopArtist(val artist: BaseItemDto, val plays: Int)

data class GenreSample(val genre: BaseItemDto, val sample: BaseItemDto?)

/** An artist's releases: albums, singles and EPs (as album artist), albums they appear on, music videos. */
data class Discography(
    val albums: List<BaseItemDto> = emptyList(),
    val singles: List<BaseItemDto> = emptyList(),
    val appears: List<BaseItemDto> = emptyList(),
    val videos: List<BaseItemDto> = emptyList(),
) {
    val owned: Int get() = albums.size + singles.size
}

/** A Jellyfin app this one can hand the queue to ("Play on another device"). */
data class CastTarget(val id: String, val name: String, val sub: String)

/**
 * The calls behind the music library, album, artist and Now playing pages (web/src/screens/music), beyond the audio
 * player's own ([AudioApi]).
 */
class MusicApi(private val session: UserSession) {
    private val api get() = session.api
    private val userId get() = session.userId
    val audio = AudioApi(session)
    private val browse = Browse(session)

    suspend fun item(id: UUID): BaseItemDto = browse.item(id)

    suspend fun setFavorite(id: UUID, on: Boolean) = browse.setFavorite(id, on)

    /** Whether this profile may download files (the album's Download button). */
    val canDownload: Boolean get() = session.user.policy?.enableContentDownloading == true

    /** Items by id, in that order. */
    suspend fun items(ids: List<UUID>, fields: List<ItemFields> = MusicFields): List<BaseItemDto> = io {
        if (ids.isEmpty()) return@io emptyList()
        val found = api.libraryApi.getItems(userId = userId, ids = ids, fields = fields, recursive = true, enableUserData = true).content.items.associateBy { it.id }
        ids.mapNotNull { found[it] }
    }

    private suspend fun query(
        parentId: UUID? = null,
        types: List<BaseItemKind>,
        sort: List<ItemSortBy> = emptyList(),
        descending: Boolean = false,
        start: Int? = null,
        limit: Int? = null,
        fields: List<ItemFields> = MusicFields,
        favorites: Boolean? = null,
        filters: List<ItemFilter>? = null,
        genreIds: List<UUID>? = null,
        artistIds: List<UUID>? = null,
        albumArtistIds: List<UUID>? = null,
        images: List<ImageType>? = null,
        count: Boolean = true,
    ): BaseItemDtoQueryResult = io {
        api.libraryApi.getItems(
            userId = userId, parentId = parentId, includeItemTypes = types, recursive = true, sortBy = sort,
            sortOrder = if (sort.isEmpty()) null else listOf(if (descending) SortOrder.DESCENDING else SortOrder.ASCENDING),
            startIndex = start, limit = limit, fields = fields, isFavorite = favorites, filters = filters, genreIds = genreIds,
            artistIds = artistIds, albumArtistIds = albumArtistIds, enableImageTypes = images, enableUserData = true, enableTotalRecordCount = count,
        ).content
    }

    /* ── Counts ── */

    suspend fun count(parentId: UUID, kind: BaseItemKind): Int =
        query(parentId, listOf(kind), limit = 0, fields = emptyList(), images = emptyList()).totalRecordCount

    suspend fun albumArtistsCount(parentId: UUID): Int = io {
        api.artistApi.getAlbumArtists(userId = userId, parentId = parentId, limit = 0, enableTotalRecordCount = true, enableImages = false).content.totalRecordCount
    }

    /* ── Tabs ── */

    suspend fun albums(parentId: UUID, sort: MusicSort, favorites: Boolean, start: Int, limit: Int, genreId: UUID? = null): BaseItemDtoQueryResult =
        query(parentId, listOf(BaseItemKind.MUSIC_ALBUM), sort.by, sort.descending, start, limit, favorites = favorites.takeIf { it }, genreIds = genreId?.let(::listOf))

    suspend fun albumArtists(parentId: UUID, sort: MusicSort, favorites: Boolean, start: Int, limit: Int): BaseItemDtoQueryResult = io {
        api.artistApi.getAlbumArtists(
            userId = userId, parentId = parentId, startIndex = start, limit = limit, sortBy = sort.by,
            sortOrder = listOf(if (sort.descending) SortOrder.DESCENDING else SortOrder.ASCENDING), isFavorite = favorites.takeIf { it },
            fields = listOf(ItemFields.ITEM_COUNTS, ItemFields.CHILD_COUNT, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO),
            enableImageTypes = listOf(ImageType.PRIMARY, ImageType.BACKDROP), imageTypeLimit = 1, enableTotalRecordCount = true, enableUserData = true,
        ).content
    }

    suspend fun songs(parentId: UUID, sort: MusicSort, favorites: Boolean, start: Int, limit: Int): BaseItemDtoQueryResult =
        query(parentId, listOf(BaseItemKind.AUDIO), sort.by, sort.descending, start, limit, AudioApi.TrackFields, favorites.takeIf { it })

    /** Songs of a library by some rule (a favourite, the most played…). */
    suspend fun songsOf(parentId: UUID, sort: List<ItemSortBy>, descending: Boolean = false, limit: Int, favorites: Boolean? = null, filters: List<ItemFilter>? = null): List<BaseItemDto> =
        query(parentId, listOf(BaseItemKind.AUDIO), sort, descending, limit = limit, fields = AudioApi.TrackFields, favorites = favorites, filters = filters, count = false).items

    /** The library's genres, the ones with the most albums first. */
    suspend fun genres(parentId: UUID, byCount: Boolean = true): List<BaseItemDto> = io {
        val list = api.genreApi.getGenres(
            userId = userId, parentId = parentId, fields = listOf(ItemFields.ITEM_COUNTS), enableImages = false, sortBy = listOf(ItemSortBy.SORT_NAME),
        ).content.items
        if (byCount) list.sortedByDescending { it.albumCount ?: it.childCount ?: 0 } else list
    }

    /** A genre's newest album, for its tile. */
    suspend fun genreSample(parentId: UUID, genreId: UUID, skip: Set<UUID> = emptySet()): BaseItemDto? =
        query(parentId, listOf(BaseItemKind.MUSIC_ALBUM), listOf(ItemSortBy.DATE_CREATED), true, limit = 6, fields = emptyList(), genreIds = listOf(genreId), count = false)
            .items.let { list -> list.firstOrNull { it.id !in skip } ?: list.firstOrNull() }

    suspend fun genreMix(genreId: UUID, limit: Int = 120): List<BaseItemDto> = io {
        api.instantMixApi.getInstantMixFromMusicGenreById(
            id = genreId, userId = userId, limit = limit, fields = AudioApi.TrackFields, enableImageTypes = listOf(ImageType.PRIMARY), imageTypeLimit = 1, enableUserData = true,
        ).content.items
    }

    /** This profile's music playlists, newest first. */
    suspend fun audioPlaylists(limit: Int? = null): List<BaseItemDto> =
        io {
            api.libraryApi.getItems(
                userId = userId, includeItemTypes = listOf(BaseItemKind.PLAYLIST), recursive = true, mediaTypes = listOf(MediaType.AUDIO),
                sortBy = listOf(ItemSortBy.DATE_CREATED, ItemSortBy.SORT_NAME), sortOrder = listOf(SortOrder.DESCENDING), limit = limit,
                fields = listOf(ItemFields.CHILD_COUNT, ItemFields.DATE_CREATED), enableUserData = true,
            ).content.items
        }

    suspend fun playlistPreview(pl: BaseItemDto): PlaylistPreview {
        val tracks = runCatching { audio.playlistTracks(pl.id) }.getOrDefault(emptyList())
        val secs = tracks.sumOf { it.runTimeTicks ?: 0L } / 10_000_000.0
        return PlaylistPreview(pl, playlistCovers(tracks), playlistSub(tracks.size.takeIf { it > 0 } ?: pl.childCount ?: 0, secs))
    }

    suspend fun playlistPreviews(limit: Int? = null): List<PlaylistPreview> = coroutineScope {
        audioPlaylists(limit).map { async { playlistPreview(it) } }.awaitAll()
    }

    /* ── Home ── */

    /** Jump back in: albums whose songs were played lately, and the albums, playlists and artists the player played from. */
    suspend fun jumpBackIn(viewId: UUID, recent: List<Pair<String, Long>>, limit: Int = 8): List<BaseItemDto> {
        val played = query(viewId, listOf(BaseItemKind.AUDIO), listOf(ItemSortBy.DATE_PLAYED), true, limit = 80, fields = listOf(ItemFields.PARENT_ID),
            filters = listOf(ItemFilter.IS_PLAYED), images = emptyList(), count = false).items
        val seen = played.mapNotNull { tk -> tk.albumId?.let { it.toString() to lastPlayedAt(tk) } } + recent
        val ids = seen.sortedByDescending { it.second }.map { it.first }.distinct().mapNotNull { runCatching { UUID.fromString(dashed(it)) }.getOrNull() }.take(limit)
        return items(ids, MusicFields + ItemFields.CHILD_COUNT)
    }

    /** Artists by how often this profile played their songs. */
    suspend fun topArtists(viewId: UUID): List<TopArtist> {
        val songs = query(viewId, listOf(BaseItemKind.AUDIO), listOf(ItemSortBy.PLAY_COUNT), true, limit = 800, fields = listOf(ItemFields.PARENT_ID),
            filters = listOf(ItemFilter.IS_PLAYED), images = emptyList(), count = false).items
        val plays = LinkedHashMap<UUID, Int>()
        for (tk in songs) {
            val a = tk.albumArtists?.firstOrNull() ?: tk.artistItems?.firstOrNull() ?: continue
            plays[a.id] = (plays[a.id] ?: 0) + (tk.userData?.playCount?.takeIf { it > 0 } ?: 1)
        }
        val ids = plays.entries.sortedByDescending { it.value }.take(7).map { it.key }
        return items(ids, MusicFields).map { TopArtist(it, plays[it.id] ?: 0) }
    }

    suspend fun albumArtistsList(parentId: UUID, random: Boolean, limit: Int): List<BaseItemDto> = io {
        api.artistApi.getAlbumArtists(
            userId = userId, parentId = parentId, limit = limit, sortBy = listOf(if (random) ItemSortBy.RANDOM else ItemSortBy.SORT_NAME),
            fields = listOf(ItemFields.ITEM_COUNTS, ItemFields.CHILD_COUNT), enableImageTypes = listOf(ImageType.PRIMARY), imageTypeLimit = 1, enableUserData = true,
        ).content.items
    }

    suspend fun recentAlbums(viewId: UUID, limit: Int = 7): List<BaseItemDto> =
        query(viewId, listOf(BaseItemKind.MUSIC_ALBUM), listOf(ItemSortBy.DATE_CREATED, ItemSortBy.SORT_NAME), true, limit = limit, count = false).items

    suspend fun artistMix(a: BaseItemDto): Mix {
        val tracks = runCatching { audio.instantMix(a.id, 120) }.getOrDefault(emptyList())
        val others = tracks.flatMap { (it.artistItems ?: it.albumArtists).orEmpty().map { x -> x.id } }.filter { it != a.id }.toSet().size
        val name = a.name.orEmpty()
        return Mix(
            "artist-${a.id}", a.id.toString(), t("{name} radio", "name" to name),
            if (others > 0) tn(others, "{name} and {n} artist like them", "{name} and {n} artists like them", "name" to name) else t("{name} and similar artists", "name" to name),
            playlistCovers(tracks).take(2), tracks,
        )
    }

    suspend fun genreMixOf(g: BaseItemDto): Mix {
        val tracks = runCatching { genreMix(g.id, 120) }.getOrDefault(emptyList())
        val name = g.name.orEmpty()
        return Mix("genre-${g.id}", g.id.toString(), t("{genre} mix", "genre" to name), t("{genre} from across the server", "genre" to name), playlistCovers(tracks).take(2), tracks)
    }

    /** Made for you: the most played artist's radio, a favourites (or genre) mix and Something new, as the web builds them. */
    suspend fun mixes(viewId: UUID, top: List<TopArtist>): List<Mix> {
        val out = mutableListOf<Mix>()
        val lead = top.firstOrNull()?.artist ?: runCatching { albumArtistsList(viewId, random = true, limit = 1).firstOrNull() }.getOrNull()
        if (lead != null) out += artistMix(lead)
        val favs = songsOf(viewId, listOf(ItemSortBy.RANDOM), limit = 40, favorites = true)
        if (favs.isNotEmpty()) {
            out += Mix("favs", "favs-$viewId", t("Your favourites mix"), t("Songs you love, and more like them"), playlistCovers(favs).take(2), null)
        } else {
            runCatching { genres(viewId).firstOrNull() }.getOrNull()?.let { out += genreMixOf(it) }
        }
        val fresh = songsOf(viewId, listOf(ItemSortBy.RANDOM), limit = 120, filters = listOf(ItemFilter.IS_UNPLAYED))
        if (fresh.isNotEmpty()) out += Mix("new", "new-$viewId", t("Something new"), t("Albums on the server you’ve never played"), playlistCovers(fresh).take(2), fresh)
        return padCovers(out, viewId)
    }

    /** More mixes: the next artists' radios, then genre mixes, up to six. */
    suspend fun moreMixes(viewId: UUID, top: List<TopArtist>): List<Mix> {
        val list = mutableListOf<Mix>()
        for (x in top.drop(1).take(2)) list += artistMix(x.artist)
        for (g in runCatching { genres(viewId) }.getOrDefault(emptyList()).take(6 - list.size)) {
            if (list.size >= 6) break
            list += genreMixOf(g)
        }
        return padCovers(list, viewId)
    }

    /** The favourites mix: favourites with songs like them in between. */
    suspend fun favouritesMix(viewId: UUID): List<BaseItemDto> {
        val favs = songsOf(viewId, listOf(ItemSortBy.RANDOM), limit = 40, favorites = true)
        if (favs.isEmpty()) return emptyList()
        val like = runCatching { audio.instantMix(favs[0].id, 60) }.getOrDefault(emptyList())
        val ids = favs.map { it.id }.toSet()
        val rest = like.filter { it.id !in ids }
        return favs.flatMapIndexed { i, f -> listOfNotNull(f, rest.getOrNull(i)) }
    }

    /** Every mix card shows two covers: a mix from a single album borrows another album's. */
    private suspend fun padCovers(mixes: List<Mix>, viewId: UUID): List<Mix> {
        if (mixes.all { it.covers.size >= 2 }) return mixes
        val spare = runCatching {
            query(viewId, listOf(BaseItemKind.MUSIC_ALBUM), listOf(ItemSortBy.RANDOM), limit = 8, fields = emptyList(), count = false).items
        }.getOrDefault(emptyList())
        return mixes.mapIndexed { i, m ->
            if (m.covers.size >= 2) m else {
                val have = m.covers.map { it.albumId ?: it.id }.toSet()
                m.copy(covers = (m.covers + spare.filter { it.id !in have }.drop(i % 3)).take(2))
            }
        }
    }

    /** Six genres with an album each for their tiles (no album twice). */
    suspend fun genreSamples(viewId: UUID, limit: Int = 6): List<GenreSample> {
        val list = genres(viewId).take(limit)
        val samples = coroutineScope { list.map { g -> async { runCatching { genreSampleList(viewId, g.id) }.getOrDefault(emptyList()) } }.awaitAll() }
        val used = HashSet<UUID>()
        return list.mapIndexed { i, g ->
            val pick = samples[i].firstOrNull { it.id !in used } ?: samples[i].firstOrNull()
            pick?.let { used += it.id }
            GenreSample(g, pick)
        }
    }

    private suspend fun genreSampleList(viewId: UUID, genreId: UUID): List<BaseItemDto> =
        query(viewId, listOf(BaseItemKind.MUSIC_ALBUM), listOf(ItemSortBy.DATE_CREATED), true, limit = 6, fields = emptyList(), genreIds = listOf(genreId), count = false).items

    /* ── Album ── */

    /** More by the album's artist: their other releases (albums first), then albums they appear on. */
    suspend fun moreBy(album: BaseItemDto, artistId: UUID): List<Pair<BaseItemDto, String>> = coroutineScope {
        val sort = listOf(ItemSortBy.PRODUCTION_YEAR, ItemSortBy.PREMIERE_DATE, ItemSortBy.SORT_NAME)
        val own = async { query(null, listOf(BaseItemKind.MUSIC_ALBUM), sort, true, limit = 30, fields = ReleaseFields, albumArtistIds = listOf(artistId)).items }
        val all = async { query(null, listOf(BaseItemKind.MUSIC_ALBUM), sort, true, limit = 30, fields = ReleaseFields, artistIds = listOf(artistId)).items }
        val mine = own.await().map { it.id }.toSet()
        val releases = own.await().filter { it.id != album.id }.map { it to releaseOf(it) }
            .sortedBy { if (it.second == Release.Album) 0 else 1 }
            .map { (a, r) -> a to dot(releaseLabel(r), yearOf(a)) }
        val appears = all.await().filter { it.id !in mine && it.id != album.id }.map { it to dot(t("Appears on"), yearOf(it)) }
        (releases + appears).take(6)
    }

    suspend fun removeFromPlaylist(playlistId: UUID, entryId: String) = io {
        api.playlistApi.removeItemFromPlaylist(playlistId = playlistId.toString(), entryIds = listOf(entryId))
        Unit
    }

    /* ── Artist ── */

    /** The artist's songs, most played first (Popular and their plays). */
    suspend fun artistSongs(artistId: UUID): List<BaseItemDto> =
        query(null, listOf(BaseItemKind.AUDIO), listOf(ItemSortBy.PLAY_COUNT, ItemSortBy.SORT_NAME), true, limit = 400, fields = AudioApi.TrackFields, artistIds = listOf(artistId), count = false).items

    suspend fun discography(artistId: UUID): Discography = coroutineScope {
        val sort = listOf(ItemSortBy.PRODUCTION_YEAR, ItemSortBy.PREMIERE_DATE, ItemSortBy.SORT_NAME)
        val fields = ReleaseFields + ItemFields.GENRES
        val own = async { query(null, listOf(BaseItemKind.MUSIC_ALBUM), sort, true, limit = 200, fields = fields, albumArtistIds = listOf(artistId)).items }
        val all = async { query(null, listOf(BaseItemKind.MUSIC_ALBUM), sort, true, limit = 200, fields = fields, artistIds = listOf(artistId)).items }
        val videos = async {
            runCatching {
                query(null, listOf(BaseItemKind.MUSIC_VIDEO), listOf(ItemSortBy.PRODUCTION_YEAR, ItemSortBy.SORT_NAME), true, limit = 100, artistIds = listOf(artistId)).items
            }.getOrDefault(emptyList())
        }
        val mine = own.await()
        val ids = mine.map { it.id }.toSet()
        Discography(
            albums = mine.filter { releaseOf(it) == Release.Album },
            singles = mine.filter { releaseOf(it) != Release.Album }.sortedByDescending { it.productionYear ?: 0 },
            appears = all.await().filter { it.id !in ids },
            videos = videos.await(),
        )
    }

    suspend fun similarArtists(artistId: UUID): List<BaseItemDto> = io {
        runCatching {
            api.libraryApi.getSimilarArtists(itemId = artistId, userId = userId, limit = 7, fields = listOf(ItemFields.ITEM_COUNTS, ItemFields.CHILD_COUNT, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO)).content.items
        }.getOrDefault(emptyList())
    }

    /* ── Play on another device ── */

    suspend fun castTargets(deviceId: String?): List<CastTarget> = io {
        api.sessionApi.getSessions(controllableByUserId = userId, activeWithinSeconds = 960).content
            .filter { it.deviceId != deviceId && it.supportsRemoteControl && it.playableMediaTypes.orEmpty().isNotEmpty() }
            .map { s: SessionInfoDto -> CastTarget(s.id.orEmpty(), s.deviceName ?: s.client ?: t("Device"), listOfNotNull(s.client, s.userName).joinToString(" · ")) }
    }

    suspend fun castTo(sessionId: String, ids: List<UUID>, startTicks: Long) = io {
        api.sessionApi.play(sessionId = sessionId, playCommand = PlayCommand.PLAY_NOW, itemIds = ids.take(100), startPositionTicks = startTicks)
        Unit
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        val MusicFields = listOf(
            ItemFields.PRIMARY_IMAGE_ASPECT_RATIO, ItemFields.GENRES, ItemFields.CHILD_COUNT, ItemFields.DATE_CREATED, ItemFields.SORT_NAME,
        )
        val ReleaseFields = listOf(ItemFields.CHILD_COUNT, ItemFields.SORT_NAME, ItemFields.TAGS)

        /** "7e64e319657a9516ec78490da03edccb" → a UUID string. */
        fun dashed(id: String): String =
            if (id.length == 32 && '-' !in id) "${id.substring(0, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}" else id
    }
}
