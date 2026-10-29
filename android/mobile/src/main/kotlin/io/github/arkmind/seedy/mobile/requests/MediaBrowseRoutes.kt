package io.github.arkmind.seedy.mobile.requests

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.browse.Browse
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.mediarequests.BookCard
import io.github.arkmind.seedy.core.mediarequests.BookRequestViewModel
import io.github.arkmind.seedy.core.mediarequests.BookRow
import io.github.arkmind.seedy.core.mediarequests.MediaFormat
import io.github.arkmind.seedy.core.mediarequests.MediaRequest
import io.github.arkmind.seedy.core.mediarequests.MediaRowViewModel
import io.github.arkmind.seedy.core.mediarequests.MediaSearchViewModel
import io.github.arkmind.seedy.core.mediarequests.MusicArtistRef
import io.github.arkmind.seedy.core.mediarequests.MusicArtistViewModel
import io.github.arkmind.seedy.core.mediarequests.MusicRelease
import io.github.arkmind.seedy.core.mediarequests.MusicRow
import io.github.arkmind.seedy.core.seedy.libraryUuid
import io.github.arkmind.seedy.mobile.browse.topInset
import io.github.arkmind.seedy.mobile.library.Message
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.nav.Route
import io.github.arkmind.seedy.mobile.parts.HeaderButton
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession
import java.time.LocalDate
import kotlinx.coroutines.launch

/** What a card opens: the artist's or the book's request window, or the title when it's on the server. */
class MediaOpeners(
    val album: (MusicRelease) -> Unit,
    val artist: (MusicArtistRef) -> Unit,
    val book: (BookCard, BookRow?) -> Unit,
    /** A request that arrived: the reader for ebooks and comics, else the title's page. */
    val ready: (MediaRequest) -> Unit,
    val item: (String) -> Unit,
)

/**
 * The request windows a page can open, and the openers for its cards (web: useMusicDialog, useBookDialog and the cards'
 * links). [onMessage] gets the toast once a request is sent.
 */
@Composable
fun mediaOpeners(onMessage: (String) -> Unit): MediaOpeners {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val scope = rememberCoroutineScope()
    var artist by remember { mutableStateOf<MusicArtistViewModel.Target?>(null) }
    var book by remember { mutableStateOf<BookRequestViewModel.Target?>(null) }
    artist?.let { RequestArtistSheet(it, onDismiss = { artist = null }, onDone = onMessage) }
    book?.let { RequestBookSheet(it, onDismiss = { book = null }, onDone = onMessage) }
    val openItem: (String) -> Unit = { id ->
        libraryUuid(id)?.let { uuid ->
            scope.launch { runCatching { Browse(session).item(uuid) }.getOrNull()?.let(nav::open) ?: nav.go(Route.Details(uuid)) }
        }
    }
    return remember(nav, session) {
        MediaOpeners(
            album = { r ->
                if (r.status == "server" && r.itemId != null) openItem(r.itemId!!)
                else r.artistMbid?.let { artist = MusicArtistViewModel.Target(it, r.artist, preselect = if (r.status == "none") r.mbid else null) }
            },
            artist = { a -> artist = MusicArtistViewModel.Target(a.mbid, a.name, imageUrl = a.imageUrl) },
            book = { b, row ->
                val canAsk = (b.status == "none" || (b.status == "server" && b.formats.any { it !in b.onServer })) && b.formats.isNotEmpty()
                if (b.status == "server" && b.itemId != null && !canAsk) openItem(b.itemId!!) else book = BookRequestViewModel.Target(b, row)
            },
            ready = { r ->
                val id = r.itemId
                val uuid = libraryUuid(id)
                if (uuid != null && MediaFormat.opensReader(r)) nav.go(Route.Reader(uuid)) else if (id != null) openItem(id)
            },
            item = openItem,
        )
    }
}

/** Opens [url] in the browser. */
fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

// ── See all ──

/** A Discover row's "See all" (web: MusicBrowse, BooksBrowse): its albums or books three to a row (no board: the row's cards). */
@Composable
fun MediaRowRoute(music: Boolean, row: String) {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val vm = viewModel(key = "media-row:$music:$row") { MediaRowViewModel(session, music, row) }
    val state by vm.state.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    val open = mediaOpeners { message = it }
    MediaRowScreen(
        music, row, state, onBack = nav::back, onAlbum = open.album, onBook = { b -> open.book(b, vm.bookRow) }, onRetry = vm::load,
        cover = vm.api::bookCover, message = message, onMessageShown = { message = null },
    )
}

@Composable
fun MediaRowScreen(
    music: Boolean,
    row: String,
    state: MediaRowViewModel.State,
    onBack: () -> Unit,
    onAlbum: (MusicRelease) -> Unit,
    onBook: (BookCard) -> Unit,
    onRetry: () -> Unit,
    cover: (BookCard) -> String? = { it.coverUrl },
    message: String? = null,
    onMessageShown: () -> Unit = {},
    today: LocalDate = LocalDate.now(),
) {
    val musicRow = MusicRow.entries.firstOrNull { it.key == row }.takeIf { music }
    val bookRow = BookRow.entries.firstOrNull { it.key == row }.takeIf { !music }
    val title = musicRow?.title ?: bookRow?.title
    Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
        LazyVerticalGrid(
            GridCells.Fixed(3),
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "head", span = { GridItemSpan(maxLineSpan) }) { BackHead(if (title != null) t(title) else t("List not found"), onBack) }
            val albums = state.albums
            val books = state.books
            when {
                title == null -> wide("missing") { Message(t("This list isn't here"), t("The link may be old.")) }
                (if (music) albums else books) == null -> items(9) { CardSkeleton(108, if (music) 1f else 2f / 3f, Modifier.fillMaxWidth()) }
                state.failed && (if (music) albums.isNullOrEmpty() else books.isNullOrEmpty()) -> wide("failed") {
                    Message(t("Couldn’t load this list"), t("Try again in a moment."), t("Try again"), onRetry)
                }
                music && albums.isNullOrEmpty() -> wide("empty") { Message(t("Nothing here right now"), t("Check back later for new albums.")) }
                !music && books.isNullOrEmpty() -> wide("empty") { Message(t("Nothing here right now"), t("Check back later for new books.")) }
                music -> items(albums!!, key = { it.mbid }) { r ->
                    val card = state.asked.release(r)
                    AlbumCard(card, MediaFormat.releaseMeta(card, musicRow, today), onClick = { onAlbum(card) }, modifier = Modifier.fillMaxWidth())
                }
                else -> items(books!!, key = { it.workId }) { b ->
                    val card = state.asked.book(b)
                    BookCardView(card, cover(card), MediaFormat.bookMeta(card, bookRow, today), onClick = { onBook(card) }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        Toast(message, onMessageShown, Modifier.align(Alignment.BottomCenter))
    }
}

private fun LazyGridScope.wide(key: String, content: @Composable () -> Unit) = item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun BackHead(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = topInset() - 4.dp).offset(x = (-12).dp), verticalAlignment = Alignment.CenterVertically) {
        HeaderButton(WebIcons.ArrowLeft, onBack, Modifier.padding(end = 4.dp))
        SText(title, Modifier.weight(1f), size = 20.sp, weight = 900, color = SeedyColors.White, maxLines = 1)
    }
}

// ── Search ──

/** Music or book search (web: the Frame's search and its results): MusicBrainz artists and albums, or books. */
@Composable
fun MediaSearchRoute(music: Boolean) {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val vm = viewModel(key = "media-search:$music") { MediaSearchViewModel(session, music) }
    val state by vm.state.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    val open = mediaOpeners { message = it }
    MediaSearchScreen(
        music, state, onText = vm::setText, onBack = nav::back, onRetry = vm::retry,
        onArtist = open.artist, onAlbum = open.album, onBook = { open.book(it, null) }, cover = vm.api::bookCover,
        message = message, onMessageShown = { message = null },
    )
}

@Composable
fun MediaSearchScreen(
    music: Boolean,
    state: MediaSearchViewModel.State,
    onText: (String) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onArtist: (MusicArtistRef) -> Unit,
    onAlbum: (MusicRelease) -> Unit,
    onBook: (BookCard) -> Unit,
    cover: (BookCard) -> String? = { it.coverUrl },
    message: String? = null,
    onMessageShown: () -> Unit = {},
    bottomPadding: Dp = 0.dp,
    autofocus: Boolean = true,
    today: LocalDate = LocalDate.now(),
) {
    Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
        LazyVerticalGrid(
            GridCells.Fixed(3),
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPadding + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            wide("field") {
                Box(Modifier.padding(top = topInset())) {
                    SearchField(
                        state.text, onText, onBack, autofocus,
                        if (music) t("Find any artist or album to request") else t("Find any book, author or series to request"),
                    )
                }
            }
            val q = state.query
            when {
                state.text.isBlank() -> wide("idle") {
                    Message(
                        if (music) t("Search MusicBrainz for artists and albums") else t("Search for books, authors and series"),
                        if (music) t("From Lidarr · any artist or album on MusicBrainz") else t("Type a title, an author or a series."),
                    )
                }
                state.loading && state.empty || q.isEmpty() -> items(9) { CardSkeleton(108, if (music) 1f else 2f / 3f, Modifier.fillMaxWidth()) }
                state.failed -> wide("failed") {
                    Message(if (music) t("Couldn’t search for music") else t("Couldn’t search for books"), t("Try again in a moment."), t("Try again"), onRetry)
                }
                state.empty -> wide("none") {
                    Message(
                        t("Nothing found for “{query}”", "query" to q),
                        if (music) t("Try another name, or check the spelling.") else t("Try another title or author, or check the spelling."),
                    )
                }
                else -> {
                    wide("results") { SText(t("Results for “{query}”", "query" to q), Modifier.padding(start = 4.dp), size = 16.sp, weight = 800, color = SeedyColors.White, maxLines = 1) }
                    if (music) {
                        if (state.artists.isNotEmpty()) {
                            wide("artists") { Section(t("Artists")) }
                            items(state.artists, key = { "a:" + it.mbid }) { a -> ArtistCard(a, { onArtist(a) }, Modifier.fillMaxWidth()) }
                        }
                        if (state.albums.isNotEmpty()) {
                            wide("albums") { Section(t("Albums")) }
                            items(state.albums, key = { "r:" + it.mbid }) { r ->
                                val card = state.asked.release(r)
                                AlbumCard(card, MediaFormat.releaseMeta(card, null, today), onClick = { onAlbum(card) }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    } else {
                        items(state.books, key = { it.workId }) { b ->
                            val card = state.asked.book(b)
                            BookCardView(card, cover(card), MediaFormat.bookMeta(card, null, today), onClick = { onBook(card) }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
        Toast(message, onMessageShown, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding))
    }
}

@Composable
private fun Section(text: String) = SText(text, Modifier.padding(start = 4.dp, top = 4.dp), size = 11.sp, weight = 800, tracking = 0.1f, uppercase = true, color = SeedyColors.Text2)

/** The search field of board "Android · Search", with the page's own words. */
@Composable
private fun SearchField(text: String, onText: (String) -> Unit, onBack: () -> Unit, autofocus: Boolean, placeholder: String) {
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
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).tap(onClick = onBack), contentAlignment = Alignment.Center) {
            SIcon(WebIcons.ArrowLeft, size = 22.dp, color = SeedyColors.White)
        }
        var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
        if (field.text != text) field = TextFieldValue(text, TextRange(text.length))
        Box(Modifier.weight(1f).padding(start = 2.dp), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) SText(placeholder, size = 15.sp, weight = 600, color = SeedyColors.Text3, maxLines = 1)
            BasicTextField(
                value = field,
                onValueChange = { field = it; if (it.text != text) onText(it.text) },
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
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).tap { onText("") }, contentAlignment = Alignment.Center) {
                SIcon(WebIcons.Close, size = 18.dp, color = SeedyColors.TextHero, stroke = 2.4f)
            }
        }
    }
    if (autofocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
