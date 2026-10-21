package io.github.arkmind.seedy.tv.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.feature.SeasonsTarget
import io.github.arkmind.seedy.core.feature.SeerrTitleViewModel
import io.github.arkmind.seedy.core.feature.SeerrTitleViewModel.Companion.requestable
import io.github.arkmind.seedy.core.feature.SeerrTitleViewModel.Companion.requested
import io.github.arkmind.seedy.core.format.duration
import io.github.arkmind.seedy.core.format.join
import io.github.arkmind.seedy.core.format.parseDay
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.seedy.SeerrDetails
import io.github.arkmind.seedy.core.seedy.libraryUuid
import io.github.arkmind.seedy.core.seedy.tmdbImage
import io.github.arkmind.seedy.tv.nav.LocalTvNav
import io.github.arkmind.seedy.tv.nav.TvRoute
import io.github.arkmind.seedy.tv.parts.TvPressable
import io.github.arkmind.seedy.tv.parts.bp
import io.github.arkmind.seedy.tv.parts.bsp
import io.github.arkmind.seedy.tv.parts.focusRing
import io.github.arkmind.seedy.tv.parts.focusScale
import io.github.arkmind.seedy.tv.parts.focusSoon
import io.github.arkmind.seedy.tv.seasons.TvRequestSeasonsDialog
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession
import java.util.Locale

/** What the TV Seerr title page does; the route wires these to the view model and navigation. */
class TvSeerrTitleActions(
    val onRequest: () -> Unit = {},
    val onRequest4k: () -> Unit = {},
    val onSeasons: () -> Unit = {},
    val onLibrary: () -> Unit = {},
    val onPlay: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onMessageShown: () -> Unit = {},
)

/** A title that's on Seerr but not (fully) on the server; a series is asked for through the request-seasons dialog. */
@Composable
fun TvSeerrTitleRoute(mediaType: String, tmdbId: Int) {
    val session = currentSession()
    val nav = LocalTvNav.current
    val vm = viewModel(key = "seerr:$mediaType:$tmdbId") { SeerrTitleViewModel(session, mediaType, tmdbId) }
    val state by vm.state.collectAsStateWithLifecycle()
    var seasons by remember { mutableStateOf(false) }
    val primary = remember { FocusRequester() }
    val d = state.details
    val local = libraryUuid(d?.jellyfinItemId)
    TvSeerrTitleScreen(
        state, canRequest = vm.canRequest, allow4k = vm.allow4k,
        actions = TvSeerrTitleActions(
            onRequest = { vm.request() },
            onRequest4k = { vm.request(is4k = true) },
            onSeasons = { seasons = true },
            onLibrary = { local?.let { nav.go(TvRoute.Details(it)) } },
            onPlay = { local?.let { nav.go(TvRoute.Player(it)) } },
            onRetry = vm::load,
            onMessageShown = vm::clearMessage,
        ),
        primaryFocus = primary,
    )
    if (seasons && d != null) {
        val target = SeasonsTarget(
            tmdbId = d.tmdbId, title = d.title, year = d.year ?: parseDay(d.releaseDate)?.year?.toString(),
            posterPath = d.posterPath, backdropPath = d.backdropPath, fromLibrary = local != null,
        )
        TvRequestSeasonsDialog(target, onDismiss = { seasons = false }, onDone = vm::markRequested, details = d, returnFocus = { primary.requestFocus() })
    }
}

/**
 * The TV details hero for a Seerr title (web: SeerrDetails): the backdrop faded into the page, the title, its kind and
 * status, the facts, Request (or where the request is), the overview and the info list.
 */
@Composable
fun TvSeerrTitleScreen(
    state: SeerrTitleViewModel.State,
    canRequest: Boolean,
    allow4k: Boolean,
    actions: TvSeerrTitleActions,
    primaryFocus: FocusRequester = remember { FocusRequester() },
) {
    val d = state.details
    Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
        if (d != null) Artwork(tmdbImage(d.backdropPath, "w1280"), Modifier.fillMaxSize(), placeholder = SeedyColors.Bg)
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to SeedyColors.Bg, 0.32f to SeedyColors.Bg.copy(alpha = 0.92f), 0.62f to SeedyColors.Bg.copy(alpha = 0.4f), 1f to SeedyColors.Bg.copy(alpha = 0.1f)),
            ),
        )
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(520.bp)
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.6f to SeedyColors.Bg, 1f to SeedyColors.Bg)),
        )
        if (d == null) {
            if (!state.loading) {
                Column(Modifier.padding(start = 96.bp, top = 96.bp).width(1100.bp), verticalArrangement = Arrangement.spacedBy(20.bp)) {
                    SText(if (state.missing) t("Seerr doesn't know this title") else t("Couldn't reach Seerr"), size = 48.bsp, weight = 900, color = SeedyColors.White)
                    SText(
                        if (state.missing) t("The link may be old, or the title was removed from TMDB.") else t("Seedy asked Seerr about this title and got no answer. Try again in a moment."),
                        size = 22.bsp, lineHeight = 1.5f, color = SeedyColors.TextHero,
                    )
                    if (!state.missing) Button(t("Try again"), WebIcons.Refresh, actions.onRetry, Modifier.focusRequester(primaryFocus), primary = true)
                }
            }
        } else {
            Hero(d, state, canRequest, allow4k, actions, primaryFocus)
        }
        state.message?.let { message ->
            LaunchedEffect(message) {
                kotlinx.coroutines.delay(3200)
                actions.onMessageShown()
            }
            SText(
                message,
                Modifier.align(Alignment.BottomStart).padding(start = 96.bp, bottom = 48.bp).clip(RoundedCornerShape(16.bp)).background(SeedyColors.Surface)
                    .padding(horizontal = 24.bp, vertical = 16.bp),
                size = 20.bsp, weight = 600, color = SeedyColors.Text,
            )
        }
    }
    LaunchedEffect(d != null, state.loading) { if (d != null || !state.loading) primaryFocus.focusSoon() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Hero(d: SeerrDetails, state: SeerrTitleViewModel.State, canRequest: Boolean, allow4k: Boolean, actions: TvSeerrTitleActions, primaryFocus: FocusRequester) {
    val accent = LocalAccent.current
    val tv = d.mediaType == "tv"
    val seasons = d.seasons.orEmpty().filter { it.seasonNumber > 0 }
    val open = seasons.filter { requestable(it.status) }
    val inLibrary = (d.status == "available" || d.status == "partial") && d.jellyfinItemId != null
    Column(Modifier.padding(start = 96.bp, top = 96.bp, end = 96.bp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.bp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.bp), verticalAlignment = Alignment.CenterVertically) {
            SText(
                if (tv) t("Series") else t("Movie"),
                Modifier.clip(CircleShape).background(if (tv) SeedyColors.TypeSeries else SeedyColors.TypeMovie).padding(horizontal = 14.bp, vertical = 6.bp),
                size = 15.bsp, weight = 800, tracking = 0.1f, uppercase = true, color = SeedyColors.White, maxLines = 1,
            )
            SeerrTitleViewModel.statusPill(d.status)?.let { (label, tone) ->
                Row(
                    Modifier.height(36.bp).clip(CircleShape).background(SeedyColors.Scrim).padding(horizontal = 14.bp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.bp),
                ) {
                    Box(Modifier.size(9.bp).background(toneOf(tone), CircleShape))
                    SText(label, size = 16.bsp, weight = 700, color = SeedyColors.Text, maxLines = 1)
                }
            }
        }
        SText(d.title, Modifier.width(1240.bp), size = titleSize(d.title), weight = 900, lineHeight = 0.9f, tracking = -0.02f, color = SeedyColors.White, uppercase = true, maxLines = 2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(18.bp), verticalArrangement = Arrangement.spacedBy(8.bp), itemVerticalAlignment = Alignment.CenterVertically) {
            (d.year ?: parseDay(d.releaseDate)?.year?.toString())?.let { SText(it, size = 21.bsp, weight = 500, color = SeedyColors.TextHero) }
            d.certification?.takeIf { it.isNotBlank() }?.let {
                SText(it, Modifier.border(2.bp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(7.bp)).padding(horizontal = 12.bp, vertical = 2.bp), size = 16.bsp, weight = 700, color = SeedyColors.TextHero)
            }
            if (!tv && (d.runtime ?: 0) > 0) SText(duration(d.runtime!! * 60.0), size = 21.bsp, weight = 500, color = SeedyColors.TextHero)
            val seasonCount = if (tv) d.numberOfSeasons ?: seasons.size else 0
            if (seasonCount > 0) SText(tn(seasonCount, "{n} season", "{n} seasons"), size = 21.bsp, weight = 500, color = SeedyColors.TextHero)
            d.rating?.takeIf { it > 0 }?.let { r ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.bp)) {
                    SIcon(WebIcons.Star, size = 20.bp, color = accent)
                    SText(String.format(Locale.US, "%.1f", r), size = 21.bsp, weight = 500, color = SeedyColors.TextHero)
                }
            }
            d.genres.take(3).takeIf { it.isNotEmpty() }?.let { SText(it.joinToString(" · "), size = 21.bsp, weight = 500, color = SeedyColors.TextHero) }
        }
        d.overview?.takeIf { it.isNotBlank() }?.let { SText(it, Modifier.width(940.bp), size = 22.bsp, lineHeight = 1.5f, color = SeedyColors.TextHero, maxLines = 3) }
        Row(Modifier.padding(top = 10.bp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.bp)) {
            val first = Modifier.focusRequester(primaryFocus)
            var placed = false
            fun focusFirst(): Modifier = if (placed) Modifier else { placed = true; first }
            when {
                inLibrary && !tv -> Button(t("Play"), WebIcons.Play, actions.onPlay, focusFirst(), primary = true)
                inLibrary -> Button(t("Open in library"), WebIcons.Tv, actions.onLibrary, focusFirst(), primary = true)
                requested(d.status) -> State(t("Requested"))
                canRequest && requestable(d.status) && (!tv || open.isNotEmpty() || seasons.isEmpty()) -> Button(
                    when {
                        state.busy == "std" -> t("Requesting…")
                        tv && seasons.size > 1 -> t("Request seasons")
                        else -> t("Request")
                    },
                    WebIcons.Plus, if (tv) actions.onSeasons else actions.onRequest, focusFirst(), primary = true,
                )
            }
            if (inLibrary && !tv) Button(t("Open in library"), WebIcons.Tv, actions.onLibrary)
            if (tv && canRequest && open.isNotEmpty() && (inLibrary || requested(d.status))) {
                Button(if (inLibrary) t("Request missing seasons") else t("Request more seasons"), WebIcons.Request, actions.onSeasons, focusFirst())
            }
            if (allow4k && !tv && requestable(d.status4k)) Button(if (state.busy == "4k") t("Requesting…") else t("Request in 4K"), WebIcons.Plus, actions.onRequest4k, focusFirst())
            if (allow4k && !tv && requested(d.status4k)) State(t("4K requested"))
        }
        if (requested(d.status) || d.status == "declined") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.bp)) {
                Box(Modifier.size(10.bp).background(if (d.status == "declined") SeedyColors.Danger else SeedyColors.Pending, CircleShape))
                SText(SeerrTitleViewModel.statusLine(d), size = 19.bsp, color = SeedyColors.Text2)
            }
        } else if (!canRequest && requestable(d.status)) {
            SText(t("Your profile can't make requests. Ask whoever runs this server."), size = 19.bsp, color = SeedyColors.Text2)
        }
        Column(Modifier.padding(top = 8.bp).width(940.bp), verticalArrangement = Arrangement.spacedBy(10.bp)) {
            for ((label, value) in SeerrTitleViewModel.info(d)) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.bp)) {
                    SText(label, Modifier.width(180.bp), size = 19.bsp, color = SeedyColors.Text3)
                    SText(value, Modifier.weight(1f), size = 19.bsp, color = SeedyColors.Text)
                }
            }
        }
        if (tv && seasons.isNotEmpty()) {
            FlowRow(Modifier.width(1240.bp).padding(top = 8.bp), horizontalArrangement = Arrangement.spacedBy(12.bp), verticalArrangement = Arrangement.spacedBy(12.bp)) {
                for (s in seasons) {
                    val (label, tone) = SeerrTitleViewModel.statusPill(s.status) ?: (t("Not requested") to "muted")
                    Row(
                        Modifier.clip(RoundedCornerShape(14.bp)).background(SeedyColors.Panel).padding(horizontal = 16.bp, vertical = 10.bp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.bp),
                    ) {
                        SText(t("Season {n}", "n" to s.seasonNumber), size = 18.bsp, weight = 700, color = SeedyColors.White, maxLines = 1)
                        Box(Modifier.size(8.bp).background(toneOf(tone), CircleShape))
                        SText(join(label, s.episodeCount.takeIf { it > 0 }?.let { tn(it, "{n} episode", "{n} episodes") }), size = 16.bsp, color = SeedyColors.Text2, maxLines = 1)
                    }
                }
            }
        }
        Box(Modifier.height(96.bp))
    }
}

private fun toneOf(tone: String): Color = when (tone) {
    "ok" -> SeedyColors.Ok
    "pending" -> SeedyColors.Pending
    "danger" -> SeedyColors.Danger
    else -> SeedyColors.Text3
}

/** 92 px, smaller (down to 60) so the title fits 1240 px on one line, as on the details page. */
private fun titleSize(title: String): TextUnit {
    val fit = 1240f / (title.length.coerceAtLeast(4) * 0.72f)
    return (fit.coerceIn(60f, 92f) / 2f).sp
}

/** The details page's 66 px hero button: translucent, the main one light; light with the ring when focused. */
@Composable
private fun Button(label: String, icon: IconDef, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    TvPressable(onClick, modifier) { focused ->
        val light = primary || focused
        Row(
            Modifier.focusScale(focused, 1.04f).focusRing(focused, radius = 19.bp, gap = 5.bp, ring = 5.bp).height(66.bp).clip(RoundedCornerShape(19.bp))
                .background(if (light) SeedyColors.Inverse else Color.White.copy(alpha = 0.14f)).padding(horizontal = if (primary) 34.bp else 26.bp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.bp),
        ) {
            val ink = if (light) SeedyColors.InkInverse else SeedyColors.White
            SIcon(icon, size = 26.bp, color = ink, stroke = if (icon == WebIcons.Plus) 2.6f else 2.2f)
            SText(label, size = 22.bsp, weight = if (primary) 800 else 700, color = ink, maxLines = 1)
        }
    }
}

/** Where a request is, in place of its button. */
@Composable
private fun State(label: String) {
    Row(
        Modifier.height(66.bp).clip(RoundedCornerShape(19.bp)).background(SeedyColors.Glass).padding(horizontal = 30.bp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.bp),
    ) {
        Box(Modifier.size(10.bp).background(SeedyColors.Pending, CircleShape))
        SText(label, size = 22.bsp, weight = 800, color = SeedyColors.TextHero, maxLines = 1)
    }
}
