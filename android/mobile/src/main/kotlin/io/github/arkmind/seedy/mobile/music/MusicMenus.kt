package io.github.arkmind.seedy.mobile.music

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.manage.AddTarget
import io.github.arkmind.seedy.core.music.CastTarget
import io.github.arkmind.seedy.core.music.MusicApi
import io.github.arkmind.seedy.core.session.UserSession
import io.github.arkmind.seedy.mobile.audio.LocalAudio
import io.github.arkmind.seedy.mobile.audio.miniBarSpace
import io.github.arkmind.seedy.mobile.browse.BottomSheet
import io.github.arkmind.seedy.mobile.manage.ItemMenuSheet
import io.github.arkmind.seedy.mobile.manage.ManageSheetHost
import io.github.arkmind.seedy.mobile.manage.ManageSheets
import io.github.arkmind.seedy.mobile.manage.MenuEntry
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.nav.MobileNav
import io.github.arkmind.seedy.mobile.nav.Route
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.settings.SheetHead
import io.github.arkmind.seedy.player.audio.AudioEngine
import io.github.arkmind.seedy.player.audio.PlayOptions
import io.github.arkmind.seedy.player.audio.Source
import io.github.arkmind.seedy.player.audio.SourceKind
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.LocalSession
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

/** A song's "…" menu: the song, the page it opens on (no link back to it) and the page's own lines. */
data class SongMenu(val track: BaseItemDto, val pageId: UUID? = null, val extra: List<MenuEntry> = emptyList())

/**
 * What the music pages do with the player and the people around it: playing lists, queueing (with the web's toasts),
 * a song's menu, Add to playlist, Play on another device, downloads. One per page ([rememberMusicController]); its
 * sheets and toast are drawn by [MusicOverlays].
 */
@Stable
class MusicController(
    val audio: AudioEngine?,
    val nav: MobileNav,
    private val session: UserSession?,
    private val scope: CoroutineScope,
    private val context: Context?,
) {
    var message by mutableStateOf<String?>(null)
    var menu by mutableStateOf<SongMenu?>(null)
    val sheets = ManageSheets()
    var castOpen by mutableStateOf(false)
    var targets by mutableStateOf<List<CastTarget>?>(null)
    private var castFallback: List<UUID> = emptyList()
    private val api = session?.let { MusicApi(it) }

    fun say(text: String) {
        message = text
    }

    fun play(tracks: List<BaseItemDto>, index: Int, source: Source, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return say(t("Nothing to play here yet"))
        audio?.playTracks(tracks, index, PlayOptions(shuffle = shuffle, source = source))
    }

    /** Play next ([next]) or Add to queue, and say so. */
    fun queue(tracks: List<BaseItemDto>, next: Boolean, name: String) {
        if (tracks.isEmpty()) return
        audio?.addToQueue(tracks, next)
        say(if (next) t("{name} plays next", "name" to name) else t("Added {name} to the queue", "name" to name))
    }

    /** Song radio: Jellyfin's instant mix from the song. */
    fun songRadio(track: BaseItemDto) {
        val a = api ?: return
        scope.launch {
            runCatching { a.audio.instantMix(track.id) }
                .onSuccess { if (it.isEmpty()) say(t("Couldn’t start the mix")) else play(it, 0, Source(SourceKind.Mix, track.id.toString(), t("{name} radio", "name" to track.name.orEmpty()))) }
                .onFailure { say(t("Couldn’t start the mix")) }
        }
    }

    fun toggleFavorite(track: BaseItemDto, onDone: (Boolean) -> Unit = {}) {
        val on = track.userData?.isFavorite != true
        val engine = audio
        if (engine != null) engine.toggleFavorite(track, on) else api?.let { a -> scope.launch { runCatching { a.setFavorite(track.id, on) } } }
        onDone(on)
    }

    fun addToPlaylist(items: List<BaseItemDto>, name: String) {
        if (items.isEmpty()) return
        sheets.show("playlist", AddTarget(items.map { it.id.toString() }, name, items.singleOrNull()?.type?.serialName))
    }

    fun newPlaylist() = sheets.show("new-playlist", AddTarget(emptyList(), ""))

    fun openMenu(track: BaseItemDto, pageId: UUID? = null, extra: List<MenuEntry> = emptyList()) {
        menu = SongMenu(track, pageId, extra)
    }

    /** "Play on another device": the queue (or [fallback], the page's songs, when nothing plays) goes to the picked app. */
    fun openCast(fallback: List<UUID> = emptyList()) {
        castFallback = fallback
        castOpen = true
        targets = null
        val a = api ?: return run { targets = emptyList() }
        scope.launch { targets = runCatching { a.castTargets(session?.api?.deviceInfo?.id) }.getOrDefault(emptyList()) }
    }

    fun castTo(target: CastTarget) {
        castOpen = false
        val a = api ?: return
        val s = audio?.state?.value
        val cur = s?.current
        val ids = if (cur != null) (listOf(cur) + s.upNext).map { it.track.id } else castFallback
        if (ids.isEmpty()) return
        val start = if (cur != null) ((audio?.position() ?: 0.0) * 10_000_000).toLong() else 0L
        scope.launch {
            runCatching { a.castTo(target.id, ids, start) }
                .onSuccess {
                    audio?.pause()
                    say(t("Playing on {device}", "device" to target.name))
                }
                .onFailure { say(t("Couldn't reach {device}", "device" to target.name)) }
        }
    }

    /** Every song, one file after the other, into the phone's Music folder (the web's Download album). */
    fun download(tracks: List<BaseItemDto>, folder: String) {
        val ctx = context ?: return
        val s = session ?: return
        val manager = ctx.getSystemService(DownloadManager::class.java) ?: return
        val base = s.api.baseUrl.orEmpty().trimEnd('/')
        val token = s.api.accessToken.orEmpty()
        val dir = folder.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "Seedy" }
        for (tk in tracks) {
            val ext = tk.mediaSources?.firstOrNull()?.container?.substringBefore(',')?.takeIf { it.isNotBlank() } ?: "mp3"
            val file = listOfNotNull(tk.indexNumber?.toString()?.padStart(2, '0'), tk.name).joinToString(" ").replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".$ext"
            val request = DownloadManager.Request(Uri.parse("$base/Items/${tk.id}/Download?ApiKey=${Uri.encode(token)}"))
                .setTitle(tk.name.orEmpty())
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            if (Build.VERSION.SDK_INT >= 29) request.setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "$dir/$file")
            else request.setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_MUSIC, "$dir/$file")
            runCatching { manager.enqueue(request) }
        }
        say(t("Downloading {n} songs", "n" to tracks.size))
    }
}

@Composable
fun rememberMusicController(): MusicController {
    val audio = LocalAudio.current
    val nav = LocalMobileNav.current
    val session = LocalSession.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return remember(audio, nav, session) { MusicController(audio, nav, session, scope, context) }
}

/** A song's menu lines (web TrackMenu): queueing, its radio, playlists, favourite, the page's own, its album and artist. */
fun songEntries(c: MusicController, m: SongMenu): List<MenuEntry> = buildList {
    val tk = m.track
    val name = tk.name.orEmpty()
    add(MenuEntry(MusicIcons.QueueNext, t("Play next"), run = { c.queue(listOf(tk), true, name) }))
    add(MenuEntry(MusicIcons.Queue, t("Add to queue"), run = { c.queue(listOf(tk), false, name) }))
    add(MenuEntry(MusicIcons.Mix, t("Song radio"), run = { c.songRadio(tk) }))
    add(MenuEntry(MusicIcons.PlaylistAdd, t("Add to playlist"), more = true, run = { c.addToPlaylist(listOf(tk), name) }))
    val fav = tk.userData?.isFavorite == true
    add(MenuEntry(if (fav) MusicIcons.HeartOn else MusicIcons.Heart, if (fav) t("Remove from favorites") else t("Add to favorites"), run = { c.toggleFavorite(tk) }))
    addAll(m.extra)
    val artist = (tk.artistItems?.firstOrNull() ?: tk.albumArtists?.firstOrNull())?.takeIf { it.id != m.pageId }
    val album = tk.albumId?.takeIf { it != m.pageId }
    if (album != null) add(MenuEntry(MusicIcons.Album, t("Go to album"), line = true, run = { c.nav.go(Route.Album(album)) }))
    if (artist != null) add(MenuEntry(MusicIcons.Artist, t("Go to artist"), line = album == null, run = { c.nav.go(Route.Artist(artist.id)) }))
}

/** The page's sheets (a song's menu, Add to playlist, Play on) and its toast, over the page's foot. [onListsChanged]: a playlist was made or filled. */
@Composable
fun BoxScope.MusicOverlays(c: MusicController, onListsChanged: () -> Unit = {}) {
    c.menu?.let { m -> ItemMenuSheet(m.track.name.orEmpty(), songEntries(c, m)) { c.menu = null } }
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(c.sheets.open) {
        if (c.sheets.open != null) wasOpen = true else if (wasOpen) { wasOpen = false; onListsChanged() }
    }
    ManageSheetHost(c.sheets, say = c::say, onDeleted = { _, _ -> })
    if (c.castOpen) CastSheet(c.targets, c::castTo) { c.castOpen = false }
    Toast(c.message, { c.message = null }, Modifier.align(Alignment.BottomCenter).padding(bottom = miniBarSpace() + 8.dp))
}

/** Play on: this phone (ticked), then the other Jellyfin apps that take commands. */
@Composable
fun CastSheet(targets: List<CastTarget>?, onPick: (CastTarget) -> Unit, onDismiss: () -> Unit) {
    BottomSheet(onDismiss) {
        SheetHead(t("Play on"), gap = 4.dp)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp, bottom = 24.dp)) {
            CastRow(t("This device"), t("Play here"), on = true, onClick = onDismiss)
            when {
                targets == null -> SText(t("Looking for devices…"), Modifier.padding(12.dp), size = 13.sp, color = SeedyColors.Text3)
                targets.isEmpty() -> SText(t("No other Jellyfin apps are open"), Modifier.padding(12.dp), size = 13.sp, color = SeedyColors.Text3)
                else -> for (x in targets) CastRow(x.name, x.sub, on = false) { onPick(x) }
            }
        }
    }
}

@Composable
private fun CastRow(name: String, sub: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(RoundedCornerShape(12.dp)).tap(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(SeedyColors.Surface), contentAlignment = Alignment.Center) {
            SIcon(MusicIcons.Cast, size = 18.dp, color = if (on) LocalAccent.current else SeedyColors.Text2)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(name, size = 15.sp, weight = 600, color = SeedyColors.Text, maxLines = 1)
            if (sub.isNotEmpty()) SText(sub, size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
        }
        if (on) SIcon(MusicIcons.Check, size = 18.dp, color = LocalAccent.current)
    }
}
