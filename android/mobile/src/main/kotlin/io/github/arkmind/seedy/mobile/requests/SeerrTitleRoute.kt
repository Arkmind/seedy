package io.github.arkmind.seedy.mobile.requests

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
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
import io.github.arkmind.seedy.core.seedy.SeerrSeason
import io.github.arkmind.seedy.core.seedy.libraryUuid
import io.github.arkmind.seedy.core.seedy.tmdbImage
import io.github.arkmind.seedy.mobile.browse.topInset
import io.github.arkmind.seedy.mobile.library.Message
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.nav.Route
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.seasons.RequestSeasonsSheet
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession
import java.util.Locale
import kotlin.math.min

/** What the Seerr title page does; the route wires these to the view model and navigation. */
class SeerrTitleActions(
    val onBack: () -> Unit = {},
    val onRequest: () -> Unit = {},
    val onRequest4k: () -> Unit = {},
    val onSeasons: () -> Unit = {},
    val onLibrary: () -> Unit = {},
    val onPlay: () -> Unit = {},
    val onTrailer: (() -> Unit)? = null,
    val onOpenSeerr: (() -> Unit)? = null,
    val onRetry: () -> Unit = {},
    val onMessageShown: () -> Unit = {},
)

/** A title that's on Seerr but not (fully) on the server; a series is asked for through the request-seasons sheet. */
@Composable
fun SeerrTitleRoute(mediaType: String, tmdbId: Int) {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val context = LocalContext.current
    val vm = viewModel(key = "seerr:$mediaType:$tmdbId") { SeerrTitleViewModel(session, mediaType, tmdbId) }
    val state by vm.state.collectAsStateWithLifecycle()
    var seasons by rememberSaveable { mutableStateOf(false) }
    fun browse(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }
    val d = state.details
    val local = libraryUuid(d?.jellyfinItemId)
    Box(Modifier.fillMaxSize()) {
        SeerrTitleScreen(
            state,
            canRequest = vm.canRequest,
            allow4k = vm.allow4k,
            actions = SeerrTitleActions(
                onBack = nav::back,
                onRequest = { vm.request() },
                onRequest4k = { vm.request(is4k = true) },
                onSeasons = { seasons = true },
                onLibrary = { local?.let { nav.go(Route.Details(it)) } },
                onPlay = { local?.let { nav.go(Route.Player(it)) } },
                onTrailer = d?.trailerUrl?.let { url -> { browse(url) } },
                onOpenSeerr = vm.seerrUrl?.let { url -> { browse(url) } },
                onRetry = vm::load,
                onMessageShown = vm::clearMessage,
            ),
        )
        if (seasons && d != null) {
            val target = SeasonsTarget(tmdbId = d.tmdbId, title = d.title, year = yearOf(d), posterPath = d.posterPath, backdropPath = d.backdropPath, fromLibrary = local != null)
            RequestSeasonsSheet(target, onDismiss = { seasons = false }, onDone = vm::markRequested, details = d)
        }
    }
}

private fun yearOf(d: SeerrDetails): String? = d.year ?: parseDay(d.releaseDate)?.year?.toString()

/**
 * The details page's hero (backdrop, back button, fitted title, facts) for a Seerr title (web: SeerrDetails): its kind
 * and status, Request (or where the request is), the overview, the info list, a series' seasons and the cast.
 */
@Composable
fun SeerrTitleScreen(state: SeerrTitleViewModel.State, canRequest: Boolean, allow4k: Boolean, actions: SeerrTitleActions) {
    val d = state.details
    Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (d == null) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().height(240.dp)) { TopButtons(actions) }
                    when {
                        state.loading -> Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.width(220.dp).height(34.dp).clip(RoundedCornerShape(10.dp)).background(SeedyColors.Surface))
                            Box(Modifier.width(160.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(SeedyColors.Surface))
                            Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(13.dp)).background(SeedyColors.Surface))
                        }
                        state.missing -> Message(t("Seerr doesn't know this title"), t("The link may be old, or the title was removed from TMDB."))
                        else -> Message(t("Couldn't reach Seerr"), t("Seedy asked Seerr about this title and got no answer. Try again in a moment."), t("Try again"), actions.onRetry)
                    }
                }
                return@LazyColumn
            }
            item(key = "top") { Top(d, state, canRequest, allow4k, actions) }
            val seasons = d.seasons.orEmpty().filter { it.seasonNumber > 0 }
            if (d.mediaType == "tv" && seasons.isNotEmpty()) {
                item(key = "seasons-head") {
                    SText(t("Seasons"), Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp), size = 16.sp, weight = 800, color = SeedyColors.White)
                }
                for (s in seasons) item(key = "season:${s.seasonNumber}") { SeasonRow(s) }
            }
            if (d.cast.isNotEmpty()) {
                item(key = "cast") {
                    Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SText(t("Cast & crew"), Modifier.padding(horizontal = 20.dp), size = 16.sp, weight = 800, color = SeedyColors.White)
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            itemsIndexed(d.cast.take(20), key = { i, c -> "${c.id}:$i" }) { i, c -> CastCard(c.name, c.character, c.profilePath, i) }
                        }
                    }
                }
            }
        }
        Toast(state.message, actions.onMessageShown, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun TopButtons(actions: SeerrTitleActions) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = topInset()),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        GlassButton(WebIcons.ArrowLeft, 22.dp, t("Back"), actions.onBack)
        Spacer(Modifier.weight(1f))
        actions.onOpenSeerr?.let { GlassButton(WebIcons.External, 20.dp, t("Open in Seerr"), it) }
    }
}

@Composable
private fun GlassButton(icon: IconDef, size: Dp, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(SeedyColors.Bg.copy(alpha = 0.5f)).tap(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        SIcon(icon, size = size, color = SeedyColors.White)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Top(d: SeerrDetails, state: SeerrTitleViewModel.State, canRequest: Boolean, allow4k: Boolean, actions: SeerrTitleActions) {
    val tv = d.mediaType == "tv"
    val seasons = d.seasons.orEmpty().filter { it.seasonNumber > 0 }
    val open = seasons.filter { requestable(it.status) }
    val inLibrary = (d.status == "available" || d.status == "partial") && d.jellyfinItemId != null
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(240.dp)) {
            Artwork(tmdbImage(d.backdropPath, "w1280") ?: tmdbImage(d.posterPath, "w780"), Modifier.fillMaxSize(), placeholder = SeedyColors.Bg)
            Box(Modifier.fillMaxWidth().height(120.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent))))
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.7f).background(Brush.verticalGradient(listOf(Color.Transparent, SeedyColors.Bg))))
            TopButtons(actions)
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 180.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TypePill(tv)
                SeerrTitleViewModel.statusPill(d.status)?.let { (label, tone) -> StatusPill(label, tone) }
            }
            BoxWithConstraints {
                SText(d.title, size = titleSize(d.title, maxWidth), weight = 900, lineHeight = 0.95f, tracking = -0.02f, color = SeedyColors.White, uppercase = true, maxLines = 3)
            }
            Meta(d)
            d.tagline?.takeIf { it.isNotBlank() }?.let { SText(it, size = 13.sp, weight = 600, color = SeedyColors.Text, maxLines = 2) }
            // The primary action (Play from the library, where the request is, or Request), the trailer beside it.
            Row(Modifier.padding(top = 4.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val wide = Modifier.weight(1f)
                when {
                    inLibrary && !tv -> Primary(WebIcons.Play, t("Play"), actions.onPlay, wide)
                    inLibrary -> Primary(WebIcons.Tv, t("Open in library"), actions.onLibrary, wide)
                    requested(d.status) -> StateBox(t("Requested"), wide)
                    canRequest && requestable(d.status) && (!tv || open.isNotEmpty() || seasons.isEmpty()) -> {
                        val busy = state.busy == "std"
                        Primary(
                            WebIcons.Plus,
                            when {
                                busy -> t("Requesting…")
                                tv && seasons.size > 1 -> t("Request seasons")
                                else -> t("Request")
                            },
                            if (tv) actions.onSeasons else actions.onRequest,
                            wide,
                            enabled = state.busy == null,
                        )
                    }
                    else -> Spacer(wide)
                }
                actions.onTrailer?.let { Square(WebIcons.Trailer, t("Trailer"), it) }
            }
            val alts = buildList<@Composable RowScope.() -> Unit> {
                if (inLibrary && !tv) add { Alt(t("Open in library"), actions.onLibrary) }
                if (tv && canRequest && open.isNotEmpty() && (inLibrary || requested(d.status))) {
                    add { Alt(if (inLibrary) t("Request missing seasons") else t("Request more seasons"), actions.onSeasons) }
                }
                if (allow4k && !tv && requestable(d.status4k)) add { Alt(if (state.busy == "4k") t("Requesting…") else t("Request in 4K"), actions.onRequest4k, enabled = state.busy == null) }
                if (allow4k && !tv && requested(d.status4k)) add { StateBox(t("4K requested"), Modifier.weight(1f), alt = true) }
            }
            if (alts.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { alts.forEach { it(this) } }
            if (requested(d.status) || d.status == "declined") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(7.dp).background(if (d.status == "declined") SeedyColors.Danger else SeedyColors.Pending, CircleShape))
                    SText(SeerrTitleViewModel.statusLine(d), size = 13.sp, color = SeedyColors.Text2)
                }
            } else if (!canRequest && requestable(d.status)) {
                SText(t("Your profile can't make requests. Ask whoever runs this server."), size = 13.sp, color = SeedyColors.Text2)
            }
            d.overview?.takeIf { it.isNotBlank() }?.let { SText(it, size = 13.sp, lineHeight = 1.55f, color = SeedyColors.TextHero) }
            Info(d)
        }
    }
}

/** 32 px, smaller when the title's longest word wouldn't fit on a line (as the details page). */
private fun titleSize(title: String, maxWidth: Dp): TextUnit {
    val longest = title.split(Regex("\\s+")).maxOfOrNull { it.length }?.coerceAtLeast(4) ?: 4
    return min(32f, maxWidth.value / (longest * 0.72f)).sp
}

@Composable
private fun TypePill(tv: Boolean) {
    SText(
        if (tv) t("Series") else t("Movie"),
        Modifier.clip(CircleShape).background(if (tv) SeedyColors.TypeSeries else SeedyColors.TypeMovie).padding(horizontal = 9.dp, vertical = 4.dp),
        size = 10.sp, weight = 800, tracking = 0.1f, uppercase = true, color = SeedyColors.White, maxLines = 1,
    )
}

@Composable
private fun StatusPill(label: String, tone: String, background: Color = SeedyColors.Scrim) {
    Row(
        Modifier.height(26.dp).clip(CircleShape).background(background).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(6.dp).background(toneOf(tone), CircleShape))
        SText(label, size = 11.sp, weight = 700, color = SeedyColors.Text, maxLines = 1)
    }
}

private fun toneOf(tone: String): Color = when (tone) {
    "ok" -> SeedyColors.Ok
    "pending" -> SeedyColors.Pending
    "danger" -> SeedyColors.Danger
    else -> SeedyColors.Text3
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Meta(d: SeerrDetails) {
    val tv = d.mediaType == "tv"
    val seasonCount = if (tv) d.numberOfSeasons ?: d.seasons.orEmpty().count { it.seasonNumber > 0 } else 0
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        yearOf(d)?.let { SText(it, size = 13.sp, color = SeedyColors.TextHero) }
        d.certification?.takeIf { it.isNotBlank() }?.let {
            SText(it, Modifier.border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 1.dp), size = 11.sp, weight = 700, color = SeedyColors.TextHero)
        }
        if (!tv && (d.runtime ?: 0) > 0) SText(duration(d.runtime!! * 60.0), size = 13.sp, color = SeedyColors.TextHero)
        if (seasonCount > 0) SText(tn(seasonCount, "{n} season", "{n} seasons"), size = 13.sp, color = SeedyColors.TextHero)
        d.rating?.takeIf { it > 0 }?.let { r ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SIcon(WebIcons.Star, size = 13.dp, color = LocalAccent.current)
                SText(String.format(Locale.US, "%.1f", r), size = 13.sp, color = SeedyColors.TextHero)
            }
        }
        d.genres.take(3).takeIf { it.isNotEmpty() }?.let { SText(it.joinToString(" · "), size = 13.sp, color = SeedyColors.TextHero) }
    }
}

@Composable
private fun Primary(icon: IconDef, label: String, onClick: () -> Unit, modifier: Modifier, enabled: Boolean = true) {
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(13.dp))
            .background(if (enabled) SeedyColors.Inverse else SeedyColors.Surface2).tap(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (enabled) SeedyColors.InkInverse else SeedyColors.Text3
        SIcon(icon, size = 20.dp, color = ink, stroke = if (icon == WebIcons.Plus) 2.6f else icon.stroke)
        SText(label, size = 14.sp, weight = 800, color = ink, maxLines = 1)
    }
}

/** Where a request is, in place of its button (web: .seerr-state). */
@Composable
private fun StateBox(label: String, modifier: Modifier, alt: Boolean = false) {
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(13.dp)).background(SeedyColors.Glass).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(SeedyColors.Pending, CircleShape))
        SText(label, size = if (alt) 13.sp else 14.sp, weight = if (alt) 700 else 800, color = SeedyColors.TextHero, maxLines = 1)
    }
}

@Composable
private fun RowScope.Alt(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Surface).tap(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { SText(label, size = 13.sp, weight = 700, color = if (enabled) SeedyColors.Text else SeedyColors.Text3, maxLines = 1, align = TextAlign.Center) }
}

@Composable
private fun Square(icon: IconDef, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(13.dp)).background(SeedyColors.Surface).tap(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        SIcon(icon, size = 20.dp, color = SeedyColors.Text)
    }
}

/** Directed by, Starring, Network, Release date, Seasons, Status (web: .seerr-info). */
@Composable
private fun Info(d: SeerrDetails) {
    val rows = SeerrTitleViewModel.info(d)
    Column(
        Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SeedyColors.Panel).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        for ((label, value) in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SText(label, Modifier.width(96.dp), size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Text3)
                SText(value, Modifier.weight(1f), size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Text)
            }
        }
    }
}

@Composable
private fun SeasonRow(s: SeerrSeason) {
    Row(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(SeedyColors.Panel).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(SeedyColors.Surface), contentAlignment = Alignment.Center) {
            SText("${s.seasonNumber}", size = 14.sp, weight = 800, color = SeedyColors.White)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(s.name.ifBlank { t("Season {n}", "n" to s.seasonNumber) }, size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
            val sub = join(s.episodeCount.takeIf { it > 0 }?.let { tn(it, "{n} episode", "{n} episodes") }, parseDay(s.airDate)?.year)
            if (sub.isNotEmpty()) SText(sub, size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
        }
        val (label, tone) = SeerrTitleViewModel.statusPill(s.status) ?: (t("Not requested") to "muted")
        StatusPill(label, tone, background = SeedyColors.Bg)
    }
}

private val CastBg = listOf(0xFF3D2424, 0xFF26362B, 0xFF3F3322, 0xFF2F2840, 0xFF1F3A3C, 0xFF3D2436, 0xFF2E2E2E, 0xFF24313D).map { Color(it) }
private val CastInk = listOf(0xFFF0B9A0, 0xFFD9E3B5, 0xFFF2CF86, 0xFFE3C4FF, 0xFFA8E6DF, 0xFFFFB3E6, 0xFFE0E0E0, 0xFFC9D6E3).map { Color(it) }

/** The library's cast card; TMDB people aren't on the server, so it doesn't open anything. */
@Composable
private fun CastCard(name: String, role: String?, path: String?, index: Int) {
    Column(Modifier.width(84.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(CastBg[index % CastBg.size]), contentAlignment = Alignment.Center) {
            val initials = name.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }.uppercase()
            SText(initials, size = 20.sp, weight = 800, color = CastInk[index % CastInk.size])
            tmdbImage(path, "w300_and_h450_face")?.let { Artwork(it, Modifier.matchParentSize(), placeholder = Color.Transparent) }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(name, size = 12.sp, weight = 700, color = SeedyColors.White, maxLines = 2, align = TextAlign.Center)
            role?.takeIf { it.isNotBlank() }?.let { SText(it, size = 11.sp, color = SeedyColors.Text3, maxLines = 2, align = TextAlign.Center) }
        }
    }
}
