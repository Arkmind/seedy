package io.github.arkmind.seedy.mobile.admin.seedy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.arkmind.seedy.core.admin.seedy.HomeAdminActions
import io.github.arkmind.seedy.core.admin.seedy.HomeAdminState
import io.github.arkmind.seedy.core.admin.seedy.HomeConfig
import io.github.arkmind.seedy.core.admin.seedy.HomeRowConfig
import io.github.arkmind.seedy.core.admin.seedy.HomeRowInfo
import io.github.arkmind.seedy.core.admin.seedy.LockedRows
import io.github.arkmind.seedy.core.admin.seedy.PickItem
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.settings.Choice
import io.github.arkmind.seedy.mobile.admin.AddChip
import io.github.arkmind.seedy.mobile.admin.BoardField
import io.github.arkmind.seedy.mobile.admin.ChipFlow
import io.github.arkmind.seedy.mobile.admin.ErrorCard
import io.github.arkmind.seedy.mobile.admin.LeaveGuard
import io.github.arkmind.seedy.mobile.admin.LoadingCards
import io.github.arkmind.seedy.mobile.admin.NameTag
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.admin.PillStyle
import io.github.arkmind.seedy.mobile.admin.RoundButton
import io.github.arkmind.seedy.mobile.admin.SaveBar
import io.github.arkmind.seedy.mobile.admin.SaveBarRoom
import io.github.arkmind.seedy.mobile.admin.SheetFoot
import io.github.arkmind.seedy.mobile.admin.Thumb
import io.github.arkmind.seedy.mobile.browse.BottomSheet
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.settings.BoardSwitch
import io.github.arkmind.seedy.mobile.settings.CssColumn
import io.github.arkmind.seedy.mobile.settings.Faint
import io.github.arkmind.seedy.mobile.settings.PickOption
import io.github.arkmind.seedy.mobile.settings.PickRow
import io.github.arkmind.seedy.mobile.settings.RowLine
import io.github.arkmind.seedy.mobile.settings.Section
import io.github.arkmind.seedy.mobile.settings.SectionTitle
import io.github.arkmind.seedy.mobile.settings.SegRow
import io.github.arkmind.seedy.mobile.settings.SettingsPage
import io.github.arkmind.seedy.mobile.settings.SheetHead
import io.github.arkmind.seedy.mobile.settings.SheetHost
import io.github.arkmind.seedy.mobile.settings.SheetState
import io.github.arkmind.seedy.mobile.settings.StackRow
import io.github.arkmind.seedy.mobile.settings.SwitchRow
import io.github.arkmind.seedy.mobile.settings.cssText
import io.github.arkmind.seedy.mobile.settings.rememberSheet
import io.github.arkmind.seedy.ui.components.boxShadow
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.SettingsIcons
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import kotlinx.coroutines.delay

private val Dragged = Color(0xFF262626)

private val LookBack = listOf(30, 60, 90, 0)
private fun lookBackLabel(n: Int) = when (n) {
    0 -> t("No limit")
    30 -> t("30 days")
    60 -> t("60 days")
    90 -> t("90 days")
    else -> tn(n, "{n} day", "{n} days")
}

private val GenreWindow = listOf(30, 90, 365)
private fun genreWindowLabel(n: Int) = when (n) {
    30 -> t("Last 30 days")
    90 -> t("Last 90 days")
    365 -> t("Last year")
    else -> tn(n, "{n} day", "{n} days")
}

private val QuietProfiles = listOf(1, 2, 3, 4, 5, 6, 8, 10, 15, 20, 30, 50)

/** Seedy › Home screen (no phone board: the server boards' kit, the web board AdminHome's content, the settings board's row list). */
@Composable
fun DefaultHomeScreen(state: HomeAdminState, actions: HomeAdminActions, onBack: () -> Unit, dragging: String? = null) {
    val sheet = rememberSheet()
    var genreSheet by remember { mutableStateOf(false) }
    var titleSheet by remember { mutableStateOf(false) }
    val changes = state.changes
    LeaveGuard(changes > 0, t("Your changes to the home screen haven’t been saved yet. If you leave now, they’re lost."), onBack)
    SettingsPage(
        t("Home screen"),
        t("The home screen every new profile starts with. After that, row order adapts to each person unless they or you turn it off."),
        onBack,
        overlay = {
            SheetHost(sheet)
            val h = state.home
            if (h != null && genreSheet) GenreSheet(h.genres.starting, actions, { g -> actions.edit { it.copy(genres = it.genres.copy(starting = it.genres.starting + g)) } }) { genreSheet = false }
            if (h != null && titleSheet) {
                TitleSheet(h.featured.picks, actions, { item ->
                    actions.remember(item)
                    actions.edit { c -> val p = c.featured.picks; c.copy(featured = c.featured.copy(picks = if (item.id in p) p - item.id else p + item.id)) }
                }) { titleSheet = false }
            }
            if (changes > 0) SaveBar(tn(changes, "{n} unsaved change", "{n} unsaved changes"), actions::discard, actions::save, saving = state.saving)
            Toast(state.message, actions::messageShown, Modifier.align(Alignment.BottomCenter).padding(bottom = if (changes > 0) 90.dp else 0.dp))
        },
    ) {
        val h = state.home
        when {
            h == null && state.loadError != null -> ErrorCard(t("Couldn’t load Seedy’s settings"), actions::retry, Modifier.padding(top = 16.dp), detail = state.loadError.orEmpty())
            h == null -> LoadingCards(3, 220.dp)
            else -> HomeBody(h, state, actions, sheet, dragging, addGenre = { genreSheet = true }, addTitle = { titleSheet = true })
        }
        if (changes > 0) SaveBarRoom() else androidx.compose.foundation.layout.Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun HomeBody(h: HomeConfig, state: HomeAdminState, actions: HomeAdminActions, sheet: SheetState, dragging: String?, addGenre: () -> Unit, addTitle: () -> Unit) {
    SectionTitle(t("Rows"))
    CssColumn(Modifier.padding(horizontal = 16.dp)) {
        SText(
            t("Drag to set the starting order. Switched-off rows are hidden for everyone."),
            Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp).cssText(12, 1.45f), size = 12.sp, lineHeight = 1.45f, color = SeedyColors.Text3,
        )
        RowsEditor(h.rows, { rows -> actions.edit { it.copy(rows = rows) } }, dragging)
    }
    Section(null) {
        row {
            SwitchRow(
                t("Adapt row order to each person"),
                t("Rows people play from move up, recalculated once a day. Featured and Continue watching never move. Each profile can turn this off."),
                h.adaptOrder,
            ) { v -> actions.edit { it.copy(adaptOrder = v) } }
        }
    }

    Section(t("Because you watched")) {
        row {
            SegRow(t("A title counts as watched when"), t("Only watched titles start a row"), listOf(Choice("finished", t("Finished")), Choice("half", t("Half watched"))), h.because.watchedWhen) { v ->
                actions.edit { it.copy(because = it.because.copy(watchedWhen = v)) }
            }
        }
        row {
            val value = if (h.because.lookBackDays in LookBack) h.because.lookBackDays else 60
            PickRow(t("Look back"), t("Older plays never start a row"), lookBackLabel(value)) {
                sheet.open(t("Look back"), choices(LookBack, ::lookBackLabel), value) { v -> actions.edit { it.copy(because = it.because.copy(lookBackDays = v)) } }
            }
        }
        row {
            val n = h.because.minTitles
            PickRow(t("Smallest row"), t("Rows with fewer titles are dropped"), tn(n, "{n} title", "{n} titles")) {
                sheet.open(t("Smallest row"), choices((1..12).toList()) { tn(it, "{n} title", "{n} titles") }, n) { v -> actions.edit { it.copy(because = it.because.copy(minTitles = v)) } }
            }
        }
    }

    Section("Top 10") {
        row {
            SegRow(t("Period"), t("Each title counts once per profile"), listOf(Choice(7, t("7 days")), Choice(30, t("30 days"))), h.top10.periodDays) { v ->
                actions.edit { it.copy(top10 = it.top10.copy(periodDays = v)) }
            }
        }
        row {
            val n = h.top10.minProfiles
            PickRow(t("Hide on quiet weeks"), t("When fewer profiles than this watched anything"), tn(n, "{n} profile", "{n} profiles")) {
                sheet.open(t("Hide on quiet weeks"), choices(withValue(QuietProfiles, n)) { tn(it, "{n} profile", "{n} profiles") }, n) { v -> actions.edit { it.copy(top10 = it.top10.copy(minProfiles = v)) } }
            }
        }
    }

    Section(t("Genre rows")) {
        row {
            val value = if (h.genres.windowDays in GenreWindow) h.genres.windowDays else 90
            PickRow(t("Pick genres from"), t("Titles each person finished in this period"), genreWindowLabel(value)) {
                sheet.open(t("Pick genres from"), choices(GenreWindow, ::genreWindowLabel), value) { v -> actions.edit { it.copy(genres = it.genres.copy(windowDays = v)) } }
            }
        }
        row {
            StackRow(t("Starting genres"), t("For new profiles, until they have watched enough"), gap = 12.dp) {
                ChipFlow(h.genres.starting, { i -> actions.edit { it.copy(genres = it.genres.copy(starting = it.genres.starting.filterIndexed { j, _ -> j != i })) } }, t("Add genre"), addGenre)
            }
        }
    }

    Section(t("Featured")) {
        row {
            SegRow(t("What the hero shows"), t("5 titles in rotation"), listOf(Choice("auto", t("Automatic")), Choice("picks", t("Your picks")), Choice("both", t("Both"))), h.featured.mode) { v ->
                actions.edit { it.copy(featured = it.featured.copy(mode = v)) }
            }
        }
        if (h.featured.mode != "auto") {
            row {
                StackRow(
                    t("Your picks"),
                    if (h.featured.mode == "picks") t("Shown in this order; recently added titles fill in if there are fewer than 5") else t("Shown first; recently added and in-progress titles fill the rest"),
                    gap = 12.dp,
                ) {
                    for (id in h.featured.picks) {
                        val item = state.picks[id]
                        val loading = id !in state.picks
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SeedyColors.Surface).padding(start = 6.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Thumb(item?.art, 72.dp, fallback = SeedyColors.Surface2)
                            SText(
                                item?.title ?: if (loading) t("Loading…") else t("Title not found"),
                                Modifier.weight(1f), size = 14.sp, weight = 700, color = if (item != null) SeedyColors.White else SeedyColors.Text3, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            RoundButton(WebIcons.Close, { actions.edit { c -> c.copy(featured = c.featured.copy(picks = c.featured.picks - id)) } }, iconSize = 14.dp, bare = true, tint = SeedyColors.Text2)
                        }
                    }
                    AddChip(t("Add a title"), addTitle)
                }
            }
        }
    }
}

/**
 * The rows in order: drag one by its grip, or hold it and drag. Featured and Continue watching stay first and second
 * (a lock instead of the grip) and nothing moves above them. [preview] shows a row picked up (screenshots).
 */
@Composable
private fun RowsEditor(rows: List<HomeRowConfig>, onChange: (List<HomeRowConfig>) -> Unit, preview: String?) {
    var order by remember { mutableStateOf<List<String>?>(null) }
    var drag by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Int>() }
    val shown = order?.mapNotNull { k -> rows.firstOrNull { it.kind == k } }?.takeIf { it.size == rows.size } ?: rows
    val firstMovable = rows.indexOfFirst { it.kind !in LockedRows }.coerceAtLeast(0)

    fun start(kind: String) {
        order = shown.map { it.kind }
        drag = kind
        offset = 0f
    }

    fun move(dy: Float) {
        val kind = drag ?: return
        val list = order?.toMutableList() ?: return
        offset += dy
        var i = list.indexOf(kind)
        while (i < list.lastIndex && offset > (heights[list[i + 1]] ?: 0) / 2f) {
            offset -= heights[list[i + 1]] ?: 0
            list.add(i + 1, list.removeAt(i)); i++
        }
        while (i > firstMovable && offset < -(heights[list[i - 1]] ?: 0) / 2f) {
            offset += heights[list[i - 1]] ?: 0
            list.add(i - 1, list.removeAt(i)); i--
        }
        // Not above the locked rows: the row stops at their edge.
        if (i == firstMovable && offset < 0) offset = maxOf(offset, -8f)
        order = list
    }

    fun end() {
        val list = order?.mapNotNull { k -> rows.firstOrNull { it.kind == k } }
        drag = null
        offset = 0f
        if (list != null && list.map { it.kind } != rows.map { it.kind }) onChange(list)
        order = null
    }

    Column(Modifier.fillMaxWidth().background(SeedyColors.Panel, RoundedCornerShape(18.dp))) {
        shown.forEachIndexed { i, r ->
            val locked = r.kind in LockedRows
            val lifted = r.kind == (drag ?: preview)
            if (i > 0 && !lifted) RowLine()
            AdminRowItem(
                r, locked, lifted,
                Modifier.onSizeChanged { heights[r.kind] = it.height }
                    .zIndex(if (lifted) 1f else 0f)
                    .graphicsLayer {
                        if (lifted) {
                            translationY = if (drag != null) offset else -10.dp.toPx()
                            scaleX = 1.02f
                            scaleY = 1.02f
                        }
                    }
                    .then(
                        if (locked) Modifier else Modifier.pointerInput(r.kind) {
                            detectDragGesturesAfterLongPress(onDragStart = { start(r.kind) }, onDragEnd = ::end, onDragCancel = ::end) { c, d -> c.consume(); move(d.y) }
                        },
                    ),
                grip = if (locked) Modifier else Modifier.pointerInput(r.kind) {
                    detectDragGestures(onDragStart = { start(r.kind) }, onDragEnd = ::end, onDragCancel = ::end) { c, d -> c.consume(); move(d.y) }
                },
                onToggle = { on -> onChange(rows.map { if (it.kind == r.kind) it.copy(enabled = on) else it }) },
                onCount = { n -> onChange(rows.map { if (it.kind == r.kind) it.copy(count = n) else it }) },
            )
        }
    }
}

@Composable
private fun AdminRowItem(r: HomeRowConfig, locked: Boolean, lifted: Boolean, modifier: Modifier, grip: Modifier, onToggle: (Boolean) -> Unit, onCount: (Int) -> Unit) {
    val info = HomeRowInfo[r.kind]
    val name = t(info?.name ?: r.kind)
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.fillMaxWidth()
            .then(if (lifted) Modifier.boxShadow(Color.Black.copy(alpha = 0.6f), 30.dp, 14.dp, 16.dp).clip(shape).background(Dragged).border(1.dp, Color.White.copy(alpha = 0.08f), shape) else Modifier)
            .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp).heightIn(min = 58.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(grip.size(38.dp, 44.dp), contentAlignment = Alignment.Center) {
            if (locked) SIcon(WebIcons.Lock, size = 17.dp, color = SeedyColors.Text3)
            else SIcon(SettingsIcons.Grip, size = 20.dp, color = if (lifted) SeedyColors.White else Faint)
        }
        Column(Modifier.weight(1f).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SText(name, Modifier.weight(1f, fill = false), size = 14.sp, weight = 700, color = if (r.enabled) SeedyColors.White else SeedyColors.Text3)
                if (info?.seerr == true) NameTag("Seerr", tint = 0.08f)
            }
            SText(t(info?.help ?: ""), size = 12.sp, color = SeedyColors.Text3)
            val range = info?.count
            if (range != null) {
                val n = r.count ?: 1
                Box(Modifier.padding(top = 6.dp)) {
                    StepperPill(t("Up to {n}", "n" to n), { onCount(maxOf(range.first, n - 1)) }, { onCount(minOf(range.last, n + 1)) }, n > range.first, n < range.last)
                }
            }
        }
        if (!locked) BoardSwitch(r.enabled, onToggle)
    }
}

/** Adds a starting genre: the library's genres not chosen yet, with a search when there are many. */
@Composable
private fun GenreSheet(chosen: List<String>, actions: HomeAdminActions, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var all by remember { mutableStateOf<List<String>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val list = actions.genres()
        if (list == null) failed = true else all = list
    }
    val taken = chosen.map { it.lowercase() }.toSet()
    val options = all.orEmpty().filter { it.lowercase() !in taken && it.contains(q.trim(), ignoreCase = true) }
    BottomSheet(onDismiss) {
        SheetHead(t("Add a starting genre"))
        if ((all?.size ?: 0) > 8) BoardField(q, { q = it }, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp), placeholder = t("Find a genre"))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            when {
                failed -> SText(t("Couldn’t load genres"), Modifier.padding(12.dp), size = 13.sp, color = SeedyColors.Danger)
                all == null -> SText(t("Loading genres…"), Modifier.padding(12.dp), size = 13.sp, color = SeedyColors.Text3)
                options.isEmpty() -> SText(if (q.isNotBlank()) t("No genre matches") else t("Every genre is already added"), Modifier.padding(12.dp), size = 13.sp, color = SeedyColors.Text3)
                else -> for (g in options) PickOption(g, false) { onAdd(g); onDismiss() }
            }
        }
    }
}

/** Adds titles to Featured: a search over movies and shows (the newest when it's empty); tapping one adds or removes it. */
@Composable
private fun TitleSheet(picks: List<String>, actions: HomeAdminActions, onToggle: (PickItem) -> Unit, onDismiss: () -> Unit) {
    var term by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<PickItem>?>(null) }
    var failed by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    LaunchedEffect(term) {
        if (term.isNotBlank()) delay(250)
        val list = actions.titles(term)
        failed = list == null
        items = list
    }
    val n = picks.size
    BottomSheet(onDismiss) {
        SheetHead(t("Add a title"), (if (n > 0) tn(n, "{n} pick so far.", "{n} picks so far.") + " " else "") + t("Picks show first in the Featured hero."))
        BoardField(term, { term = it }, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp), placeholder = t("Search movies and shows"))
        SText(
            if (term.isBlank()) t("Recently added") else t("Results"),
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp), size = 12.sp, weight = 800, tracking = 0.1f, color = SeedyColors.Text3, uppercase = true,
        )
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp)) {
            val list = items
            when {
                failed -> SText(t("Couldn’t search the library. Try again in a moment."), Modifier.padding(12.dp), size = 13.sp, color = SeedyColors.Text3)
                list == null -> repeat(4) { Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(58.dp).clip(RoundedCornerShape(13.dp)).background(SeedyColors.Surface)) }
                list.isEmpty() -> SText(t("Nothing in the library matches “{term}”.", "term" to term.trim()), Modifier.padding(12.dp), size = 13.sp, color = SeedyColors.Text3)
                else -> for (it in list) {
                    val on = it.id in picks
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(if (on) SeedyColors.Surface else Color.Transparent).tap { onToggle(it) }.padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Thumb(it.art, 80.dp, fallback = SeedyColors.Surface2)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            SText(it.title, size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            SText(it.meta, size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
                        }
                        if (on) SIcon(WebIcons.Check, Modifier.padding(end = 8.dp), size = 20.dp, color = accent, stroke = 2.6f)
                    }
                }
            }
        }
        SheetFoot { PillButton(t("Done"), onDismiss, style = PillStyle.Light) }
    }
}

/** "− Up to 6 +": a value with a step either way. */
@Composable
private fun StepperPill(label: String, onDec: () -> Unit, onInc: () -> Unit, decEnabled: Boolean = true, incEnabled: Boolean = true) {
    Row(Modifier.height(32.dp).clip(CircleShape).background(SeedyColors.Surface).padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
        StepButton(WebIcons.Minus, decEnabled, onDec)
        SText(label, Modifier.padding(horizontal = 6.dp), size = 12.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
        StepButton(WebIcons.Plus, incEnabled, onInc)
    }
}

@Composable
private fun StepButton(icon: IconDef, enabled: Boolean, onClick: () -> Unit) =
    RoundButton(icon, onClick, size = 28.dp, iconSize = 13.dp, background = SeedyColors.Surface2, tint = if (enabled) SeedyColors.White else SeedyColors.Text3, enabled = enabled, dim = 1f)
