package io.github.arkmind.seedy.core.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.admin.errorText
import io.github.arkmind.seedy.core.format.join
import io.github.arkmind.seedy.core.i18n.I18n
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.NumberFormat

/*
 * Delete (board ItemDelete): what goes (each season, the video files, the artwork and subtitles around them, and the
 * folder), whether Seerr forgets it too, a warning when Sonarr or Radarr would fetch it again, and the title typed out
 * to confirm. Seedy's preview says all that; without the plugin the sizes come from the media sources.
 */

/** A line of the breakdown: "Season 1", "10 episodes · 2160p HEVC", "214 GB". */
data class DeleteLine(val title: String, val sub: String, val size: String)

data class DeleteState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    /** "Delete Northbound?" */
    val title: String = "",
    /** "2 seasons · 252 GB" */
    val sub: String = "",
    val text: String = "",
    val lines: List<DeleteLine> = emptyList(),
    val paths: List<String> = emptyList(),
    /** Seerr tracks it and this person may clear it there. */
    val seerr: Boolean = false,
    val clearSeerr: Boolean = true,
    /** "Sonarr still monitors Northbound, so it may download it again. …" */
    val warning: String? = null,
    /** What to type to confirm (the title, or "delete" for several). */
    val word: String = "",
    val typed: String = "",
    /** "252 GB" on the button. */
    val size: String = "",
    val busy: Boolean = false,
    val error: String? = null,
) {
    val confirmed: Boolean get() = word.isNotEmpty() && typed.trim().equals(word.trim(), ignoreCase = true)
    val canDelete: Boolean get() = !loading && !failed && confirmed && !busy
}

interface DeleteActions {
    fun type(text: String) {}
    fun toggleSeerr() {}
    fun retry() {}
    /** Deletes; [onDone] gets the ids that went and what to say. */
    fun delete(onDone: (ids: List<String>, message: String) -> Unit) {}
}

/** [ids] are what goes; [continuing] says a series still airs (its last season is "so far"). */
class DeleteViewModel(session: UserSession, private val ids: List<String>, private val continuing: Boolean = false) : ViewModel(), DeleteActions {
    private val api = ManageApi(session)
    private val _state = MutableStateFlow(DeleteState())
    val state: StateFlow<DeleteState> = _state.asStateFlow()
    private var preview: DeletePreview? = null

    init {
        load()
    }

    private fun load() {
        _state.update { it.copy(loading = true, failed = false) }
        viewModelScope.launch {
            val result = runCatching { api.deletePreview(ids) ?: fallback() }
            result.onSuccess { p ->
                preview = p
                _state.update { describe(p, continuing).copy(typed = it.typed, clearSeerr = it.clearSeerr) }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                _state.update { it.copy(loading = false, failed = true, error = t("Couldn’t look at the files. {error}", "error" to errorText(e))) }
            }
        }
    }

    /** No plugin: the files Jellyfin knows of, from the media sources (a series' from its episodes). */
    private suspend fun fallback(): DeletePreview {
        val items = api.facts(ids).map { f ->
            if (f.type == "Series" || f.type == "Season") {
                val eps = api.episodes(f.id)
                val seasons = eps.groupBy { it.seasonId ?: "" }.map { (sid, list) ->
                    val video = list.firstNotNullOfOrNull { e -> e.mediaSources.firstNotNullOfOrNull { s -> s.mediaStreams.firstOrNull { it.type == "Video" } } }
                    DeleteGroup(
                        kind = "season", id = sid, name = list.first().seasonName, index = list.first().parentIndexNumber, episodes = list.size,
                        files = list.sumOf { it.mediaSources.size }, bytes = list.sumOf { e -> e.mediaSources.sumOf { it.size ?: 0 } },
                        height = video?.height, codec = video?.codec,
                    )
                }.sortedBy { it.index ?: 0 }
                DeleteItem(f.id, f.name, f.type, f.canDelete, seasons.sumOf { it.bytes }, listOfNotNull(f.path), seasons)
            } else {
                val video = f.mediaSources.firstNotNullOfOrNull { s -> s.mediaStreams.firstOrNull { it.type == "Video" } }
                val bytes = f.mediaSources.sumOf { it.size ?: 0 }
                val group = DeleteGroup("media", episodes = 1, files = f.mediaSources.size, bytes = bytes, height = video?.height, codec = video?.codec)
                DeleteItem(f.id, f.name, f.type, f.canDelete, bytes, listOfNotNull(f.path ?: f.mediaSources.firstOrNull()?.path), listOf(group))
            }
        }
        return DeletePreview(items, items.sumOf { it.bytes })
    }

    override fun type(text: String) = _state.update { it.copy(typed = text) }

    override fun toggleSeerr() = _state.update { it.copy(clearSeerr = !it.clearSeerr) }

    override fun retry() = load()

    override fun delete(onDone: (ids: List<String>, message: String) -> Unit) {
        val s = _state.value
        val p = preview ?: return
        if (!s.canDelete) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val gone = p.items.map { it.id }.ifEmpty { ids }
            val result = runCatching { api.delete(gone) }
            if (result.isFailure) {
                val e = result.exceptionOrNull()!!
                if (e is CancellationException) throw e
                _state.update { it.copy(busy = false, error = t("Couldn’t delete it. {error}", "error" to errorText(e))) }
                return@launch
            }
            var seerrFailed = false
            if (s.seerr && s.clearSeerr) {
                for (m in p.items.mapNotNull { it.seerr }) {
                    if (runCatching { api.clearSeerr(m) }.isFailure) seerrFailed = true
                }
            }
            val name = p.items.singleOrNull()?.name
            val message = when {
                seerrFailed -> t("Deleted, but Seerr couldn’t forget it")
                name != null -> t("Deleted {name}", "name" to name)
                else -> tn(gone.size, "Deleted {n} title", "Deleted {n} titles")
            }
            _state.update { it.copy(busy = false) }
            onDone(gone, message)
        }
    }

    companion object {
        /** The sheet's text from a preview. */
        fun describe(p: DeletePreview, continuing: Boolean = false): DeleteState {
            val one = p.items.singleOrNull()
            val unit = sizeUnit(p.bytes)
            val size = fileSize(p.bytes, unit)
            val lines = if (one != null) {
                val seasons = one.groups.filter { it.kind == "season" }
                val last = seasons.maxByOrNull { it.index ?: 0 }
                one.groups.map { g ->
                    when (g.kind) {
                        "season" -> DeleteLine(
                            g.name ?: t("Season {n}", "n" to (g.index ?: 0)),
                            join(
                                if (continuing && g === last) tn(g.episodes, "{n} episode so far", "{n} episodes so far") else tn(g.episodes, "{n} episode", "{n} episodes"),
                                quality(g.height, g.codec),
                            ),
                            fileSize(g.bytes, unit),
                        )
                        "extras" -> DeleteLine(t("Artwork, subtitles and NFO files"), tn(g.files, "{n} file", "{n} files"), fileSize(g.bytes, unit))
                        else -> DeleteLine(if (g.files <= 1) t("Video file") else tn(g.files, "{n} video file", "{n} video files"), quality(g.height, g.codec), fileSize(g.bytes, unit))
                    }
                }
            } else {
                p.items.map { item ->
                    val seasons = item.groups.count { it.kind == "season" }
                    val video = item.groups.firstOrNull { it.kind != "extras" }
                    DeleteLine(item.name, if (seasons > 0) join(tn(seasons, "{n} season", "{n} seasons"), quality(video?.height, video?.codec)) else quality(video?.height, video?.codec), fileSize(item.bytes, unit))
                }
            }
            val kind = when {
                one == null -> tn(p.items.size, "{n} title", "{n} titles")
                one.type == "Series" -> one.groups.count { it.kind == "season" }.let { tn(it, "{n} season", "{n} seasons") }
                one.type == "Movie" -> t("Movie")
                one.type == "Episode" -> t("Episode")
                else -> null
            }
            val monitored = p.items.firstOrNull { it.seerr?.monitored == true }
            return DeleteState(
                loading = false,
                title = if (one != null) t("Delete {name}?", "name" to one.name) else tn(p.items.size, "Delete {n} title?", "Delete {n} titles?"),
                sub = join(kind, size),
                text = if (one != null) t("This deletes the files from the server’s disk, and everyone loses their watch progress for it. It can’t be undone.")
                else t("This deletes the files from the server’s disk, and everyone loses their watch progress for them. It can’t be undone."),
                lines = lines,
                paths = p.items.flatMap { it.paths }.distinct(),
                seerr = p.items.any { it.seerr != null },
                warning = monitored?.let { m ->
                    val arr = m.seerr?.arr ?: if (m.seerr?.mediaType == "tv") "Sonarr" else "Radarr"
                    t("{arr} still monitors {name}, so it may download it again. Unmonitor it in {arr} if you want it gone for good.", "arr" to arr, "name" to m.name)
                },
                word = one?.name ?: t("delete"),
                size = size,
            )
        }

        /** "2160p HEVC", "720p H.264". */
        fun quality(height: Int?, codec: String?): String = listOfNotNull(
            height?.takeIf { it > 0 }?.let { "${it}p" },
            codec?.takeIf { it.isNotBlank() }?.let { codecName(it) },
        ).joinToString(" ")

        private fun codecName(codec: String): String = when (codec.lowercase()) {
            "hevc", "h265" -> "HEVC"
            "h264", "avc" -> "H.264"
            "av1" -> "AV1"
            "vp9" -> "VP9"
            "mpeg2video" -> "MPEG-2"
            else -> codec.uppercase()
        }

        private val units = listOf("B", "KB", "MB", "GB", "TB", "PB")

        /** The unit a total reads best in (decimal, as disks are sold): 252 GB, 3.5 MB. */
        fun sizeUnit(bytes: Long): Int {
            var v = bytes.toDouble()
            var i = 0
            while (v >= 1000 && i < units.size - 1) {
                v /= 1000
                i++
            }
            return i
        }

        /** [bytes] in [unit] ([sizeUnit]): whole from 10 up, one decimal under ("214 GB", "0.2 GB"). */
        fun fileSize(bytes: Long, unit: Int = sizeUnit(bytes)): String {
            var v = bytes.toDouble()
            repeat(unit) { v /= 1000 }
            val digits = if (v >= 10 || unit == 0) 0 else 1
            val f = NumberFormat.getInstance(I18n.locale).apply {
                minimumFractionDigits = digits
                maximumFractionDigits = digits
            }
            if (bytes > 0 && digits == 1 && v < 0.05) return "< ${f.format(0.1)} ${units[unit]}"
            return "${f.format(v)} ${units[unit]}"
        }
    }
}
