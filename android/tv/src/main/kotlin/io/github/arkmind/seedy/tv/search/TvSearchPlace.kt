package io.github.arkmind.seedy.tv.search

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import io.github.arkmind.seedy.core.browse.Hit
import io.github.arkmind.seedy.core.browse.HitKind
import io.github.arkmind.seedy.core.browse.marks
import io.github.arkmind.seedy.core.browse.rowMeta
import io.github.arkmind.seedy.core.feature.SearchViewModel
import io.github.arkmind.seedy.core.format.duration
import io.github.arkmind.seedy.core.format.episodeShort
import io.github.arkmind.seedy.core.format.join
import io.github.arkmind.seedy.core.format.remainingSeconds
import io.github.arkmind.seedy.core.format.seasonsLabel
import io.github.arkmind.seedy.core.format.ticksToSeconds
import io.github.arkmind.seedy.core.format.timeLeft
import io.github.arkmind.seedy.core.format.yearRange
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.seedy.SeerrItem
import io.github.arkmind.seedy.core.seedy.tmdbImage
import io.github.arkmind.seedy.tv.browse.TvTab
import io.github.arkmind.seedy.tv.browse.ringRoom
import io.github.arkmind.seedy.tv.nav.LocalTvNav
import io.github.arkmind.seedy.tv.nav.TvRoute
import io.github.arkmind.seedy.tv.parts.TvPressable
import io.github.arkmind.seedy.tv.parts.bp
import io.github.arkmind.seedy.tv.parts.bsp
import io.github.arkmind.seedy.tv.parts.focusRing
import io.github.arkmind.seedy.tv.parts.focusScale
import io.github.arkmind.seedy.tv.parts.focusSoon
import io.github.arkmind.seedy.tv.shell.RailInset
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.NotoLineRatio
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID
import kotlin.math.roundToInt

/** Board "Android TV · Search". */
@Composable
fun TvSearchPlace() {
    val session = currentSession()
    val nav = LocalTvNav.current
    val vm = viewModel { SearchViewModel(session, channels = true) }
    val state by vm.state.collectAsStateWithLifecycle()
    // The mic button: Android's speech recognizer, when the TV has one.
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(vm::setText)
    }
    TvSearchScreen(
        state,
        TvSearchActions(
            onText = vm::setText,
            onMic = {
                try {
                    voice.launch(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, t("Say a title")),
                    )
                } catch (_: ActivityNotFoundException) {
                }
            },
            onOpen = { vm.remember(); nav.open(it) },
            onSeerr = vm::askSeerr,
            onSeerrTitle = { s ->
                vm.remember()
                val local = s.jellyfinItemId?.let { runCatching { UUID.fromString(dashed(it)) }.getOrNull() }
                if (local != null) nav.go(TvRoute.Details(local)) else nav.go(TvRoute.SeerrTitle(s.mediaType, s.tmdbId))
            },
        ),
    )
}

/** Jellyfin ids come without dashes. */
fun dashed(id: String): String =
    if (id.length == 32 && '-' !in id) "${id.substring(0, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}" else id

class TvSearchActions(
    val onText: (String) -> Unit = {},
    val onMic: () -> Unit = {},
    val onOpen: (BaseItemDto) -> Unit = {},
    val onSeerr: () -> Unit = {},
    val onSeerrTitle: (SeerrItem) -> Unit = {},
)

private val Keys = "abcdefghijklmnopqrstuvwxyz0123456789".toList()

/**
 * The query over the remote's keyboard (and the mic) on the left; on the right the top hit, the next three results,
 * episodes and channels, and "Not here? Find it on Seerr".
 */
@Composable
fun TvSearchScreen(state: SearchViewModel.State, actions: TvSearchActions, firstKey: FocusRequester = remember { FocusRequester() }, focusKey: Char = 'a') {
    Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
        Keyboard(state.text, actions, firstKey, focusKey, Modifier.padding(start = RailInset, top = 96.bp).width(520.bp))
        Column(
            Modifier.padding(start = 780.bp, end = 96.bp, top = 96.bp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 96.bp),
            verticalArrangement = Arrangement.spacedBy(22.bp),
        ) {
            val r = state.results?.takeIf { !state.idle }
            when {
                state.idle -> Idle(state, actions)
                state.error -> SText(t("Search didn't work"), size = 22.bsp, weight = 700, color = SeedyColors.Text2)
                r == null -> SText(t("Searching…"), size = 22.bsp, weight = 700, color = SeedyColors.Text2)
                r.isEmpty -> {
                    SText(t("Nothing on the server for “{q}”", "q" to state.query), size = 22.bsp, weight = 700, color = SeedyColors.Text2)
                    Seerr(state, actions)
                }
                else -> Results(state, actions)
            }
        }
    }
    LaunchedEffect(Unit) { firstKey.focusSoon() }
}

@Composable
private fun Keyboard(text: String, actions: TvSearchActions, firstKey: FocusRequester, focusKey: Char, modifier: Modifier) {
    val accent = LocalAccent.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(18.bp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.bp)) {
            Row(
                Modifier.weight(1f).height(76.bp).clip(RoundedCornerShape(20.bp)).background(SeedyColors.Row).padding(horizontal = 22.bp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.bp),
            ) {
                SIcon(WebIcons.Search, size = 28.bp, color = SeedyColors.Text2, stroke = 2.2f)
                if (text.isNotEmpty()) SText(text, Modifier.weight(1f, fill = false), size = 30.bsp, weight = 800, color = SeedyColors.White, maxLines = 1)
                Box(Modifier.offset(x = (-8).bp).size(3.bp, 34.bp).clip(RoundedCornerShape(2.bp)).background(accent))
            }
            TvPressable(actions.onMic) { focused ->
                Box(
                    Modifier.focusScale(focused, 1.08f).focusRing(focused, radius = 20.bp, gap = 4.bp, ring = 4.bp).size(76.bp).clip(RoundedCornerShape(20.bp))
                        .background(if (focused) SeedyColors.Inverse else Color.White.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center,
                ) {
                    SIcon(AppIcons.Mic, size = 30.bp, color = if (focused) SeedyColors.InkInverse else SeedyColors.White)
                }
            }
        }
        SText(t("Or hold the mic button on the remote and say a title"), size = 18.bsp, color = SeedyColors.Text3)
        Column(Modifier.padding(top = 8.bp), verticalArrangement = Arrangement.spacedBy(10.bp)) {
            for (row in Keys.chunked(6)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.bp)) {
                    for (c in row) Key(c.toString(), 76.bp, 24.bsp, { actions.onText(text + c) }, if (c == focusKey) Modifier.focusRequester(firstKey) else Modifier)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.bp)) {
                Key(t("Space"), 162.bp, 19.bsp, { if (text.isNotEmpty() && !text.endsWith(" ")) actions.onText("$text ") })
                Key(t("Delete"), 162.bp, 19.bsp, { actions.onText(text.dropLast(1)) })
                Key(t("Clear"), 162.bp, 19.bsp, { actions.onText("") })
            }
        }
    }
}

@Composable
private fun Key(label: String, width: Dp, size: TextUnit, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TvPressable(onClick, modifier) { focused ->
        Box(
            Modifier.focusScale(focused, 1.08f).focusRing(focused, radius = 16.bp, gap = 4.bp, ring = 4.bp).size(width, 68.bp).clip(RoundedCornerShape(16.bp))
                .background(if (focused) SeedyColors.Inverse else Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            SText(label, size = size, weight = 700, color = if (focused) SeedyColors.InkInverse else SeedyColors.White, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Idle(state: SearchViewModel.State, actions: TvSearchActions) {
    SText(t("Type a title, a person or a genre"), size = 22.bsp, weight = 700, color = SeedyColors.Text2)
    if (state.recent.isNotEmpty()) {
        SText(t("Recent searches"), size = 22.bsp, weight = 800, color = SeedyColors.White)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.bp), verticalArrangement = Arrangement.spacedBy(10.bp)) {
            for (q in state.recent) TvTab(q, false, { actions.onText(q) })
        }
    }
    if (state.genres.isNotEmpty()) {
        SText(t("Browse by genre"), Modifier.padding(top = 8.bp), size = 22.bsp, weight = 800, color = SeedyColors.White)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.bp), verticalArrangement = Arrangement.spacedBy(10.bp)) {
            for (g in state.genres) TvTab(g, false, { actions.onText(g) })
        }
    }
}

@Composable
private fun Results(state: SearchViewModel.State, actions: TvSearchActions) {
    val r = state.results ?: return
    SText(t("Results for “{q}”", "q" to state.query), size = 22.bsp, weight = 700, color = SeedyColors.Text2)
    val best = r.best
    val side = (r.titles + r.people).filter { it != best }.sortedByDescending { it.score }.take(3)
    Row(horizontalArrangement = Arrangement.spacedBy(32.bp)) {
        if (best != null) TopHit(best, state.top, actions)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.bp)) {
            for (h in side) SideRow(h, state, actions)
        }
    }
    val more = r.episodes.filter { it != best } + r.channels
    if (more.isNotEmpty()) {
        SText(t("Episodes and channels"), Modifier.padding(top = 8.bp), size = 22.bsp, weight = 800, color = SeedyColors.White)
        LazyRow(
            Modifier.fillMaxWidth().ringRoom(10.bp), contentPadding = PaddingValues(10.bp),
            horizontalArrangement = Arrangement.spacedBy(32.bp),
        ) {
            items(more, key = { it.item.id }) { h -> SmallCard(h, actions) }
        }
    }
    Seerr(state, actions)
}

/** The best match: its backdrop, "TOP HIT", the name with the match in the accent, and what Play would do. */
@Composable
private fun TopHit(hit: Hit, top: SearchViewModel.TopPlay?, actions: TvSearchActions) {
    val viewer = LocalViewer.current
    val item = hit.item
    TvPressable({ actions.onOpen(item) }, Modifier.width(560.bp)) { focused ->
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).focusRing(focused, radius = 22.bp, gap = 4.bp, ring = 5.bp).clip(RoundedCornerShape(22.bp)).background(Color(0xFF1A232E))) {
            viewer.images?.backdrop(item, 1280)?.let { AsyncImage(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to SeedyColors.Bg.copy(alpha = 0.92f))))
            SText(
                t("Top hit"), Modifier.padding(20.bp).clip(RoundedCornerShape(10.bp)).background(SeedyColors.Bg.copy(alpha = 0.8f)).padding(horizontal = 12.bp, vertical = 5.bp),
                size = 16.bsp, weight = 800, tracking = 0.08f, color = SeedyColors.Text, uppercase = true,
            )
            Column(Modifier.align(Alignment.BottomStart).padding(start = 28.bp, end = 28.bp, bottom = 26.bp), verticalArrangement = Arrangement.spacedBy(4.bp)) {
                Marked(item.name.orEmpty(), hit.match, 40.bsp, 900, SeedyColors.White, uppercase = true, lineHeight = 0.95f)
                SText(tvTopMeta(item, top), size = 18.bsp, color = SeedyColors.TextHero, maxLines = 1)
            }
        }
    }
}

/** "Show · Resume S3 · E2 · 34 min left", "Movie · 2021 · 1 h 49 min". */
fun tvTopMeta(item: BaseItemDto, top: SearchViewModel.TopPlay?): String = when (item.type) {
    BaseItemKind.SERIES -> {
        val ep = top?.target?.takeIf { top.resume }
        if (ep != null) join(t("Show"), t("Resume {ep}", "ep" to ep.episodeShort), timeLeft(ep.remainingSeconds)) else join(t("Show"), item.yearRange, item.seasonsLabel)
    }
    else -> sideMeta(item)
}

/** The side list's line: "Movie · 2021 · 1 h 49 min", "Movie · 2019 · not watched", "Show · 2024–2026 · 3 seasons". */
fun sideMeta(item: BaseItemDto): String {
    val ud = item.userData
    val run = item.runTimeTicks?.let { duration(ticksToSeconds(it)) }
    return when (item.type) {
        BaseItemKind.SERIES -> join(t("Show"), item.yearRange, item.seasonsLabel)
        BaseItemKind.BOX_SET, BaseItemKind.EPISODE, BaseItemKind.TV_CHANNEL -> rowMeta(item)
        else -> join(
            t("Movie"), item.productionYear,
            when {
                ud != null && ud.playbackPositionTicks > 0 && !ud.played -> timeLeft(item.remainingSeconds)
                ud != null && !ud.played -> t("not watched")
                else -> run
            },
        )
    }
}

@Composable
private fun SideRow(hit: Hit, state: SearchViewModel.State, actions: TvSearchActions) {
    val viewer = LocalViewer.current
    val item = hit.item
    TvPressable({ actions.onOpen(item) }) { focused ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.bp)) {
            val person = hit.kind == HitKind.Person
            Box(
                Modifier.size(if (person) 90.bp else 160.bp, 90.bp).focusRing(focused, radius = if (person) 45.bp else 14.bp, gap = 4.bp, ring = 4.bp)
                    .clip(if (person) CircleShape else RoundedCornerShape(14.bp)).background(if (person) Color(0xFF3D2436) else Color(0xFF2F2840)),
                contentAlignment = Alignment.Center,
            ) {
                SText(initials(item.name.orEmpty()), size = 26.bsp, weight = 900, color = if (person) Color(0xFFFFB3E6) else Color(0xFFE3C4FF))
                val url = if (person) viewer.images?.primary(item, 200) else viewer.images?.landscape(item, 320, viewer.episodeImages)
                url?.let { AsyncImage(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.bp)) {
                Marked(item.name.orEmpty(), hit.match, 21.bsp, 700, SeedyColors.White)
                SText(if (person) state.personLines[item.id].orEmpty().ifEmpty { " " } else sideMeta(item), size = 17.bsp, color = SeedyColors.Text3, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SmallCard(hit: Hit, actions: TvSearchActions) {
    val viewer = LocalViewer.current
    val item = hit.item
    val channel = hit.kind == HitKind.Channel
    TvPressable({ actions.onOpen(item) }, Modifier.width(280.bp)) { focused ->
        Column(verticalArrangement = Arrangement.spacedBy(10.bp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).focusRing(focused, radius = 18.bp, gap = 4.bp, ring = 4.bp).clip(RoundedCornerShape(18.bp)).background(if (channel) Color(0xFF2F4A7A) else Color(0xFF1F3A3C))) {
                val url = if (channel) viewer.images?.primary(item, 320) else viewer.images?.landscape(item, 480, viewer.episodeImages)
                Artwork(url, Modifier.matchParentSize(), placeholder = Color.Transparent, scale = if (channel) ContentScale.Fit else ContentScale.Crop)
                if (channel) {
                    SText(
                        t("LIVE"), Modifier.padding(12.bp).clip(RoundedCornerShape(8.bp)).background(Color(0xFFD63C3C)).padding(horizontal = 10.bp, vertical = 4.bp),
                        size = 16.bsp, weight = 800, tracking = 0.06f, color = SeedyColors.White,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.bp)) {
                val lead = if (item.type == BaseItemKind.EPISODE && item.seriesName != null) "${item.seriesName} · " else ""
                Marked(item.name.orEmpty(), hit.match, 19.bsp, 700, SeedyColors.White, lead = lead)
                SText(rowMeta(item), size = 16.bsp, color = SeedyColors.Text3, maxLines = 1)
            }
        }
    }
}

/** "Not here? Find it on Seerr", then Seerr's titles once asked (straight away when the library has nothing). */
@Composable
private fun Seerr(state: SearchViewModel.State, actions: TvSearchActions) {
    if (!state.seerrOn) return
    val s = state.seerr
    if (s == null) {
        TvPressable(actions.onSeerr, Modifier.padding(top = 16.bp)) { focused ->
            Row(
                Modifier.fillMaxWidth().focusRing(focused, radius = 22.bp, gap = 4.bp, ring = 5.bp).clip(RoundedCornerShape(22.bp))
                    .background(if (focused) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.07f)).padding(horizontal = 28.bp, vertical = 22.bp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.bp),
            ) {
                Box(Modifier.size(60.bp).clip(RoundedCornerShape(16.bp)).background(Color.White.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                    SIcon(WebIcons.Request, size = 30.bp, color = LocalAccent.current, stroke = 2.2f)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.bp)) {
                    SText(t("Not here? Find “{q}” on Seerr", "q" to state.query), size = 22.bsp, weight = 800, color = SeedyColors.White, maxLines = 1)
                    SText(t("Request movies and shows the server doesn't have"), size = 18.bsp, color = SeedyColors.Text2, maxLines = 1)
                }
            }
        }
        return
    }
    SText(t("Request it"), Modifier.padding(top = 8.bp), size = 22.bsp, weight = 800, color = SeedyColors.White)
    when {
        s.loading -> SText(t("Asking Seerr…"), size = 19.bsp, color = SeedyColors.Text2)
        s.error -> SText(t("Seerr didn't answer, so requests can't be made right now."), size = 19.bsp, color = SeedyColors.Text2)
        s.items.isEmpty() -> SText(t("Seerr doesn’t know “{q}” either. Check the spelling, or try the original title.", "q" to state.query), size = 19.bsp, color = SeedyColors.Text2)
        else -> LazyRow(
            Modifier.fillMaxWidth().ringRoom(10.bp), contentPadding = PaddingValues(10.bp),
            horizontalArrangement = Arrangement.spacedBy(24.bp),
        ) {
            items(s.items, key = { "${it.mediaType}:${it.tmdbId}" }) { item -> Poster(item) { actions.onSeerrTitle(item) } }
        }
    }
}

/** A Seerr title: the 2:3 poster with its type, the name and year. */
@Composable
private fun Poster(item: SeerrItem, onClick: () -> Unit) {
    TvPressable(onClick, Modifier.width(196.bp)) { focused ->
        Column(verticalArrangement = Arrangement.spacedBy(10.bp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).focusRing(focused, radius = 16.bp, gap = 4.bp, ring = 5.bp).clip(RoundedCornerShape(16.bp))) {
                Artwork(tmdbImage(item.posterPath, "w342"), Modifier.matchParentSize())
                val tv = item.mediaType == "tv"
                SText(
                    if (tv) t("Series") else t("Movie"),
                    Modifier.padding(10.bp).clip(CircleShape).background(if (tv) SeedyColors.TypeSeries else SeedyColors.TypeMovie).padding(horizontal = 11.bp, vertical = 4.bp),
                    size = 13.bsp, weight = 800, tracking = 0.1f, color = SeedyColors.White, uppercase = true,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.bp)) {
                SText(item.title, size = 18.bsp, weight = 800, color = SeedyColors.White, maxLines = 1)
                SText(join(item.year, item.rating?.takeIf { it > 0 }?.let { "★ " + String.format(java.util.Locale.US, "%.1f", it) }), size = 16.bsp, color = SeedyColors.Text3, maxLines = 1)
            }
        }
    }
}

/** "IN" for Ines Northey. */
private fun initials(name: String): String = name.split(' ').filter { w -> w.any { it.isLetter() } }.take(2).joinToString("") { it.first().uppercase() }

/** [text] with the parts matching [query] in the accent; [lineHeight] below Noto's own trims the box the CSS way. */
@Composable
private fun Marked(text: String, query: String, size: TextUnit, weight: Int, color: Color, uppercase: Boolean = false, lineHeight: Float? = null, lead: String = "") {
    val accent = LocalAccent.current
    val shown: AnnotatedString = buildAnnotatedString {
        append(if (uppercase) lead.uppercase() else lead)
        for ((part, hit) in marks(text, query)) {
            val p = if (uppercase) part.uppercase() else part
            if (hit) withStyle(SpanStyle(color = accent)) { append(p) } else append(p)
        }
    }
    val trim = lineHeight?.takeIf { it < NotoLineRatio }
    BasicText(
        shown,
        if (trim != null) Modifier.layout { measurable, constraints ->
            val p = measurable.measure(constraints)
            val cut = ((NotoLineRatio - trim) * size.toPx()).coerceAtLeast(0f)
            layout(p.width, (p.height - cut).roundToInt()) { p.place(0, -(cut / 2).roundToInt()) }
        } else Modifier,
        style = BaseText.copy(fontSize = size, fontWeight = FontWeight(weight), color = color),
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}
