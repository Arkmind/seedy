package io.github.arkmind.seedy.mobile.manage

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.arkmind.seedy.core.manage.AddTarget
import io.github.arkmind.seedy.core.manage.ListKind
import io.github.arkmind.seedy.core.manage.ManageApi
import io.github.arkmind.seedy.core.session.UserSession
import io.github.arkmind.seedy.mobile.browse.BottomSheet
import io.github.arkmind.seedy.mobile.settings.SheetHead
import io.github.arkmind.seedy.ui.icons.IconDef
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

/** A line of the More menu; [group] starts a named group ("Manage"), [line] draws the separator above it. */
class MenuEntry(
    val icon: IconDef,
    val label: String,
    val sub: String? = null,
    val danger: Boolean = false,
    val more: Boolean = false,
    val group: String? = null,
    val line: Boolean = false,
    val run: () -> Unit,
)

/** The More menu (board DetailsMore) as a phone sheet: the title's name over its lines. */
@Composable
fun ItemMenuSheet(title: String, entries: List<MenuEntry>, onDismiss: () -> Unit) {
    BottomSheet(onDismiss) {
        SheetHead(title, gap = 4.dp)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp, bottom = 24.dp)) {
            for (e in entries) {
                if (e.line) MenuLine()
                if (e.group != null) MenuGroup(e.group)
                MenuRow(e.icon, e.label, { onDismiss(); e.run() }, e.sub, e.danger, e.more)
            }
        }
    }
}

/** What a person may do with titles here. */
data class ManagePerms(val admin: Boolean, val collections: Boolean) {
    companion object {
        fun of(session: UserSession): ManagePerms = ManageApi(session).let { ManagePerms(it.isAdmin, it.canManageCollections) }
    }
}

/** Can [item] go in a playlist (the things Jellyfin plays)? */
fun playlistable(item: BaseItemDto): Boolean = item.type in setOf(
    BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.SEASON, BaseItemKind.EPISODE, BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO,
    BaseItemKind.MUSIC_ALBUM, BaseItemKind.AUDIO, BaseItemKind.BOX_SET, BaseItemKind.TRAILER,
)

/** Which management sheet is open over a page. */
@Stable
class ManageSheets {
    var open by mutableStateOf<String?>(null)
    var target by mutableStateOf<AddTarget?>(null)
    var continuing by mutableStateOf(false)

    fun show(sheet: String, target: AddTarget, continuing: Boolean = false) {
        this.target = target
        this.continuing = continuing
        open = sheet
    }

    fun close() {
        open = null
    }
}

@Composable
fun rememberManageSheets(): ManageSheets = remember { ManageSheets() }

/**
 * Draws the open sheet: Add to playlist / collection, New playlist / collection, Delete. [onDeleted] runs once the
 * titles are gone; [say] shows what happened.
 */
@Composable
fun ManageSheetHost(sheets: ManageSheets, say: (String) -> Unit, onDeleted: (List<String>, String) -> Unit) {
    val target = sheets.target ?: return
    when (sheets.open) {
        "playlist" -> AddToSheet(ListKind.Playlist, target, sheets::close) { sheets.open = "new-playlist" }
        "collection" -> AddToSheet(ListKind.Collection, target, sheets::close) { sheets.open = "new-collection" }
        "new-playlist" -> NewPlaylistSheet(target, sheets::close, say)
        "new-collection" -> NewCollectionSheet(target, sheets::close, say)
        "delete" -> DeleteSheet(target.ids, sheets::close, onDeleted, sheets.continuing, key = target)
    }
}
