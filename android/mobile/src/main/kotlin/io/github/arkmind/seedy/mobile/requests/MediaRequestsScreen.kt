package io.github.arkmind.seedy.mobile.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.mediarequests.BookCard
import io.github.arkmind.seedy.core.mediarequests.BookRow
import io.github.arkmind.seedy.core.mediarequests.MediaFormat
import io.github.arkmind.seedy.core.mediarequests.MediaRequest
import io.github.arkmind.seedy.core.mediarequests.MediaRequestsViewModel
import io.github.arkmind.seedy.core.mediarequests.MediaShow
import io.github.arkmind.seedy.core.mediarequests.MediaTab
import io.github.arkmind.seedy.core.mediarequests.MusicRelease
import io.github.arkmind.seedy.core.mediarequests.ToneText
import io.github.arkmind.seedy.mobile.admin.ConfirmSheet
import io.github.arkmind.seedy.mobile.browse.topInset
import io.github.arkmind.seedy.mobile.library.Message
import io.github.arkmind.seedy.mobile.manage.ItemMenuSheet
import io.github.arkmind.seedy.mobile.manage.MenuEntry
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import java.time.LocalDate

/** What the Music and Books requests do; the route wires these to the view models, the sheets and navigation. */
class MediaActions(
    val onTab: (MediaTab) -> Unit = {},
    val onSearch: () -> Unit = {},
    /** Opens Lidarr or Shelfarr in the browser; null when the server doesn't say where it is (it only tells admins). */
    val onOpenApp: (() -> Unit)? = null,
    val onAlbum: (MusicRelease) -> Unit = {},
    val onBook: (BookCard, BookRow?) -> Unit = { _, _ -> },
    val onSeeAll: (String) -> Unit = {},
    val onShow: (MediaShow) -> Unit = {},
    val onRetry: () -> Unit = {},
    /** A finished request: the reader for ebooks and comics, the title's page otherwise. */
    val onReady: (MediaRequest) -> Unit = {},
    val onOpenItem: (MediaRequest) -> Unit = {},
    val onApprove: (MediaRequest) -> Unit = {},
    val onDecline: (MediaRequest) -> Unit = {},
    val onSearchNow: (MediaRequest) -> Unit = {},
    val onAcceptLower: (MediaRequest) -> Unit = {},
    val onRemove: (MediaRequest) -> Unit = {},
    /** The request's app in the browser, when the server says where it is. */
    val appUrl: (MediaRequest) -> (() -> Unit)? = { null },
    /** A book's cover: its own, else the edition's on the server. */
    val cover: (BookCard) -> String? = { it.coverUrl },
    val onMessageShown: () -> Unit = {},
)

/**
 * Boards "Android · Requests · Music" and "… Books": the header with Search and Open in Lidarr/Shelfarr, the service
 * tabs, the Discover · My requests (· All requests) switch, the requests in progress and Discover's rows; the other
 * tabs list the requests on their way (web: mine.tsx).
 */
@Composable
fun MediaRequestsScreen(
    state: MediaRequestsViewModel.State,
    music: Boolean,
    services: ServiceTabs?,
    actions: MediaActions,
    bottomPadding: Dp,
    today: LocalDate = LocalDate.now(),
    message: String? = state.message,
) {
    var removing by remember { mutableStateOf<MediaRequest?>(null) }
    var menu by remember { mutableStateOf<MediaRequest?>(null) }
    var showing by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding + 24.dp)) {
            item(key = "head") {
                Column(Modifier.padding(start = 20.dp, end = 16.dp, top = topInset()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RequestsTitleRow(actions.onSearch, actions.onOpenApp)
                    if (services != null) ServiceTabsRow(services, Modifier.padding(end = 4.dp))
                    val counts = state.counts(music)
                    val progress = state.progress(music, today)
                    val items = buildList {
                        add(SwitchItem(MediaTab.Discover, t("Discover")))
                        add(SwitchItem(MediaTab.Mine, t("My requests"), counts?.open ?: progress.requests.count { it.status != "ready" }))
                        if (state.canManage) add(SwitchItem(MediaTab.All, t("All requests"), counts?.pending ?: 0, pending = true))
                    }
                    RequestsSwitch(items, state.tab, actions.onTab, Modifier.padding(end = 4.dp))
                    if (state.tab == MediaTab.Discover && progress.requests.isNotEmpty()) {
                        val accent = LocalAccent.current
                        val waiting = SummaryCount(t("{n} waiting", "n" to progress.waiting), SeedyColors.Pending)
                        val downloading = SummaryCount(t("{n} downloading", "n" to progress.downloading), accent)
                        val searching = SummaryCount(t("{n} searching", "n" to progress.searching), SeedyColors.TextHero)
                        val ready = SummaryCount(t("{n} ready", "n" to progress.ready), SeedyColors.Ok)
                        val n = progress.requests.size
                        // The boards' orders: music downloading then searching; books searching, waiting, ready.
                        val order = if (music) listOf(waiting to progress.waiting, downloading to progress.downloading, searching to progress.searching, ready to progress.ready)
                        else listOf(searching to progress.searching, waiting to progress.waiting, downloading to progress.downloading, ready to progress.ready)
                        MediaSummaryCard(
                            if (music) tn(n, "{n} music request in progress", "{n} music requests in progress") else tn(n, "{n} book request", "{n} book requests"),
                            progress.requests, order.filter { it.second > 0 }.map { it.first },
                            onClick = { actions.onTab(MediaTab.Mine) }, modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
            }
            when (state.tab) {
                MediaTab.Discover -> if (music) musicRows(state, actions, today) else bookRows(state, actions, today)
                else -> requestList(
                    state, music, everyone = state.tab == MediaTab.All, today, actions,
                    onMenu = { menu = it }, onRemove = { removing = it }, onShowMenu = { showing = true },
                )
            }
        }
        Toast(message, actions.onMessageShown, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding))
    }
    menu?.let { r -> RequestMenu(r, actions, onRemove = { removing = it }, onDismiss = { menu = null }) }
    removing?.let { r -> RemoveSheet(r, actions, onDismiss = { removing = null }) }
    if (showing) ShowSheet(state.show, actions.onShow, onDismiss = { showing = false })
}

@Composable
private fun RowHead(title: String, onSeeAll: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SText(title, Modifier.weight(1f), size = 16.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
        if (onSeeAll != null) SText(t("See all"), Modifier.tap(onClick = onSeeAll), size = 13.sp, weight = 600, color = SeedyColors.Text2, maxLines = 1)
    }
}

private fun LazyListScope.musicRows(state: MediaRequestsViewModel.State, actions: MediaActions, today: LocalDate) {
    for (row in state.musicRows) {
        // A row with nothing in it leaves no gap.
        if (row.items?.isEmpty() == true) continue
        item(key = "music:${row.row.key}") {
            Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RowHead(t(row.row.title), row.items?.let { { actions.onSeeAll(row.row.key) } })
                val items = row.items
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (items == null) items(4) { CardSkeleton(128, 1f) }
                    else items(items.take(20), key = { it.mbid }) { r ->
                        val card = state.asked.release(r)
                        AlbumCard(card, MediaFormat.releaseMeta(card, row.row, today), onClick = { actions.onAlbum(card) })
                    }
                }
            }
        }
    }
}

private fun LazyListScope.bookRows(state: MediaRequestsViewModel.State, actions: MediaActions, today: LocalDate) {
    for (row in state.bookRows) {
        if (row.items?.isEmpty() == true) continue
        item(key = "books:${row.row.key}") {
            Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RowHead(t(row.row.title), row.items?.let { { actions.onSeeAll(row.row.key) } })
                val items = row.items
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (items == null) items(4) { CardSkeleton(112, 2f / 3f) }
                    else items(items.take(20), key = { it.workId }) { b ->
                        val card = state.asked.book(b)
                        BookCardView(card, actions.cover(card), MediaFormat.bookMeta(card, row.row, today), onClick = { actions.onBook(card, row.row) })
                    }
                }
            }
        }
    }
}

// ── My requests · All requests ──

private fun LazyListScope.requestList(
    state: MediaRequestsViewModel.State,
    music: Boolean,
    everyone: Boolean,
    today: LocalDate,
    actions: MediaActions,
    onMenu: (MediaRequest) -> Unit,
    onRemove: (MediaRequest) -> Unit,
    onShowMenu: () -> Unit,
) {
    val lists = state.lists(music)
    val all = if (everyone) lists.all else lists.mine
    val shown = state.shown(music, if (everyone) MediaTab.All else MediaTab.Mine)
    item(key = "show") { ShowButton(state.show, onShowMenu) }
    when {
        all == null || shown == null -> items(4) {
            Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp).fillMaxWidth().heightIn(min = 132.dp).clip(RoundedCornerShape(14.dp)).background(SeedyColors.Panel))
        }
        lists.failed && all.isEmpty() -> item(key = "failed") { Message(t("Couldn’t load requests"), t("Try again in a moment."), t("Try again"), actions.onRetry) }
        shown.isEmpty() && state.show != MediaShow.All -> item(key = "none") {
            Message(t("No requests here"), t("Nothing matches what you picked."), t("Show everything")) { actions.onShow(MediaShow.All) }
        }
        shown.isEmpty() -> item(key = "empty") {
            if (everyone) Message(t("No requests yet"), t("Requests from everyone on the server show up here."))
            else Message(t("You haven’t requested anything yet"), t("What you request shows up here, with where it is on its way to the server."), t("Find something to request")) {
                actions.onTab(MediaTab.Discover)
            }
        }
        else -> items(shown, key = { "req:${it.id}" }) { r ->
            Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp)) {
                MediaRequestRow(r, everyone, busy = r.id in state.busy, today, actions, onMore = { onMenu(r) }, onRemove = { onRemove(r) })
            }
        }
    }
}

/** "Show Open and finished ⌄": which requests the list keeps. */
@Composable
private fun ShowButton(show: MediaShow, onClick: () -> Unit) {
    val prefix = t("Show {what}").substringBefore("{what}").trim()
    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp).fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Row(
            Modifier.height(36.dp).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Row).tap(onClick = onClick).padding(start = 12.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (prefix.isNotEmpty()) SText(prefix, size = 13.sp, weight = 600, color = SeedyColors.Text3, maxLines = 1)
            SText(t(show.label), size = 13.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
            SIcon(WebIcons.ChevronDown, size = 16.dp, color = SeedyColors.Text2, stroke = 2.4f)
        }
    }
}

@Composable
private fun ShowSheet(current: MediaShow, onShow: (MediaShow) -> Unit, onDismiss: () -> Unit) {
    ItemMenuSheet(
        t("Show requests"),
        MediaShow.entries.map { s -> MenuEntry(if (s == current) WebIcons.Check else Blank, t(s.label)) { onShow(s) } },
        onDismiss,
    )
}

private val Blank = io.github.arkmind.seedy.ui.icons.IconDef(0f, emptyList())

private fun toneColour(tone: String, accent: Color): Color = when (tone) {
    "accent" -> accent
    "danger" -> SeedyColors.Danger
    "pending" -> SeedyColors.Pending
    "ok" -> SeedyColors.Ok
    else -> SeedyColors.TextHero
}

/**
 * A request on its way (no phone board: the RequestsMine board's row, on the phone's scale): its cover, name and
 * app, what and when, what's happening now, the five stages, and what the person can do about it.
 */
@Composable
fun MediaRequestRow(
    r: MediaRequest,
    everyone: Boolean,
    busy: Boolean,
    today: LocalDate,
    actions: MediaActions,
    onMore: () -> Unit,
    onRemove: () -> Unit,
) {
    val stage = MediaFormat.stage(r)
    val attention = stage.fail && r.status != "declined"
    val shape = RoundedCornerShape(14.dp)
    val accent = LocalAccent.current
    Column(
        Modifier.fillMaxWidth().clip(shape).background(if (attention) Color(0xFF161212) else SeedyColors.Panel)
            .then(if (attention) Modifier.border(1.dp, SeedyColors.Danger.copy(alpha = 0.25f), shape) else Modifier)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) {
                if (r.music) RequestThumbArt(r, Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)))
                else RequestThumbArt(r, Modifier.size(40.dp, 60.dp).clip(RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp, topEnd = 5.dp, bottomEnd = 5.dp)))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                SText(MediaFormat.name(r), size = 14.sp, weight = 800, color = SeedyColors.White, maxLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SText(
                        MediaFormat.serviceName(r.service), Modifier.background(SeedyColors.Surface, CircleShape).padding(horizontal = 7.dp, vertical = 1.dp),
                        size = 9.sp, weight = 800, tracking = 0.06f, uppercase = true, color = SeedyColors.TextHero, maxLines = 1,
                    )
                    SText(MediaFormat.whatLine(r, everyone, today), Modifier.weight(1f), size = 12.sp, color = SeedyColors.Text3, maxLines = 2)
                }
                MediaFormat.note(r)?.takeIf { it.text.isNotEmpty() }?.let { n: ToneText ->
                    SText(n.text, size = 12.sp, weight = 600, color = toneColour(n.tone, accent), maxLines = 2)
                }
            }
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(SeedyColors.Surface).tap(onClick = onMore), contentAlignment = Alignment.Center) {
                SIcon(WebIcons.More, size = 18.dp, color = SeedyColors.Text)
            }
        }
        Pipeline(MediaFormat.stage(r))
        // The main thing to do: open what arrived, approve, search again; or cancel a request still waiting.
        val mine = r.requestedByMe
        val buttons = buildList<Triple<String, Boolean, () -> Unit>> {
            val ready = MediaFormat.readyLabel(r)
            when {
                ready != null -> add(Triple(ready, true) { actions.onReady(r) })
                r.canDecide && (everyone || !mine) -> {
                    add(Triple(t("Approve"), true) { actions.onApprove(r) })
                    add(Triple(t("Decline"), false) { actions.onDecline(r) })
                }
                r.canSearch && (r.attention || r.status == "failed") -> {
                    add(Triple(t("Search now"), true) { actions.onSearchNow(r) })
                    r.lowerQuality?.let { q -> add(Triple(t("Accept {quality}", "quality" to q), false) { actions.onAcceptLower(r) }) }
                }
                mine && r.status == "pending" -> add(Triple(t("Cancel request"), false, onRemove))
            }
        }
        if (buttons.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, main, run) in buttons) {
                    Box(
                        Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(11.dp)).background(if (main) SeedyColors.Inverse else SeedyColors.Surface)
                            .tap(enabled = !busy, onClick = run).alpha(if (busy) 0.5f else 1f),
                        contentAlignment = Alignment.Center,
                    ) { SText(label, size = 13.sp, weight = if (main) 800 else 700, color = if (main) SeedyColors.InkInverse else SeedyColors.Text, maxLines = 1) }
                }
            }
        }
    }
}

/**
 * Asked → Approved → Searching → Downloading → Ready, lit up to where the request is (red where it's stuck). The phone
 * has no room for five names: the current stage's sits under its bar, running on under the ones still to come.
 */
@Composable
private fun Pipeline(stage: io.github.arkmind.seedy.core.mediarequests.MediaStage) {
    val accent = LocalAccent.current
    val gap = 5.dp
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            for (i in MediaFormat.Stages.indices) {
                val bar = when {
                    i >= stage.n -> Color(0xFF262626)
                    stage.fail && i == stage.n - 1 -> SeedyColors.Danger
                    i == 4 -> SeedyColors.Ok
                    else -> accent
                }
                Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(bar))
            }
        }
        val i = (stage.n - 1).coerceIn(0, 4)
        val label = stage.label ?: t(MediaFormat.Stages[i])
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            if (i > 0) Spacer(Modifier.weight(i.toFloat()))
            SText(
                label, Modifier.weight((5 - i).toFloat()), size = 11.sp, weight = 800,
                color = if (stage.fail) SeedyColors.Danger else SeedyColors.White, maxLines = 1,
                align = if (i == 4) androidx.compose.ui.text.style.TextAlign.Start else null,
            )
        }
    }
}

/** The row's More: open on the server, search again, accept a lesser quality, open the app, cancel or remove. */
@Composable
private fun RequestMenu(r: MediaRequest, actions: MediaActions, onRemove: (MediaRequest) -> Unit, onDismiss: () -> Unit) {
    val app = actions.appUrl(r)
    val entries = buildList {
        if (!r.itemId.isNullOrEmpty()) add(MenuEntry(WebIcons.Play, t("Open on the server")) { actions.onOpenItem(r) })
        if (r.canSearch) add(MenuEntry(WebIcons.Refresh, t("Search now")) { actions.onSearchNow(r) })
        if (r.lowerQuality != null && r.canSearch) add(MenuEntry(WebIcons.Quality, t("Accept {quality}", "quality" to r.lowerQuality)) { actions.onAcceptLower(r) })
        if (app != null) add(MenuEntry(WebIcons.External, t("Open {app}", "app" to MediaFormat.serviceName(r.service))) { app() })
        if (r.canCancel) {
            val own = r.requestedByMe && r.status == "pending"
            add(MenuEntry(WebIcons.Close, if (own) t("Cancel request") else t("Remove request"), danger = true) { onRemove(r) })
        }
        if (isEmpty()) add(MenuEntry(Blank, t("Nothing to do here yet")) {})
    }
    ItemMenuSheet(t("More for {title}", "title" to MediaFormat.name(r)), entries, onDismiss)
}

/** The web's confirm before a request goes: the person's own waiting one is cancelled, anyone's is removed. */
@Composable
private fun RemoveSheet(r: MediaRequest, actions: MediaActions, onDismiss: () -> Unit) {
    val title = MediaFormat.name(r)
    if (r.requestedByMe && r.status == "pending") {
        ConfirmSheet(t("Cancel your request?"), t("{title} won’t be requested. You can ask for it again later.", "title" to title), t("Cancel request"), { actions.onRemove(r) }, onDismiss)
    } else {
        ConfirmSheet(
            t("Remove this request?"),
            t(
                "The request for {title} is removed, and cancelled in {service} when it’s still on its way. Anything already downloaded stays on the server.",
                "title" to title, "service" to MediaFormat.serviceName(r.service),
            ),
            t("Remove"), { actions.onRemove(r) }, onDismiss, danger = true,
        )
    }
}
