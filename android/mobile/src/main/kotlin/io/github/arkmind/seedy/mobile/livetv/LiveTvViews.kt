package io.github.arkmind.seedy.mobile.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.feature.LiveTvViewModel
import io.github.arkmind.seedy.core.format.dayMonth
import io.github.arkmind.seedy.core.format.join
import io.github.arkmind.seedy.core.format.runtime
import io.github.arkmind.seedy.core.format.weekdayDate
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.livetv.LiveChannel
import io.github.arkmind.seedy.core.livetv.LiveTv
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.components.ChannelLogo
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.IconPart
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

private val Live = Color(0xFFD63C3C)
private val RecRed = Color(0xFFFF5C5C)
private val FilledHeart = IconDef(0f, listOf(IconPart("M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z", filled = true)))

/** Live TV's four views, in the boards' order. */
enum class LiveView(val label: String) { OnNow("On now"), Guide("Guide"), Channels("Channels"), Recordings("Recordings") }

/** The views' pills under the title (a red dot on Recordings while something records). */
@Composable
fun LiveTabs(current: LiveView, recording: Boolean, onSelect: (LiveView) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (v in LiveView.entries) {
            val on = v == current
            Row(
                Modifier.height(36.dp).clip(CircleShape).background(if (on) SeedyColors.Inverse else SeedyColors.Surface).tap { onSelect(v) }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SText(t(v.label), size = 13.sp, weight = if (on) 800 else 600, color = if (on) SeedyColors.InkInverse else SeedyColors.Text, maxLines = 1)
                if (v == LiveView.Recordings && recording) Box(Modifier.size(7.dp).background(RecRed, CircleShape))
            }
        }
    }
}

/** The boards' "♥ Favorites ⌄" filter. */
@Composable
private fun FavoritesFilter(on: Boolean, onChange: (Boolean) -> Unit, boxed: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.height(if (boxed) 40.dp else 36.dp).clip(RoundedCornerShape(12.dp)).background(if (boxed) SeedyColors.Row else Color.Transparent)
                .tap { open = true }.padding(start = if (boxed) 12.dp else 4.dp, end = if (boxed) 10.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (on) SIcon(FilledHeart, size = 13.dp, color = LocalAccent.current)
            SText(if (on) t("Favorites") else t("All channels"), size = 13.sp, weight = 700, color = if (boxed) SeedyColors.Text else SeedyColors.Text2)
            SIcon(WebIcons.ChevronDown, size = 14.dp, color = if (boxed) SeedyColors.Text else SeedyColors.Text2, stroke = 2.4f)
        }
        DropdownMenu(open, { open = false }, containerColor = SeedyColors.Row, shape = RoundedCornerShape(12.dp)) {
            DropdownMenuItem(text = { SText(t("Favorites"), size = 14.sp, weight = if (on) 800 else 600) }, onClick = { open = false; onChange(true) })
            DropdownMenuItem(text = { SText(t("All channels"), size = 14.sp, weight = if (!on) 800 else 600) }, onClick = { open = false; onChange(false) })
        }
    }
}

/** Board "Android · Live TV": the last channel watched as a big card, then every channel with what's on. */
@Composable
fun OnNowView(state: LiveTvViewModel.State, bottom: Dp, onFavoritesOnly: (Boolean) -> Unit, onWatch: (LiveChannel) -> Unit) {
    val now = state.now
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 16.dp)) {
        state.lastWatched?.let { last ->
            item(key = "last") { LastWatched(last, now) { onWatch(last) } }
        }
        item(key = "head") {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                SText(t("On now"), Modifier.weight(1f), size = 16.sp, weight = 800, color = SeedyColors.White)
                FavoritesFilter(state.favoritesOnly, onFavoritesOnly, boxed = false)
            }
        }
        // The last channel watched has its own card above.
        items(state.shown.filter { it.id != state.lastChannelId }, key = { it.id }) { c -> ChannelRow(c, state, now, Modifier.padding(horizontal = 12.dp)) { onWatch(c) } }
    }
}

@Composable
private fun LastWatched(c: LiveChannel, now: LocalDateTime, onClick: () -> Unit) {
    val viewer = LocalViewer.current
    val program = c.current
    Box(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0B120E)).tap(onClick = onClick),
    ) {
        val art = program?.let { viewer.images?.landscape(it, 720) } ?: viewer.images?.backdrop(c.item, 720)
        Artwork(art, Modifier.matchParentSize(), placeholder = Color(0xFF0B120E))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.4f to Color.Transparent, 1f to Color(0xE60A0A0A))))
        LiveBadge(Modifier.align(Alignment.TopStart).padding(10.dp))
        ChannelLogo(c.item, 32.dp, 9.dp, 11.sp, Modifier.align(Alignment.TopEnd).padding(10.dp))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SText(t("Last watched · {channel}", "channel" to c.label), size = 11.sp, weight = 800, tracking = 0.08f, color = SeedyColors.TextHero, uppercase = true, maxLines = 1)
            SText(program?.let(LiveTv::programTitle) ?: c.label, size = 17.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
            if (program != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.24f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(LiveTv.fraction(program, now)).background(Color.White))
                    }
                    SText(LiveTv.leftLabel(program, now), size = 11.sp, color = SeedyColors.TextHero)
                }
            }
        }
    }
}

@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(7.dp)).background(Live).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(5.dp).background(Color.White, CircleShape))
        SText(t("Live"), size = 11.sp, weight = 800, tracking = 0.06f, color = Color.White, uppercase = true)
    }
}

@Composable
private fun ChannelRow(c: LiveChannel, state: LiveTvViewModel.State, now: LocalDateTime, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val program = c.current?.takeIf { LiveTv.isAiring(it, now) }
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp)).tap(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChannelLogo(c.item, 42.dp, 11.dp, 12.sp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SText(program?.let(LiveTv::programTitle) ?: c.label, Modifier.weight(1f, fill = false), size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
                if (LiveTv.recState(program, state.timers) == "now") Box(Modifier.size(7.dp).background(RecRed, CircleShape))
            }
            SText(join(c.number, c.label, program?.let { LiveTv.leftLabel(it, now) }), size = 11.sp, color = SeedyColors.Text3, maxLines = 1)
            if (program != null) {
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(SeedyColors.Surface2)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(LiveTv.fraction(program, now)).background(Color.White))
                }
            }
        }
        trailing?.invoke()
    }
}

/**
 * Board "Android · Live TV guide": a day and favourites filter, the grid of channels and programmes (90 minutes
 * across, swipe for more), and the chosen programme's card with Record and Watch.
 */
@Composable
fun GuideView(
    state: LiveTvViewModel.State,
    bottom: Dp,
    selected: Pair<LiveChannel, BaseItemDto>?,
    onSelect: (LiveChannel, BaseItemDto) -> Unit,
    onDay: (LocalDate) -> Unit,
    onFavoritesOnly: (Boolean) -> Unit,
    onRecord: (BaseItemDto) -> Unit,
    onWatch: (LiveChannel) -> Unit,
) {
    val accent = LocalAccent.current
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DayPicker(state.day, onDay)
                FavoritesFilter(state.favoritesOnly, onFavoritesOnly, boxed = true)
                Spacer(Modifier.weight(1f))
                SText(
                    t("Now"),
                    Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Row).tap { onDay(LocalDate.now()) }.padding(horizontal = 12.dp, vertical = 11.dp),
                    size = 13.sp, weight = 700, color = SeedyColors.Text,
                )
            }
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp).weight(1f, fill = false).clip(RoundedCornerShape(14.dp)).background(SeedyColors.Panel)) {
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val area = maxWidth - 58.dp
                    val perMinute = area / 90f
                    val span = Duration.between(state.windowStart, state.day.plusDays(1).atTime(5, 0)).toMinutes().coerceAtLeast(90)
                    val total = perMinute * span.toFloat()
                    val nowOffset = perMinute * Duration.between(state.windowStart, state.now).toMinutes().toFloat()
                    Column {
                        // Header: the half hours.
                        // The board's rows are 32 and 58 px plus a 1 px border under each.
                        Row(Modifier.fillMaxWidth().height(33.dp).drawBehind { drawLine(Color(0xFF1F1F1F), Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }) {
                            Box(Modifier.width(58.dp).fillMaxHeight().drawBehind { drawLine(Color(0xFF1F1F1F), Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx()) })
                            Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
                                Box(Modifier.width(total).fillMaxHeight()) {
                                    var m = 0L
                                    while (m < span) {
                                        val label = LiveTv.clockOf(state.windowStart.plusMinutes(m))
                                        Box(
                                            Modifier.offset(x = perMinute * m.toFloat()).fillMaxHeight()
                                                .drawBehind { drawLine(Color(0xFF1F1F1F), Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) }
                                                .padding(start = 6.dp),
                                            contentAlignment = Alignment.CenterStart,
                                        ) { SText(label, size = 11.sp, weight = 700, color = SeedyColors.Text2) }
                                        m += 30
                                    }
                                }
                            }
                        }
                        LazyColumn(Modifier.fillMaxWidth()) {
                            items(state.shown, key = { it.id }) { c ->
                                Row(Modifier.fillMaxWidth().height(59.dp).drawBehind { drawLine(SeedyColors.Row, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }) {
                                    Column(
                                        Modifier.width(58.dp).fillMaxHeight().drawBehind { drawLine(Color(0xFF1F1F1F), Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx()) }.tap { onWatch(c) },
                                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                                    ) {
                                        ChannelLogo(c.item, 32.dp, 9.dp, 10.sp)
                                        SText(c.number, size = 9.sp, weight = 700, color = SeedyColors.Text3)
                                    }
                                    Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
                                        Box(Modifier.width(total).fillMaxHeight()) {
                                            for (p in state.programs[c.id].orEmpty()) {
                                                val s = p.startDate ?: continue
                                                val e = p.endDate ?: continue
                                                val a = Duration.between(state.windowStart, s).toMinutes().coerceAtLeast(0)
                                                val b = Duration.between(state.windowStart, e).toMinutes().coerceAtMost(span)
                                                if (b <= a) continue
                                                val sel = selected?.second?.id == p.id
                                                val airing = LiveTv.isAiring(p, state.now)
                                                Box(Modifier.offset(x = perMinute * a.toFloat()).width(perMinute * (b - a).toFloat()).fillMaxHeight().padding(start = 2.dp, end = 2.dp, top = 4.dp, bottom = 5.dp)) {
                                                    Column(
                                                        Modifier.fillMaxSize().clip(RoundedCornerShape(9.dp))
                                                            .background(if (sel) SeedyColors.Surface2 else if (airing) Color(0xFF232323) else SeedyColors.Row)
                                                            .then(if (sel) Modifier.border(2.dp, accent, RoundedCornerShape(9.dp)) else Modifier)
                                                            .tap { onSelect(c, p) }.padding(horizontal = 7.dp),
                                                        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
                                                    ) {
                                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                            if (LiveTv.recState(p, state.timers) != null) Box(Modifier.size(6.dp).background(RecRed, CircleShape))
                                                            SText(LiveTv.programTitle(p), size = 12.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
                                                        }
                                                        SText(LiveTv.timeRange(p), size = 10.sp, color = SeedyColors.Text3, maxLines = 1)
                                                    }
                                                }
                                            }
                                            // What has aired is dimmed; the accent line is now.
                                            if (nowOffset > 0.dp) {
                                                Box(Modifier.width(nowOffset).fillMaxHeight().background(Color(0x800E0E0E)))
                                                Box(Modifier.offset(x = nowOffset - 1.dp).width(2.dp).fillMaxHeight().background(accent))
                                            }
                                        }
                                    }
                                }
                            }
                            item { Spacer(Modifier.height(bottom + 96.dp)) }
                        }
                    }
                    LaunchedEffect(state.windowStart) { scroll.scrollTo(0) }
                }
            }
        }
        selected?.let { (c, p) ->
            ProgramCard(c, p, state, Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = bottom + 10.dp), onRecord, onWatch)
        }
    }
}

@Composable
private fun DayPicker(day: LocalDate, onDay: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    fun label(d: LocalDate) = when (d) {
        today -> t("Today, {day}", "day" to weekdayDate(d).substringBeforeLast(' '))
        today.plusDays(1) -> t("Tomorrow, {day}", "day" to weekdayDate(d).substringBeforeLast(' '))
        else -> weekdayDate(d)
    }
    Box {
        Row(
            Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Row).tap { open = true }.padding(start = 12.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SText(label(day), size = 13.sp, weight = 700, color = SeedyColors.Text, maxLines = 1)
            SIcon(WebIcons.ChevronDown, Modifier.padding(0.dp), size = 14.dp, color = SeedyColors.Text.copy(alpha = 0.7f), stroke = 2.4f)
        }
        DropdownMenu(open, { open = false }, containerColor = SeedyColors.Row, shape = RoundedCornerShape(12.dp)) {
            for (i in 0..6) {
                val d = today.plusDays(i.toLong())
                DropdownMenuItem(text = { SText(label(d), size = 14.sp, weight = if (d == day) 800 else 600) }, onClick = { open = false; onDay(d) })
            }
        }
    }
}

@Composable
private fun ProgramCard(c: LiveChannel, p: BaseItemDto, state: LiveTvViewModel.State, modifier: Modifier, onRecord: (BaseItemDto) -> Unit, onWatch: (LiveChannel) -> Unit) {
    val airing = LiveTv.isAiring(p, state.now)
    val rec = LiveTv.recState(p, state.timers)
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SeedyColors.Row).border(1.dp, Color(0xFF262626), RoundedCornerShape(16.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChannelLogo(c.item, 40.dp, 11.dp, 12.sp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(LiveTv.programTitle(p), size = 14.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
            SText(join(c.label, LiveTv.timeRange(p), if (airing) LiveTv.leftLabel(p, state.now) else null), size = 11.sp, color = SeedyColors.Text2, maxLines = 1)
        }
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF262626)).tap { onRecord(p) },
            contentAlignment = Alignment.Center,
        ) {
            if (rec != null) SIcon(WebIcons.Close, size = 16.dp, color = RecRed, stroke = 2.6f)
            else Box(Modifier.size(13.dp).background(RecRed, CircleShape))
        }
        if (airing) {
            Row(
                Modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Inverse).tap { onWatch(c) }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SIcon(WebIcons.Play, size = 15.dp, color = SeedyColors.InkInverse)
                SText(t("Watch"), size = 13.sp, weight = 800, color = SeedyColors.InkInverse)
            }
        }
    }
}

/** Every channel, with a heart to keep it in Favorites (no board: built from "On now"'s rows). */
@Composable
fun ChannelsView(state: LiveTvViewModel.State, bottom: Dp, onFavorite: (LiveChannel) -> Unit, onWatch: (LiveChannel) -> Unit) {
    val accent = LocalAccent.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = bottom + 16.dp)) {
        items(state.channels, key = { it.id }) { c ->
            ChannelRow(c, state, state.now, trailing = {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).tap { onFavorite(c) }, contentAlignment = Alignment.Center) {
                    SIcon(if (c.favorite) FilledHeart else WebIcons.Heart, size = 20.dp, color = if (c.favorite) accent else SeedyColors.Text2)
                }
            }) { onWatch(c) }
        }
    }
}

/** What's been recorded (no board: built from the episode lists). */
@Composable
fun RecordingsView(state: LiveTvViewModel.State, bottom: Dp, onPlay: (BaseItemDto) -> Unit) {
    val viewer = LocalViewer.current
    if (state.recordings.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(bottom = bottom), contentAlignment = Alignment.Center) {
            SText(t("Nothing recorded yet. Pick a programme in the guide and tap the red dot."), Modifier.padding(32.dp), size = 14.sp, color = SeedyColors.Text2, align = androidx.compose.ui.text.style.TextAlign.Center)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = bottom + 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(state.recordings, key = { it.id }) { r ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).tap { onPlay(r) }.padding(6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(9.dp))) {
                    Artwork(viewer.images?.landscape(r, 240), Modifier.matchParentSize())
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SText(LiveTv.programTitle(r), size = 13.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
                    SText(join(r.channelName, r.startDate?.toLocalDate()?.let(::dayMonth), r.runtime), size = 11.sp, color = SeedyColors.Text3, maxLines = 1)
                }
            }
        }
    }
}
