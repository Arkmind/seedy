package io.github.arkmind.seedy.core.browse

import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder

/* A library's tabs, sorts and filters, as the web's Library screen has them (web/src/screens/browse/Library.tsx). */

/** A library's views ("All movies", "Suggestions"…). Labels are English, for t(). */
enum class LibraryTab { All, Suggestions, Favorites, Collections, Genres }

fun tabsFor(type: CollectionType?): List<Pair<LibraryTab, String>> = when (type) {
    CollectionType.MOVIES -> listOf(
        LibraryTab.All to "All movies", LibraryTab.Suggestions to "Suggestions", LibraryTab.Favorites to "Favorites",
        LibraryTab.Collections to "Collections", LibraryTab.Genres to "Genres",
    )
    CollectionType.TVSHOWS -> listOf(
        LibraryTab.All to "All shows", LibraryTab.Suggestions to "Suggestions", LibraryTab.Favorites to "Favorites", LibraryTab.Genres to "Genres",
    )
    else -> listOf(LibraryTab.All to "All", LibraryTab.Favorites to "Favorites")
}

/** What a library lists. */
fun typesFor(type: CollectionType?): List<BaseItemKind> = when (type) {
    CollectionType.MOVIES -> listOf(BaseItemKind.MOVIE)
    CollectionType.TVSHOWS -> listOf(BaseItemKind.SERIES)
    CollectionType.MUSIC -> listOf(BaseItemKind.MUSIC_ALBUM)
    CollectionType.BOXSETS -> listOf(BaseItemKind.BOX_SET)
    CollectionType.MUSICVIDEOS -> listOf(BaseItemKind.MUSIC_VIDEO)
    CollectionType.HOMEVIDEOS -> listOf(BaseItemKind.VIDEO, BaseItemKind.PHOTO)
    CollectionType.PLAYLISTS -> listOf(BaseItemKind.PLAYLIST)
    CollectionType.BOOKS -> listOf(BaseItemKind.BOOK)
    CollectionType.LIVETV -> listOf(BaseItemKind.TV_CHANNEL)
    else -> listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO, BaseItemKind.BOX_SET)
}

/** What Shuffle plays: episodes for shows. */
fun playableTypesFor(type: CollectionType?): List<BaseItemKind> = when (type) {
    CollectionType.TVSHOWS -> listOf(BaseItemKind.EPISODE)
    CollectionType.MUSIC -> listOf(BaseItemKind.AUDIO)
    CollectionType.BOXSETS -> listOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.VIDEO)
    else -> typesFor(type)
}

/** "248 movies", "61 shows", "18 collections"… for a library tile and its page. */
fun countLabel(type: CollectionType?, n: Int): String = when (type) {
    CollectionType.MOVIES -> tn(n, "{n} movie", "{n} movies")
    CollectionType.TVSHOWS -> tn(n, "{n} show", "{n} shows")
    CollectionType.BOXSETS -> tn(n, "{n} collection", "{n} collections")
    CollectionType.HOMEVIDEOS -> tn(n, "{n} video and photo", "{n} videos and photos")
    CollectionType.PLAYLISTS -> tn(n, "{n} playlist", "{n} playlists")
    CollectionType.MUSIC -> tn(n, "{n} album", "{n} albums")
    CollectionType.LIVETV -> tn(n, "{n} channel", "{n} channels")
    else -> tn(n, "{n} title", "{n} titles")
}

enum class Sort(val label: String, val fields: List<ItemSortBy>, val descending: Boolean) {
    DateAdded("Date added", listOf(ItemSortBy.DATE_CREATED, ItemSortBy.SORT_NAME), true),
    Title("Title", listOf(ItemSortBy.SORT_NAME), false),
    Released("Release date", listOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.PRODUCTION_YEAR, ItemSortBy.SORT_NAME), true),
    Rating("Rating", listOf(ItemSortBy.COMMUNITY_RATING, ItemSortBy.SORT_NAME), true),
    Runtime("Runtime", listOf(ItemSortBy.RUNTIME, ItemSortBy.SORT_NAME), false),
    Played("Recently played", listOf(ItemSortBy.DATE_PLAYED, ItemSortBy.SORT_NAME), true),
    Random("Random", listOf(ItemSortBy.RANDOM), false),
}

enum class Flag(val label: String) {
    Unwatched("Unwatched"), Watched("Watched"), InProgress("In progress"), Favorites("Favorites"),
    UltraHd("4K"), Hd("HD"), Subtitles("Has subtitles"),
}

/** The filters sheet's choices. */
data class Filters(
    val flags: Set<Flag> = emptySet(),
    val genres: List<String> = emptyList(),
    val decades: List<Int> = emptyList(),
    val ratings: List<String> = emptyList(),
) {
    val count: Int get() = flags.size + genres.size + decades.size + ratings.size

    /** "4K · Thriller": what's on, for the line next to the tools. */
    val labels: List<String> get() = flags.map { t(it.label) } + genres + decades.map { "${it}s" } + ratings

    fun toggle(flag: Flag): Filters {
        var next = if (flag in flags) flags - flag else flags + flag
        // Watched and Unwatched exclude each other.
        if (flag == Flag.Watched && flag in next) next = next - Flag.Unwatched
        if (flag == Flag.Unwatched && flag in next) next = next - Flag.Watched
        return copy(flags = next)
    }

    fun toggleGenre(g: String) = copy(genres = if (g in genres) genres - g else genres + g)
    fun toggleDecade(d: Int) = copy(decades = if (d in decades) decades - d else decades + d)
    fun toggleRating(r: String) = copy(ratings = if (r in ratings) ratings - r else ratings + r)

    fun applyTo(q: ItemsQuery): ItemsQuery {
        val f = buildList {
            if (Flag.Unwatched in flags) add(ItemFilter.IS_UNPLAYED)
            if (Flag.Watched in flags) add(ItemFilter.IS_PLAYED)
            if (Flag.InProgress in flags) add(ItemFilter.IS_RESUMABLE)
            if (Flag.Favorites in flags) add(ItemFilter.IS_FAVORITE)
        }
        return q.copy(
            filters = q.filters + f,
            is4k = if (Flag.UltraHd in flags) true else q.is4k,
            isHd = if (Flag.Hd in flags) true else q.isHd,
            hasSubtitles = if (Flag.Subtitles in flags) true else q.hasSubtitles,
            genres = genres.ifEmpty { q.genres },
            years = decades.flatMap { d -> (d until d + 10).toList() }.ifEmpty { q.years },
            officialRatings = ratings.ifEmpty { q.officialRatings },
        )
    }
}

/** What a tab lists, before sorting and filters. */
fun baseQuery(view: BaseItemDto, tab: LibraryTab): ItemsQuery = when (tab) {
    LibraryTab.Collections -> ItemsQuery(includeTypes = listOf(BaseItemKind.BOX_SET), recursive = true)
    LibraryTab.Favorites -> ItemsQuery(parentId = view.id, includeTypes = typesFor(view.collectionType), isFavorite = true)
    else -> ItemsQuery(parentId = view.id, includeTypes = typesFor(view.collectionType))
}

fun ItemsQuery.sorted(sort: Sort, descending: Boolean): ItemsQuery =
    copy(sortBy = sort.fields, sortOrder = if (descending) SortOrder.DESCENDING else SortOrder.ASCENDING)

/** The A–Z rail's letters ("#" is numbers and symbols). */
const val Letters = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ"

/** The rail letter a title files under. */
fun letterOf(item: BaseItemDto): Char {
    val c = (item.sortName ?: item.name).orEmpty().firstOrNull()?.uppercaseChar() ?: '#'
    return if (c in 'A'..'Z') c else '#'
}
