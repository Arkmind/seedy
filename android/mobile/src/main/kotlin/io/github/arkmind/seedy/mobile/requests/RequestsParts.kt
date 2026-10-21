package io.github.arkmind.seedy.mobile.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.format.join
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.requests.RequestFormat
import io.github.arkmind.seedy.core.requests.RequestState
import io.github.arkmind.seedy.core.requests.SeerrRequest
import io.github.arkmind.seedy.core.requests.SeerrTitle
import io.github.arkmind.seedy.core.seedy.tmdbImage
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import java.time.LocalDate
import java.util.Locale

/** The colour a request's state is written in. */
@Composable
fun toneColour(tone: RequestState.Tone): Color = when (tone) {
    RequestState.Tone.Ok -> SeedyColors.Ok
    RequestState.Tone.Pending -> SeedyColors.Pending
    RequestState.Tone.Accent -> LocalAccent.current
    RequestState.Tone.Danger -> SeedyColors.Danger
    RequestState.Tone.Muted -> SeedyColors.Text3
}

/** "★ 7.4 · 2026", or an upcoming title's date (lit when it's soon). */
@Composable
fun titleMeta(title: SeerrTitle, upcoming: Boolean, today: LocalDate = LocalDate.now()): Pair<String, Color> {
    if (upcoming) {
        val (text, soon) = RequestFormat.upcoming(title, today)
        return text to (if (soon) LocalAccent.current else SeedyColors.Text3)
    }
    val rating = title.rating?.takeIf { it > 0 }?.let { "★ " + String.format(Locale.US, "%.1f", it) }
    return join(rating, title.year) to SeedyColors.Text3
}

/**
 * Board "Android · Requests" card: a 2:3 poster with its kind (Movie, Series, Upcoming) and a dot for where it is
 * (requested, on the server), the title and a line under it.
 */
@Composable
fun SeerrTitleCard(title: SeerrTitle, upcoming: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier.width(120.dp), today: LocalDate = LocalDate.now()) {
    Column(modifier.tap(onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(11.dp)).background(SeedyColors.Surface)) {
            val poster = tmdbImage(title.posterPath, "w342")
            if (poster == null) {
                SText(
                    title.title, Modifier.align(Alignment.BottomStart).padding(start = 9.dp, end = 9.dp, bottom = 11.dp),
                    size = 13.sp, weight = 900, lineHeight = 0.95f, uppercase = true, color = SeedyColors.White, maxLines = 3,
                )
            } else {
                Artwork(poster, Modifier.matchParentSize())
            }
            val (label, bg) = when {
                upcoming -> t("Upcoming") to SeedyColors.TypeUpcoming
                title.mediaType == "tv" -> t("Series") to SeedyColors.TypeSeries
                else -> t("Movie") to SeedyColors.TypeMovie
            }
            SText(
                label, Modifier.padding(6.dp).clip(CircleShape).background(bg).padding(horizontal = 7.dp, vertical = 3.dp),
                size = 9.sp, weight = 800, tracking = 0.1f, uppercase = true, color = SeedyColors.White,
            )
            val dot = when (title.status) {
                "available", "partial" -> SeedyColors.Ok
                "pending", "processing" -> SeedyColors.Pending
                else -> null
            }
            if (dot != null) {
                Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).background(SeedyColors.Scrim, CircleShape), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(8.dp).background(dot, CircleShape))
                }
            }
        }
        Column(Modifier.padding(horizontal = 1.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            SText(title.title, size = 13.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
            val (meta, ink) = titleMeta(title, upcoming, today)
            SText(meta, size = 11.sp, weight = 600, color = ink, maxLines = 1)
        }
    }
}

/**
 * The board's "3 requests in progress" card: two of the posters stacked, and how many wait, download and are ready.
 */
@Composable
fun InProgressCard(open: List<SeerrRequest>, waiting: Int, downloading: Int, ready: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SeedyColors.Panel).border(1.dp, Color(0xFF222222), RoundedCornerShape(14.dp))
            .tap(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(46.dp).height(42.dp)) {
            open.take(2).forEachIndexed { i, r ->
                // The board's 2 px ring sits outside each poster (a box-shadow), over the one before.
                Box(Modifier.offset(x = (18 * i - 2).dp, y = (-2).dp).size(32.dp, 46.dp).background(SeedyColors.Panel, RoundedCornerShape(8.dp)).padding(2.dp)) {
                    Artwork(tmdbImage(r.posterPath, "w92"), Modifier.matchParentSize().clip(RoundedCornerShape(6.dp)), placeholder = SeedyColors.Surface2)
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            SText(
                io.github.arkmind.seedy.core.i18n.tn(open.size, "{n} request in progress", "{n} requests in progress"),
                size = 13.sp, weight = 700, color = SeedyColors.White, maxLines = 1,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Count(waiting, t("{n} waiting", "n" to waiting), SeedyColors.Pending)
                Count(downloading, t("{n} downloading", "n" to downloading), accent)
                Count(ready, t("{n} ready", "n" to ready), SeedyColors.Ok)
            }
        }
        io.github.arkmind.seedy.ui.icons.SIcon(io.github.arkmind.seedy.ui.icons.WebIcons.ChevronRight, size = 16.dp, color = SeedyColors.Text2, stroke = 2.4f)
    }
}

@Composable
private fun Count(n: Int, text: String, dot: Color) {
    if (n <= 0) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(6.dp).background(dot, CircleShape))
        SText(text, size = 11.sp, color = SeedyColors.Text2, maxLines = 1)
    }
}

/** A request in My requests (no phone board: the TV board's request card, on the phone's scale). */
@Composable
fun RequestRow(
    r: SeerrRequest,
    everyone: Boolean,
    canManage: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onRetry: () -> Unit,
) {
    val state = RequestFormat.state(r)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SeedyColors.Panel).tap(onClick = onOpen).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(52.dp, 78.dp).clip(RoundedCornerShape(8.dp)).background(SeedyColors.Surface)) {
                Artwork(tmdbImage(r.posterPath, "w185"), Modifier.matchParentSize())
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SText(r.title, size = 14.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
                SText(RequestFormat.line(r, everyone), size = 12.sp, color = SeedyColors.Text3, maxLines = 2)
                val ink = toneColour(state.tone)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(7.dp).background(ink, CircleShape))
                    SText(state.text, size = 12.sp, weight = 700, color = ink, maxLines = 1)
                }
                state.fraction?.let { f ->
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(SeedyColors.Surface2)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(LocalAccent.current))
                    }
                }
            }
        }
        val pending = r.requestStatus == "pending"
        val failed = r.requestStatus == "failed"
        if (canManage && (pending || failed)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pending) {
                    SmallButton(t("Approve"), Modifier.weight(1f), primary = true, enabled = !busy, onClick = onApprove)
                    SmallButton(t("Decline"), Modifier.weight(1f), enabled = !busy, onClick = onDecline)
                } else {
                    SmallButton(t("Try again"), Modifier.weight(1f), enabled = !busy, onClick = onRetry)
                }
            }
        }
    }
}

@Composable
private fun SmallButton(label: String, modifier: Modifier, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.height(40.dp).clip(RoundedCornerShape(11.dp)).background(if (primary) SeedyColors.Inverse else SeedyColors.Surface).tap(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { SText(label, size = 13.sp, weight = 800, color = if (primary) SeedyColors.InkInverse else SeedyColors.White) }
}
