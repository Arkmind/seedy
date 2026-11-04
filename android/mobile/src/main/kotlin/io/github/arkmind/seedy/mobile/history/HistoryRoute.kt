package io.github.arkmind.seedy.mobile.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.feature.HistoryActions
import io.github.arkmind.seedy.core.feature.HistoryState
import io.github.arkmind.seedy.core.feature.HistoryViewModel
import io.github.arkmind.seedy.core.history.HistoryEntry
import io.github.arkmind.seedy.core.history.HistoryFilter
import io.github.arkmind.seedy.core.history.HistoryFormat
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.seedy.libraryUuid
import io.github.arkmind.seedy.mobile.admin.BoardField
import io.github.arkmind.seedy.mobile.admin.ConfirmSheet
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.browse.Chip
import io.github.arkmind.seedy.mobile.manage.ItemMenuSheet
import io.github.arkmind.seedy.mobile.manage.ManageIcons
import io.github.arkmind.seedy.mobile.manage.MenuEntry
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.nav.Route
import io.github.arkmind.seedy.mobile.parts.Tab
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.settings.BoardSwitch
import io.github.arkmind.seedy.mobile.settings.Card
import io.github.arkmind.seedy.mobile.settings.RowLine
import io.github.arkmind.seedy.mobile.settings.SectionTitle
import io.github.arkmind.seedy.mobile.settings.SettingsPage
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession
import java.time.LocalDate

/** Where a history row leads: its player (at [startTicks], null for where the person left off) or its title's page. */
class HistoryNav(
    val back: () -> Unit = {},
    val play: (HistoryEntry, Long?) -> Unit = { _, _ -> },
    val open: (HistoryEntry) -> Unit = {},
    val browse: () -> Unit = {},
)

/** Viewing history (Settings, Profile and the Libraries tab lead here). */
@Composable
fun HistoryRoute() {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val vm = viewModel { HistoryViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    HistoryScreen(
        state, vm, LocalDate.now(),
        HistoryNav(
            back = nav::back,
            play = { e, start -> libraryUuid(e.itemId)?.let { nav.go(Route.Player(it, start)) } },
            open = { e -> libraryUuid(e.titleId)?.let { nav.go(Route.Details(it)) } },
            browse = { nav.toTab(Tab.Home) },
        ),
    )
}

/**
 * Viewing history, from the web's History board (its phone layout) in the phone's settings kit: search and Clear
 * history, the kinds and Show hidden, then the plays by day in cards, 30 at a time. [today] places the day groups.
 */
@Composable
fun HistoryScreen(state: HistoryState, actions: HistoryActions, today: LocalDate, nav: HistoryNav) {
    var menu by remember { mutableStateOf<HistoryEntry?>(null) }
    var clearing by remember { mutableStateOf(false) }
    SettingsPage(
        t("Viewing history"),
        t("Every play on this profile, newest first. Hidden titles stop shaping your recommendations and the Top 10."),
        nav.back,
        overlay = {
            Toast(state.message, actions::messageShown, Modifier.align(Alignment.BottomCenter))
            menu?.let { e -> RowMenu(e, actions, nav) { menu = null } }
            if (clearing) {
                ConfirmSheet(
                    t("Clear viewing history?"),
                    t("Every play on this profile is removed from Seedy. Recommendations and the Top 10 start fresh. Watched marks and resume points in Jellyfin stay as they are."),
                    t("Clear history"), actions::clear, { clearing = false }, danger = true,
                )
            }
        },
    ) {
        if (!state.enabled) {
            Message(WebIcons.History, t("Viewing history is off"), t("The server admin has turned off the play log for this profile, so Seedy isn't keeping a history. Jellyfin still remembers what you've watched and where you stopped."))
            return@SettingsPage
        }
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                BoardField(state.text, actions::setText, placeholder = t("Search history"), size = 14, start = 40.dp)
                SIcon(WebIcons.Search, Modifier.padding(start = 14.dp), size = 17.dp, color = SeedyColors.Text3)
            }
            PillButton(t("Clear history"), { clearing = true }, height = 46.dp, enabled = state.items.isNotEmpty() || state.busy)
        }
        // The kinds, then Show hidden at the end of the line (under them when the words don't fit).
        FlowRow(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            for (f in HistoryFilter.entries) Chip(t(f.label), f == state.filter, { actions.setFilter(f) })
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                SText(t("Show hidden"), size = 13.sp, weight = 600, color = SeedyColors.TextHero, maxLines = 1)
                BoardSwitch(state.showHidden, actions::setShowHidden)
            }
        }

        when {
            state.busy -> Skeleton()
            state.status == "error" -> Message(
                WebIcons.Alert, t("Couldn't load your history"), t("The server didn't answer. Check that the Seedy plugin is running, then try again."),
                t("Try again"), actions::retry,
            )
            state.items.isEmpty() -> Empty(state, nav)
            else -> {
                for (g in HistoryFormat.groups(state.items, today)) {
                    SectionTitle(g.label)
                    Card(Modifier.padding(horizontal = 16.dp)) {
                        g.items.forEachIndexed { i, e ->
                            if (i > 0) RowLine()
                            HistoryRow(e, g.recent, nav, onMore = { menu = e }, onToggleHidden = { actions.toggleHidden(e) })
                        }
                    }
                }
                if (state.more) {
                    Box(Modifier.fillMaxWidth().padding(top = 20.dp), contentAlignment = Alignment.Center) {
                        PillButton(if (state.status == "more") t("Loading…") else t("Show earlier"), actions::loadMore, enabled = state.status != "more", height = 44.dp)
                    }
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

/**
 * A play (the web's phone row): the 112 px picture with how far it got (Play), the title and episode, where it stopped
 * and when, then Hide and More stacked on the right. Hidden titles step back.
 */
@Composable
private fun HistoryRow(e: HistoryEntry, recent: Boolean, nav: HistoryNav, onMore: () -> Unit, onToggleHidden: () -> Unit) {
    val images = LocalViewer.current.images
    val viewer = LocalViewer.current
    val playable = e.item != null
    val fraction = HistoryFormat.fraction(e)
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(SeedyColors.Surface)
                .alpha(if (e.hidden) 0.4f else 1f)
                .then(if (playable) Modifier.tap { nav.play(e, if (e.finished) null else e.positionTicks) } else Modifier),
        ) {
            e.item?.let { Artwork(images?.landscape(it, 240, viewer.episodeImages), Modifier.fillMaxWidth().fillMaxHeight()) }
            if (!e.finished && fraction > 0f) {
                Box(Modifier.align(Alignment.BottomCenter).padding(6.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(SeedyColors.White))
                }
            }
            if (e.finished) {
                Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).background(SeedyColors.Scrim, CircleShape), contentAlignment = Alignment.Center) {
                    SIcon(WebIcons.Check, size = 12.dp, color = Color.White, stroke = 3f)
                }
            }
        }
        Column(
            Modifier.weight(1f).then(if (playable) Modifier.tap { nav.open(e) } else Modifier),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SText(
                    HistoryFormat.title(e), Modifier.weight(1f, fill = false), size = 15.sp, weight = 700,
                    color = if (e.hidden) SeedyColors.Text3 else SeedyColors.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (e.hidden) {
                    SText(
                        t("Hidden"), Modifier.clip(CircleShape).background(SeedyColors.Surface).padding(horizontal = 8.dp, vertical = 1.dp),
                        size = 11.sp, weight = 700, color = SeedyColors.Text2, maxLines = 1,
                    )
                }
            }
            SText(HistoryFormat.sub(e), size = 13.sp, color = if (e.hidden) Faint else SeedyColors.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            SText(HistoryFormat.state(e), size = 12.sp, weight = 600, color = if (e.hidden) SeedyColors.Text3 else SeedyColors.Text, maxLines = 2)
            SText(HistoryFormat.whenOf(e, recent), size = 12.sp, color = if (e.hidden) Faint else SeedyColors.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column {
            RowButton(if (e.hidden) WebIcons.Eye else WebIcons.EyeOff, onToggleHidden)
            RowButton(WebIcons.More, onMore)
        }
    }
}

private val Faint = Color(0xFF6A6A6A)

@Composable
private fun RowButton(icon: IconDef, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).tap(onClick = onClick), contentAlignment = Alignment.Center) {
        SIcon(icon, size = 20.dp, color = SeedyColors.Text2)
    }
}

/** More: go to the title, play from where it stopped or from the start, hide, remove. */
@Composable
private fun RowMenu(e: HistoryEntry, actions: HistoryActions, nav: HistoryNav, onDismiss: () -> Unit) {
    val entries = buildList {
        if (e.item != null) {
            val movie = HistoryFormat.movieLike(e)
            add(MenuEntry(if (movie) WebIcons.Film else WebIcons.Tv, if (movie) t("Go to movie") else t("Go to series")) { nav.open(e) })
            if (HistoryFormat.resumable(e)) add(MenuEntry(WebIcons.Play, t("Play from where you stopped"), HistoryFormat.stoppedAt(e)) { nav.play(e, e.positionTicks) })
            add(MenuEntry(ManageIcons.PlayFromStart, t("Play from the beginning")) { nav.play(e, 0L) })
        }
        add(MenuEntry(if (e.hidden) WebIcons.Eye else WebIcons.EyeOff, if (e.hidden) t("Unhide") else t("Hide from history"), line = e.item != null) { actions.toggleHidden(e) })
        add(MenuEntry(WebIcons.Trash, t("Remove from history"), danger = true) { actions.remove(e) })
    }
    ItemMenuSheet(HistoryFormat.title(e), entries, onDismiss)
}

/** Nothing played, or nothing for this search or kind. */
@Composable
private fun Empty(state: HistoryState, nav: HistoryNav) {
    when {
        state.search.isNotEmpty() -> Message(
            WebIcons.SearchX, t("Nothing in your history for “{search}”", "search" to state.search),
            if (state.showHidden) t("Try another title, or switch to All.") else t("Try another title, or switch to All and turn on Show hidden."),
        )
        state.filter == HistoryFilter.Movies -> Message(WebIcons.SearchX, t("No movies played yet"), t("Movies you play on this profile show up here, newest first."))
        state.filter == HistoryFilter.Shows -> Message(WebIcons.SearchX, t("No shows played yet"), t("Episodes you play on this profile show up here, newest first."))
        else -> Message(
            WebIcons.History, t("Nothing played yet"), t("Every movie and episode you play on this profile shows up here, newest first."),
            t("Find something to watch"), nav.browse,
        )
    }
}

/** The web's empty state in a card: an icon in a tile, a title, a line, and maybe a button. */
@Composable
private fun Message(icon: IconDef, title: String, text: String, action: String? = null, onAction: () -> Unit = {}) {
    Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp), padding = 24.dp, gap = 12.dp) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(52.dp).clip(RoundedCornerShape(16.dp)).background(SeedyColors.Surface), contentAlignment = Alignment.Center) {
            SIcon(icon, size = 26.dp, color = SeedyColors.Text2)
        }
        SText(title, Modifier.fillMaxWidth(), size = 17.sp, weight = 800, color = SeedyColors.White, align = TextAlign.Center)
        SText(text, Modifier.fillMaxWidth(), size = 13.sp, lineHeight = 1.5f, color = SeedyColors.Text2, align = TextAlign.Center)
        if (action != null) {
            Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) { PillButton(action, onAction) }
        }
    }
}

/** Four grey rows under a grey label while the first plays load. */
@Composable
private fun Skeleton() {
    Box(Modifier.padding(start = 20.dp, top = 26.dp, bottom = 12.dp).size(150.dp, 12.dp).clip(RoundedCornerShape(6.dp)).background(SeedyColors.Surface))
    Card(Modifier.padding(horizontal = 16.dp)) {
        repeat(4) { i ->
            if (i > 0) RowLine()
            Row(Modifier.fillMaxWidth().heightIn(min = 87.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(SeedyColors.Surface))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth(0.6f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(SeedyColors.Surface))
                    Box(Modifier.fillMaxWidth(0.4f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(SeedyColors.Surface))
                    Box(Modifier.fillMaxWidth(0.75f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(SeedyColors.Surface))
                }
            }
        }
    }
}
