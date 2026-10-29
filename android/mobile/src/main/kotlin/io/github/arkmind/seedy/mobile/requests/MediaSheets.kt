package io.github.arkmind.seedy.mobile.requests

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.mediarequests.BookRequestViewModel
import io.github.arkmind.seedy.core.mediarequests.MediaFormat
import io.github.arkmind.seedy.core.mediarequests.MediaOptions
import io.github.arkmind.seedy.core.mediarequests.MusicArtistViewModel
import io.github.arkmind.seedy.core.mediarequests.MusicRelease
import io.github.arkmind.seedy.mobile.browse.Chip
import io.github.arkmind.seedy.mobile.manage.BigButton
import io.github.arkmind.seedy.mobile.parts.bleed
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.components.SeedySwitch
import io.github.arkmind.seedy.ui.components.initialsOf
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession

/*
 * The request windows as phone sheets (web: music-dialog.tsx and book-dialog.tsx; boards RequestMusicArtist and
 * RequestBook, in the frame of "Android · Request seasons"): over a dimmed page from 150 px down, the parts scroll over
 * a pinned foot with the approval line and the request button.
 */

/** The sheet's frame: the dimmed page, the grabber, [content] scrolling over the pinned [footer]. */
@Composable
private fun RequestSheet(onDismiss: () -> Unit, footer: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) =
    io.github.arkmind.seedy.ui.components.Overlay {
        BackHandler(onBack = onDismiss)
        val shown = remember { MutableTransitionState(false).apply { targetState = true } }
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color(0xA8050505)).tap(onClick = onDismiss))
            AnimatedVisibility(shown, enter = slideInVertically { it / 3 } + fadeIn()) {
                val shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
                Column(Modifier.fillMaxSize().padding(top = 150.dp).shadow(40.dp, shape, ambientColor = Color.Black, spotColor = Color.Black).clip(shape).background(SeedyColors.Panel).tap {}) {
                    Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(SeedyColors.Stroke))
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = content,
                    )
                    val bottom = max(26.dp, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp)
                    Column(
                        Modifier.fillMaxWidth().drawBehind { drawLine(SeedyColors.Line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                            .padding(start = 16.dp, end = 16.dp, top = 13.dp, bottom = bottom),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        content = footer,
                    )
                }
            }
        }
    }

@Composable
private fun Eyebrow(text: String) = SText(text, size = 11.sp, weight = 800, tracking = 0.1f, color = SeedyColors.Text2, uppercase = true, maxLines = 1)

@Composable
private fun Skeleton(modifier: Modifier) = Box(modifier.clip(RoundedCornerShape(8.dp)).background(SeedyColors.Row))

/** The foot: what happens next (approved at once, an admin first, or why it can't be sent), then the request button. */
@Composable
private fun ColumnScope.Foot(note: Pair<String, String>?, error: String?, label: String, enabled: Boolean, onSubmit: () -> Unit) {
    error?.let { SText(it, size = 12.sp, weight = 600, color = SeedyColors.Danger) }
    if (note != null && note.first.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val tone = when (note.second) { "ok" -> SeedyColors.Ok; "danger" -> SeedyColors.Danger; else -> SeedyColors.Pending }
            Box(Modifier.size(8.dp).background(tone, CircleShape))
            SText(note.first, size = 12.sp, color = SeedyColors.Text2, maxLines = 2)
        }
    }
    BigButton(label, onSubmit, enabled = enabled, icon = WebIcons.Request)
}

/** The ticked box of the boards' rows: the accent when ticked, grey with a tick when it's already there. */
@Composable
private fun TickBox(on: Boolean, locked: Boolean = false, size: Int = 24, check: Int = 16) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(7.dp)).background(if (on) LocalAccent.current else if (locked) Color(0xFF5A5A5A) else SeedyColors.Surface2),
        contentAlignment = Alignment.Center,
    ) {
        if (on || locked) SIcon(WebIcons.Check, size = check.dp, color = if (on) SeedyColors.InkInverse else SeedyColors.Text, stroke = 3.2f)
    }
}

/** "Album requests this week · 3 of 5 left", with what's used in grey and this request in the accent. */
@Composable
private fun Quota(label: String, opts: MediaOptions, units: Int, extra: String? = null) {
    val accent = LocalAccent.current
    val limit = opts.limit
    val left = maxOf(0, limit - opts.used)
    val over = limit >= 0 && units > left
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(SeedyColors.Row).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SText(label, Modifier.weight(1f), size = 12.sp, weight = 700, color = SeedyColors.Text, maxLines = 1)
            SText(
                when { limit < 0 -> t("No limit"); limit == 0 -> t("Can’t request"); else -> t("{left} of {limit} left", "left" to left, "limit" to limit) },
                size = 12.sp, color = if (over) SeedyColors.Danger else SeedyColors.Text2, maxLines = 1,
            )
        }
        Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(SeedyColors.Surface2)) {
            if (limit > 0) {
                val used = (opts.used.toFloat() / limit).coerceIn(0f, 1f)
                val asked = (minOf(units, left).toFloat() / limit).coerceIn(0f, 1f - used)
                if (used > 0f) Box(Modifier.fillMaxHeight().weight(used).background(Color(0xFF6A6A6A)))
                if (asked > 0f) Box(Modifier.fillMaxHeight().weight(asked).background(if (over) SeedyColors.Danger else accent))
                val rest = 1f - used - asked
                if (rest > 0f) Spacer(Modifier.weight(rest))
            }
        }
        extra?.let { SText(it, size = 12.sp, color = SeedyColors.Text3) }
    }
}

/** A grey card with a small label over its value (Quality). */
@Composable
private fun InfoCard(label: String, value: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(SeedyColors.Row).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SText(label, size = 12.sp, color = SeedyColors.Text3)
        SText(value, size = 14.sp, weight = 700, color = SeedyColors.White)
    }
}

@Composable
private fun RetryNote(text: String, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SText(text, size = 13.sp, color = SeedyColors.Text2)
        SText(
            t("Try again"), Modifier.clip(RoundedCornerShape(12.dp)).background(SeedyColors.Surface).tap(onClick = onRetry).padding(horizontal = 14.dp, vertical = 10.dp),
            size = 13.sp, weight = 700, color = SeedyColors.White,
        )
    }
}

// ── Music ──

/** Opens the artist window for [target]; [onDone] gets the toast's words once the request is sent, then it closes. */
@Composable
fun RequestArtistSheet(target: MusicArtistViewModel.Target, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val session = currentSession()
    val vm = viewModel(key = "request-artist") { MusicArtistViewModel(session) }
    LaunchedEffect(target) { vm.open(target) }
    val state by vm.state.collectAsStateWithLifecycle()
    if (state.target != target) return
    RequestArtistContent(
        state, ArtistSheetActions(vm::setMode, vm::setType, vm::toggle, vm::toggleAll, vm::setKeepNew, vm::retry) { vm.submit { msg -> onDone(msg); onDismiss() } },
        onDismiss,
    )
}

class ArtistSheetActions(
    val mode: (MusicArtistViewModel.Mode) -> Unit,
    val type: (String) -> Unit,
    val toggle: (String) -> Unit,
    val toggleAll: () -> Unit,
    val keepNew: (Boolean) -> Unit,
    val retry: () -> Unit,
    val submit: () -> Unit,
)

/** The artist window itself (stateless, for screenshot tests). */
@Composable
fun RequestArtistContent(state: MusicArtistViewModel.State, actions: ArtistSheetActions, onDismiss: () -> Unit) {
    RequestSheet(
        onDismiss,
        footer = { Foot(if (state.ready) state.note else null, state.error, state.label, state.canSend, actions.submit) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RequestAlbumArt(artIndex(state.seed), state.imageUrl, Modifier.size(64.dp).clip(CircleShape), text = initialsOf(state.name), round = true, initialsSize = 22.sp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Eyebrow(t("Request from Lidarr"))
                SText(state.name, size = 17.sp, weight = 800, lineHeight = 1.2f, color = SeedyColors.White, maxLines = 2)
                when {
                    state.failed && !state.ready -> SText(t("MusicBrainz didn’t answer."), size = 12.sp, color = SeedyColors.Text2)
                    !state.ready -> Skeleton(Modifier.fillMaxWidth(0.8f).height(12.dp))
                    else -> SText(state.meta, size = 12.sp, lineHeight = 1.4f, color = SeedyColors.Text2, maxLines = 3)
                }
            }
        }
        val opts = state.options
        when {
            state.failed && !state.ready -> RetryNote(t("Couldn’t load this artist."), actions.retry)
            !state.ready || opts == null -> {
                Skeleton(Modifier.fillMaxWidth().height(48.dp))
                repeat(4) { Skeleton(Modifier.fillMaxWidth().height(60.dp)) }
            }
            else -> {
                // Whole artist · Choose albums · New releases only.
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(SeedyColors.Row).padding(4.dp)) {
                    FlexRow(gap = 4) {
                        for (mode in MusicArtistViewModel.Mode.entries) {
                            val on = state.mode == mode
                            Box(
                                Modifier.height(40.dp).clip(RoundedCornerShape(11.dp)).background(if (on) SeedyColors.Inverse else Color.Transparent)
                                    .tap { actions.mode(mode) }.padding(horizontal = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                SText(
                                    t(mode.label), size = 13.sp, weight = if (on) 800 else 600, lineHeight = 1.15f, align = TextAlign.Center,
                                    color = if (on) SeedyColors.InkInverse else SeedyColors.Text, maxLines = 2,
                                )
                            }
                        }
                    }
                }
                if (state.mode == MusicArtistViewModel.Mode.Future) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(SeedyColors.Row).padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SIcon(WebIcons.Clock, size = 18.dp, color = SeedyColors.Text2)
                        SText(
                            t("Lidarr watches {artist} and grabs albums that come out from now on. Nothing already out is downloaded.", "artist" to state.name),
                            Modifier.weight(1f), size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Text2,
                        )
                    }
                } else {
                    Types(state, actions)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (r in state.rows) ReleaseRow(r, state, actions)
                    }
                }
                if (state.mode == MusicArtistViewModel.Mode.Albums) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(SeedyColors.Row).tap { actions.keepNew(!state.keepNew) }
                            .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            SText(t("Keep getting new albums"), size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
                            SText(t("Lidarr keeps watching {artist} and grabs new releases", "artist" to state.name), size = 12.sp, color = SeedyColors.Text3, maxLines = 2)
                        }
                        SeedySwitch(state.keepNew, actions.keepNew)
                    }
                }
                InfoCard(t("Quality"), opts.quality?.let { t("{quality} · set by your admin", "quality" to it) } ?: t("Set by your admin"))
                Quota(t("Album requests this week"), opts, state.units)
            }
        }
    }
}

/** Albums · 5 / EPs · 3 / Singles · 9; then, as Request seasons' list head, the type's name and Select all while picking albums. */
@Composable
private fun Types(state: MusicArtistViewModel.State, actions: ArtistSheetActions) {
    val counts = state.counts
    val types = listOf("album" to "Albums · {n}", "ep" to "EPs · {n}", "single" to "Singles · {n}").filter { (counts[it.first] ?: 0) > 0 }
    LazyRow(Modifier.fillMaxWidth().bleed(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
        for ((type, label) in types) {
            item(key = type) { Chip(t(label, "n" to (counts[type] ?: 0)), state.type == type, { actions.type(type) }) }
        }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        val name = when (state.type) { "ep" -> t("EPs"); "single" -> t("Singles"); else -> t("Albums") }
        SText(name, Modifier.weight(1f), size = 15.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
        if (state.mode == MusicArtistViewModel.Mode.Albums && state.open.isNotEmpty()) {
            Row(
                Modifier.height(44.dp).tap(onClick = actions.toggleAll).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SText(t("Select all"), size = 13.sp, weight = 700, color = SeedyColors.Text, maxLines = 1)
                TickBox(on = state.allOn, size = 22, check = 14)
            }
        }
    }
}

/** An album as Request seasons draws a season: the box, its cover, title and line, and where it is under them. */
@Composable
private fun ReleaseRow(r: MusicRelease, state: MusicArtistViewModel.State, actions: ArtistSheetActions) {
    val accent = LocalAccent.current
    val locked = MusicArtistViewModel.State.locked(r)
    val on = state.on(r)
    val picking = state.mode == MusicArtistViewModel.Mode.Albums
    val shape = RoundedCornerShape(13.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(shape).background(if (on && picking) Color(0xFF1C1C1C) else Color.Transparent)
            .border(1.dp, if (on && picking) accent else Color(0xFF222222), shape)
            .then(if (locked || !picking) Modifier else Modifier.tap { actions.toggle(r.mbid) })
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TickBox(on = on, locked = locked)
        RequestAlbumArt(artIndex(r.mbid), r.coverUrl, Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(r.title, size = 14.sp, weight = 700, color = if (locked) SeedyColors.Text2 else SeedyColors.White, maxLines = 1)
            MediaFormat.releaseSub(r).takeIf { it.isNotEmpty() }?.let { SText(it, size = 12.sp, color = SeedyColors.Text3, maxLines = 1) }
            val status = MediaFormat.cardStatus(r.status, r.requestedBy)
            val text = status?.text ?: if (r.upcoming) t("Not out yet") else t("Not on the server")
            val (bg, ink, dot) = when (r.status) {
                "server" -> Triple(SeedyColors.Ok.copy(alpha = 0.1f), SeedyColors.Ok, SeedyColors.Ok)
                "none" -> Triple(Color(0xFF262626), SeedyColors.TextHero, SeedyColors.TextHero)
                else -> Triple(SeedyColors.Pending.copy(alpha = 0.1f), SeedyColors.Pending, stateColour(status?.dot ?: "pending"))
            }
            Row(
                Modifier.padding(top = 4.dp).clip(CircleShape).background(bg).padding(horizontal = 9.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(6.dp).background(dot, CircleShape))
                SText(text, size = 11.sp, weight = 700, color = ink, maxLines = 1)
            }
        }
    }
}

// ── Books ──

/** Opens the book window for [target]; [onDone] gets the toast's words once the request is sent, then it closes. */
@Composable
fun RequestBookSheet(target: BookRequestViewModel.Target, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val session = currentSession()
    val vm = viewModel(key = "request-book") { BookRequestViewModel(session) }
    LaunchedEffect(target) { vm.open(target) }
    val state by vm.state.collectAsStateWithLifecycle()
    if (state.target != target) return
    RequestBookContent(
        state, BookSheetActions(vm::toggle, vm::setLang, vm::retry) { vm.submit { msg -> onDone(msg); onDismiss() } },
        cover = vm.api::bookCover, onDismiss = onDismiss,
    )
}

class BookSheetActions(val toggle: (String) -> Unit, val lang: (String) -> Unit, val retry: () -> Unit, val submit: () -> Unit)

private class FormatInfo(val name: String, val icon: io.github.arkmind.seedy.ui.icons.IconDef, val sub: String)

private fun formatInfo(f: String): FormatInfo = when (f) {
    "audiobook" -> FormatInfo("Audiobook", WebIcons.Headphones, "Listen in Seedy, with your place saved")
    "comic" -> FormatInfo("Comic", WebIcons.Book, "CBZ or CBR · read in Seedy page by page")
    else -> FormatInfo("Ebook", WebIcons.Book, "EPUB, or AZW3 if there’s no EPUB · read in Seedy")
}

/** The book window itself (stateless, for screenshot tests). */
@Composable
fun RequestBookContent(state: BookRequestViewModel.State, actions: BookSheetActions, cover: (io.github.arkmind.seedy.core.mediarequests.BookCard) -> String?, onDismiss: () -> Unit) {
    val book = state.book ?: return
    val opts = state.options
    RequestSheet(
        onDismiss,
        footer = { Foot(if (opts != null) state.note else null, state.error, state.label, state.canSend, actions.submit) },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BookCover(book, cover(book), Modifier.size(64.dp, 96.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Eyebrow(if (book.content == "comic") t("Request a comic") else t("Request a book"))
                SText(book.title, size = 17.sp, weight = 800, lineHeight = 1.2f, color = SeedyColors.White, maxLines = 2)
                val meta = state.meta
                BasicText(
                    buildAnnotatedString {
                        book.author?.let { withStyle(SpanStyle(color = SeedyColors.White, fontWeight = FontWeight(700))) { append(it) } }
                        if (book.author != null && meta.isNotEmpty()) append(" · ")
                        append(meta)
                    },
                    style = BaseText.copy(fontSize = 12.sp, color = SeedyColors.Text2),
                    maxLines = 2,
                )
                val desc = state.description
                if (desc.isNotEmpty()) SText(desc, size = 12.sp, lineHeight = 1.45f, color = SeedyColors.Text3, maxLines = 5)
                else if (state.loading) Skeleton(Modifier.fillMaxWidth(0.8f).height(12.dp))
            }
        }
        if (state.failed && state.details == null) {
            RetryNote(t("Couldn’t load this book."), actions.retry)
            return@RequestSheet
        }
        Eyebrow(t("Formats"))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                opts == null -> repeat(2) { Skeleton(Modifier.fillMaxWidth().height(76.dp)) }
                state.offered.isEmpty() -> SText(t("This server can’t request this kind of book."), size = 13.sp, color = SeedyColors.Text2)
                else -> for (f in state.offered) FormatRow(f, state, actions)
            }
        }
        LanguageCard(state, actions)
        if (opts != null) Quota(t("Book requests this month"), opts, state.units, extra = if (book.content == "comic") null else t("Ebook and audiobook count as two"))
        else Skeleton(Modifier.fillMaxWidth().height(62.dp))
        val libraries = state.libraries
        if (libraries.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(SeedyColors.Row).padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SIcon(WebIcons.Folder, size = 18.dp, color = SeedyColors.Text2, stroke = 2.2f)
                val template = if (libraries.size == 2) t("Lands in {a} and {b}.") else t("Lands in {a}.")
                BasicText(
                    buildAnnotatedString {
                        var rest = template
                        val bold = SpanStyle(color = SeedyColors.White, fontWeight = FontWeight(700))
                        for ((key, name) in listOf("{a}" to libraries[0], "{b}" to libraries.getOrNull(1))) {
                            val i = rest.indexOf(key)
                            if (i < 0 || name == null) continue
                            append(rest.substring(0, i))
                            withStyle(bold) { append(name) }
                            rest = rest.substring(i + key.length)
                        }
                        append(rest)
                        state.scanLine?.let { append(" $it") }
                    },
                    Modifier.weight(1f),
                    style = BaseText.copy(fontSize = 13.sp, lineHeight = 19.sp, color = SeedyColors.Text2),
                )
            }
        }
    }
}

@Composable
private fun FormatRow(f: String, state: BookRequestViewModel.State, actions: BookSheetActions) {
    val accent = LocalAccent.current
    val book = state.book ?: return
    val onServer = f in book.onServer
    val on = !onServer && f in state.picked
    val info = formatInfo(f)
    val service = state.available.firstOrNull { it.format == f }?.service.orEmpty()
    val shape = RoundedCornerShape(13.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (on) Color(0xFF1C1C1C) else Color.Transparent)
            .border(if (on) 2.dp else 1.dp, if (on) accent else Color(0xFF222222), shape)
            .then(if (onServer) Modifier else Modifier.tap { actions.toggle(f) }).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(SeedyColors.Surface), contentAlignment = Alignment.Center) {
            SIcon(info.icon, size = 22.dp, color = if (on) accent else SeedyColors.TextHero, stroke = 2f)
        }
        Column(Modifier.weight(1f).alpha(if (onServer) 0.6f else 1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(t(info.name), size = 15.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
            SText(if (onServer) t("Already on the server") else t(info.sub), size = 12.sp, lineHeight = 1.4f, color = SeedyColors.Text2)
            SText(MediaFormat.routeLine(f, service), size = 12.sp, weight = 700, color = SeedyColors.Text3, maxLines = 1)
        }
        TickBox(on = on, locked = onServer)
    }
}

/** Language: the book's original first, as the web's select. */
@Composable
private fun LanguageCard(state: BookRequestViewModel.State, actions: BookSheetActions) {
    var open by remember { mutableStateOf(false) }
    val original = state.original?.code
    fun name(l: BookRequestViewModel.Language) = if (l.code == original) t("{language} (original)", "language" to t(l.name)) else t(l.name)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(SeedyColors.Row).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SText(t("Language"), size = 12.sp, color = SeedyColors.Text3)
        Box {
            Row(
                Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp)).background(SeedyColors.Surface).tap { open = true }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val current = BookRequestViewModel.Languages.firstOrNull { it.code == state.lang }
                SText(current?.let(::name).orEmpty(), Modifier.weight(1f), size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
                SIcon(WebIcons.ChevronDown, size = 16.dp, color = SeedyColors.Text2, stroke = 2.2f)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = SeedyColors.Row, shape = RoundedCornerShape(12.dp)) {
                for (l in BookRequestViewModel.Languages) {
                    DropdownMenuItem(
                        text = { SText(name(l), size = 14.sp, weight = if (l.code == state.lang) 800 else 600, color = SeedyColors.White) },
                        onClick = { open = false; actions.lang(l.code) },
                    )
                }
            }
        }
    }
}
