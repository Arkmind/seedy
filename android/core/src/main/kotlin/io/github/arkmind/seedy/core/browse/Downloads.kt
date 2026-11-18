package io.github.arkmind.seedy.core.browse

import io.github.arkmind.seedy.core.format.isVirtual
import io.github.arkmind.seedy.core.manage.DeleteViewModel
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType

/** Saving a movie's or an episode's original file on the phone (the web's Download in the More menu). */
object Downloads {
    private val Unsafe = Regex("[\\\\/:*?\"<>|]")

    /** [name] as a file or folder name the phone accepts. */
    fun safe(name: String): String = name.replace(Unsafe, "_").trim()

    /** A movie, episode or other video that has a file on the server. */
    fun isVideoFile(item: BaseItemDto): Boolean = item.mediaType == MediaType.VIDEO && item.isFolder != true && !item.isVirtual

    /**
     * A season with episodes. Jellyfin calls a season virtual when it has no folder of its own (episodes right in the
     * show's folder), so that says nothing about its files; seasons that are really missing don't come with episodes.
     */
    fun hasEpisodes(season: BaseItemDto): Boolean = (season.childCount ?: 1) > 0

    /** The server's file name ("Sintel (2010).mkv"), else the title and its container. */
    fun fileName(item: BaseItemDto): String {
        item.path?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { '.' in it.trim('.') }?.let { return safe(it) }
        val ext = (item.mediaSources?.firstOrNull()?.container ?: item.container)?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() } ?: "mkv"
        return safe(item.name.orEmpty()).ifEmpty { item.id.toString() } + ".$ext"
    }

    /** Where it goes under the phone's Movies: a movie on its own, an episode in its show's folder. */
    fun path(item: BaseItemDto): String {
        val show = item.seriesName?.let(::safe)?.takeIf { item.type == BaseItemKind.EPISODE && it.isNotEmpty() }
        return listOfNotNull(show, fileName(item)).joinToString("/")
    }

    /** The Download line's second line: "MKV · 4.2 GB". */
    fun sub(item: BaseItemDto): String? {
        val source = item.mediaSources?.firstOrNull()
        val parts = listOfNotNull(
            (source?.container ?: item.container)?.substringBefore(',')?.trim()?.uppercase()?.takeIf { it.isNotEmpty() },
            source?.size?.takeIf { it > 0 }?.let { DeleteViewModel.fileSize(it) },
        )
        return parts.joinToString(" · ").ifEmpty { null }
    }
}
