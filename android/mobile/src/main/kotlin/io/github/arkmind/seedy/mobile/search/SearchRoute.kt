package io.github.arkmind.seedy.mobile.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import io.github.arkmind.seedy.core.browse.Hit
import io.github.arkmind.seedy.core.browse.HitKind
import io.github.arkmind.seedy.core.browse.SearchResults
import io.github.arkmind.seedy.core.browse.rowMeta
import io.github.arkmind.seedy.core.feature.SearchViewModel
import io.github.arkmind.seedy.core.format.duration
import io.github.arkmind.seedy.core.format.episodeTag
import io.github.arkmind.seedy.core.format.join
import io.github.arkmind.seedy.core.format.remainingSeconds
import io.github.arkmind.seedy.core.format.seasonsLabel
import io.github.arkmind.seedy.core.format.ticksToSeconds
import io.github.arkmind.seedy.core.format.timeLeft
import io.github.arkmind.seedy.core.format.yearRange
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.seedy.SeerrItem
import io.github.arkmind.seedy.mobile.browse.Chip
import io.github.arkmind.seedy.mobile.browse.ChipRow
import io.github.arkmind.seedy.mobile.browse.MarkedText
import io.github.arkmind.seedy.mobile.browse.NoticeCard
import io.github.arkmind.seedy.mobile.browse.Portrait
import io.github.arkmind.seedy.mobile.browse.SectionLabel
import io.github.arkmind.seedy.mobile.browse.SeerrPoster
import io.github.arkmind.seedy.mobile.browse.initials
import io.github.arkmind.seedy.mobile.browse.marked
import io.github.arkmind.seedy.mobile.browse.topInset
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.nav.Route
import io.github.arkmind.seedy.mobile.parts.bleed
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/** Board "Android · Search". */
@Composable
fun SearchRoute() {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val vm = viewModel { SearchViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    SearchScreen(
        state,
        SearchActions(
            onText = vm::setText,
            onBack = nav::back,
            onFilter = vm::setFilter,
            onOpen = { vm.remember(); nav.open(it) },
            onPlay = { vm.remember(); nav.play(it) },
            onSeerr = vm::askSeerr,
            onSeerrTitle = { s ->
                vm.remember()
                val local = s.jellyfinItemId?.let { runCatching { UUID.fromString(dashed(it)) }.getOrNull() }
                if (local != null) nav.go(Route.Details(local)) else nav.go(Route.SeerrTitle(s.mediaType, s.tmdbId))
            },
            onRetry = vm::retry,
        ),
        bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
    )
}

/** Jellyfin ids come without dashes. */
fun dashed(id: String): String =
    if (id.length == 32 && '-' !in id) "${id.substring(0, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}" else id

class SearchActions(
    val onText: (String) -> Unit = {},
    val onBack: () -> Unit = {},
    val onFilter: (HitKind?) -> Unit = {},
    val onOpen: (BaseItemDto) -> Unit = {},
    val onPlay: (BaseItemDto) -> Unit = {},
    val onSeerr: () -> Unit = {},
    val onSeerrTitle: (SeerrItem) -> Unit = {},
    val onRetry: () -> Unit = {},
)

/** The board's kinds, in its chips' order. Labels are English, for t(). */
private val Kinds = listOf(
    HitKind.Show to "Shows", HitKind.Movie to "Movies", HitKind.Collection to "Collections", HitKind.Episode to "Episodes", HitKind.Person to "People",
)
private val GroupOrder = listOf(HitKind.Movie to "Movies", HitKind.Show to "Shows", HitKind.Collection to "Collections", HitKind.Episode to "Episodes", HitKind.Person to "People")

/**
 * The search field (with back and clear), the kinds as chips, then the top hit with its Play or Resume button, the
 * other results by kind, and "Not here? Find it on Seerr". Nothing typed yet: recent searches and genres.
 */
@Composable
fun SearchScreen(state: SearchViewModel.State, actions: SearchActions, bottomPadding: Dp, autofocus: Boolean = true) {
    val results = state.results?.takeIf { !state.idle }
    LazyColumn(Modifier.fillMaxSize().background(SeedyColors.Bg), contentPadding = PaddingValues(bottom = bottomPadding + 24.dp)) {
        item(key = "top") {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = topInset()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Field(state.text, actions, autofocus)
                if (results != null && !results.isEmpty) {
                    val all = results.all
                    ChipRow(Modifier.bleed(16.dp), side = 16.dp) {
                        item { Chip(t("All"), state.filter == null, { actions.onFilter(null) }, count = "${all.size}", weight = 700) }
                        for ((kind, label) in Kinds) {
                            val n = all.count { it.kind == kind }
                            if (n > 0) item(key = kind) { Chip(t(label), state.filter == kind, { actions.onFilter(kind) }, count = "$n", weight = 700) }
                        }
                    }
                }
            }
        }
        when {
            state.idle -> idle(state, actions)
            state.error -> item { Box(Modifier.padding(16.dp)) { Note(t("Search didn't work"), t("The server didn't answer. Check your connection and try again.")) } }
            results == null -> items(4) { RowSkeleton() }
            results.isEmpty -> empty(state, actions)
            else -> results(state, results, actions)
        }
    }
}


@Composable
private fun Field(text: String, actions: SearchActions, autofocus: Boolean) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focus = remember { FocusRequester() }
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(15.dp)
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(shape).background(SeedyColors.Row)
            .then(if (focused) Modifier.border(2.dp, accent, shape) else Modifier).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).tap(onClick = actions.onBack), contentAlignment = Alignment.Center) {
            SIcon(WebIcons.ArrowLeft, size = 22.dp, color = SeedyColors.White)
        }
        // The caret sits after what's typed, also when the text comes from elsewhere (a recent search, Clear).
        var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
        if (field.text != text) field = TextFieldValue(text, TextRange(text.length))
        Box(Modifier.weight(1f).padding(start = 2.dp), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) SText(t("Search movies, shows and people"), size = 15.sp, weight = 600, color = SeedyColors.Text3, maxLines = 1)
            BasicTextField(
                value = field,
                onValueChange = { field = it; if (it.text != text) actions.onText(it.text) },
                singleLine = true,
                interactionSource = interaction,
                textStyle = BaseText.copy(fontSize = 15.sp, fontWeight = FontWeight(600), color = SeedyColors.White),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {}),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (text.isNotEmpty()) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).tap { actions.onText("") }, contentAlignment = Alignment.Center) {
                SIcon(WebIcons.Close, size = 18.dp, color = SeedyColors.TextHero, stroke = 2.4f)
            }
        }
    }
    if (autofocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

private fun LazyListScope.results(state: SearchViewModel.State, r: SearchResults, actions: SearchActions) {
    val filter = state.filter
    val best = r.best?.takeIf { filter == null }
    val rest = r.all.filter { it != best && (filter == null || it.kind == filter) }
    if (best != null) {
        item(key = "top-label") { SectionLabel(t("Top hit"), Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp)) }
        item(key = "top-hit") { TopHit(best, state.top, actions, Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp)) }
    }
    var first = best == null
    for ((kind, label) in GroupOrder) {
        val hits = rest.filter { it.kind == kind }
        if (hits.isEmpty()) continue
        item(key = "g:$kind") { SectionLabel(t(label), Modifier.padding(start = 16.dp, end = 16.dp, top = if (first) 18.dp else 18.dp)) }
        first = false
        items(hits, key = { "h:${it.item.id}" }) { h -> ResultRow(h, state, actions) }
    }
    seerr(state, actions)
}

private fun LazyListScope.seerr(state: SearchViewModel.State, actions: SearchActions) {
    if (!state.seerrOn) return
    val s = state.seerr
    if (s == null) {
        item(key = "seerr-card") {
            NoticeCard(
                AppIcons.Requests, t("Not here? Find “{q}” on Seerr", "q" to state.query), t("Request movies and shows the server doesn't have"),
                Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp), onClick = actions.onSeerr,
            )
        }
        return
    }
    item(key = "seerr-head") { SText(t("Request it"), Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 12.dp), size = 16.sp, weight = 800, color = SeedyColors.White) }
    when {
        s.loading -> item { Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { repeat(2) { Box(Modifier.weight(1f).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Surface)) } } }
        s.error -> item { SText(t("Seerr didn't answer, so requests can't be made right now."), Modifier.padding(horizontal = 16.dp), size = 13.sp, color = SeedyColors.Text2) }
        s.items.isEmpty() -> item { SText(t("Seerr doesn’t know “{q}” either. Check the spelling, or try the original title.", "q" to state.query), Modifier.padding(horizontal = 16.dp), size = 13.sp, color = SeedyColors.Text2) }
        else -> items(s.items.chunked(2), key = { "s:${it.first().mediaType}${it.first().tmdbId}" }) { pair ->
            Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (it in pair) SeerrPoster(it, { actions.onSeerrTitle(it) }, Modifier.weight(1f))
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Nothing in the library: say so, and Seerr's titles under it (the board behind "Request seasons"). */
private fun LazyListScope.empty(state: SearchViewModel.State, actions: SearchActions) {
    item(key = "none") {
        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            Note(
                t("Nothing on the server for “{q}”", "q" to state.query),
                if (state.seerrOn) t("Request it from Seerr below.") else t("No movies, shows, episodes or people match. Check the spelling, or try a shorter part of the title."),
            )
        }
    }
    if (state.seerrOn) {
        item(key = "seerr-head") { SText(t("Request it"), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp), size = 16.sp, weight = 800, color = SeedyColors.White) }
        val s = state.seerr
        when {
            s == null || s.loading -> item { Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { repeat(2) { Box(Modifier.weight(1f).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Surface)) } } }
            s.error -> item { SText(t("Seerr didn't answer, so requests can't be made right now."), Modifier.padding(horizontal = 16.dp), size = 13.sp, color = SeedyColors.Text2) }
            s.items.isEmpty() -> item { SText(t("Seerr doesn’t know “{q}” either. Check the spelling, or try the original title.", "q" to state.query), Modifier.padding(horizontal = 16.dp), size = 13.sp, color = SeedyColors.Text2) }
            else -> items(s.items.chunked(2), key = { "s:${it.first().mediaType}${it.first().tmdbId}" }) { pair ->
                Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (it in pair) SeerrPoster(it, { actions.onSeerrTitle(it) }, Modifier.weight(1f))
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.idle(state: SearchViewModel.State, actions: SearchActions) {
    if (state.recent.isNotEmpty()) {
        item(key = "recent") { SectionLabel(t("Recent searches"), Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp)) }
        items(state.recent, key = { "r:$it" }) { q ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).tap { actions.onText(q) }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SIcon(WebIcons.Clock, size = 18.dp, color = SeedyColors.Text3)
                SText(q, Modifier.weight(1f), size = 14.sp, weight = 600, color = SeedyColors.White, maxLines = 1)
            }
        }
    }
    if (state.genres.isNotEmpty()) {
        item(key = "genres") {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel(t("Browse by genre"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (g in state.genres) Chip(g, false, { actions.onText(g) })
                }
            }
        }
    }
}

@Composable
private fun Note(title: String, text: String) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SeedyColors.Panel).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SText(title, size = 16.sp, weight = 800, color = SeedyColors.White)
        SText(text, size = 13.sp, color = SeedyColors.Text2)
    }
}

/** The best match: its backdrop, the name with the match in the accent, a line, and what Play would start. */
@Composable
private fun TopHit(hit: Hit, top: SearchViewModel.TopPlay?, actions: SearchActions, modifier: Modifier) {
    val viewer = LocalViewer.current
    val item = hit.item
    Box(modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1A232E)).tap { actions.onOpen(item) }) {
        viewer.images?.backdrop(item, 780)?.let { AsyncImage(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to SeedyColors.Bg.copy(alpha = 0.92f))))
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                MarkedText(marked(item.name.orEmpty(), hit.match), 19.sp, 900, SeedyColors.White, uppercase = true, lineHeight = 1f)
                SText(topMeta(item, top), size = 12.sp, color = SeedyColors.TextHero, maxLines = 1)
            }
            val target = top?.target
            if (item.type != BaseItemKind.BOX_SET && item.type != BaseItemKind.PERSON) {
                Row(
                    Modifier.height(40.dp).clip(RoundedCornerShape(11.dp)).background(SeedyColors.Inverse)
                        .tap { if (target != null) actions.onPlay(target) else actions.onOpen(item) }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SIcon(WebIcons.Play, size = 16.dp, color = SeedyColors.InkInverse)
                    SText(if (top?.resume == true) t("Resume") else t("Play"), size = 13.sp, weight = 800, color = SeedyColors.InkInverse, maxLines = 1)
                }
            }
        }
    }
}

/** "Show · S3 E2 · 34 min left", "Movie · 2021 · Drama · 1 h 49 min". */
fun topMeta(item: BaseItemDto, top: SearchViewModel.TopPlay?): String = when (item.type) {
    BaseItemKind.SERIES -> {
        val ep = top?.target?.takeIf { top.resume }
        if (ep != null) join(t("Show"), ep.episodeTag, timeLeft(ep.remainingSeconds)) else join(t("Show"), item.yearRange, item.seasonsLabel)
    }
    BaseItemKind.EPISODE -> join(t("Episode"), rowMeta(item))
    BaseItemKind.BOX_SET -> rowMeta(item)
    else -> join(
        t("Movie"), item.productionYear, item.genres?.firstOrNull(),
        if (top?.resume == true) timeLeft(item.remainingSeconds) else item.runTimeTicks?.let { duration(ticksToSeconds(it)) },
    )
}

@Composable
private fun ResultRow(hit: Hit, state: SearchViewModel.State, actions: SearchActions) {
    val viewer = LocalViewer.current
    val item = hit.item
    Row(
        Modifier.padding(top = 10.dp).fillMaxWidth().heightIn(min = 56.dp).tap { actions.onOpen(item) }.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (hit.kind == HitKind.Person) {
            Portrait(item, 54.dp)
        } else {
            Box(Modifier.size(96.dp, 54.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF2F2840)), contentAlignment = Alignment.Center) {
                SText(initials(item.cardTitleForInitials()), size = 15.sp, weight = 900, color = Color(0xFFE3C4FF))
                viewer.images?.landscape(item, 240, viewer.episodeImages)?.let { AsyncImage(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val lead = if (item.type == BaseItemKind.EPISODE && item.seriesName != null) "${item.seriesName} · " else ""
            MarkedText(marked(item.name.orEmpty(), hit.match, lead), 14.sp, 700, SeedyColors.White)
            val meta = if (hit.kind == HitKind.Person) state.personLines[item.id].orEmpty() else rowMeta(item)
            SText(meta.ifEmpty { " " }, size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
        }
    }
}

private fun BaseItemDto.cardTitleForInitials(): String = if (type == BaseItemKind.EPISODE) seriesName ?: name.orEmpty() else name.orEmpty()

@Composable
private fun RowSkeleton() {
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(96.dp, 54.dp).clip(RoundedCornerShape(9.dp)).background(SeedyColors.Surface))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(140.dp, 12.dp).clip(RoundedCornerShape(6.dp)).background(SeedyColors.Surface))
            Box(Modifier.size(80.dp, 10.dp).clip(RoundedCornerShape(5.dp)).background(SeedyColors.Surface))
        }
    }
}
