package io.github.arkmind.seedy.mobile.books

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.books.BookSort
import io.github.arkmind.seedy.core.books.BooksView
import io.github.arkmind.seedy.core.books.BooksViewModel
import io.github.arkmind.seedy.core.books.ContinueKind
import io.github.arkmind.seedy.core.books.uuidOf
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.audio.LocalAudio
import io.github.arkmind.seedy.mobile.audio.miniBarSpace
import io.github.arkmind.seedy.mobile.browse.BottomSheet
import io.github.arkmind.seedy.mobile.manage.ItemMenuSheet
import io.github.arkmind.seedy.mobile.manage.ManagePerms
import io.github.arkmind.seedy.mobile.manage.MenuEntry
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.nav.Route
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.settings.PickOption
import io.github.arkmind.seedy.mobile.settings.SheetHead
import io.github.arkmind.seedy.player.audio.AudioEngine
import io.github.arkmind.seedy.ui.theme.currentSession
import java.util.UUID

/** Board "Android · Books": a books library, opened on [view] ("Series"…) and [focus] (a series or author) when given. */
@Composable
fun BooksLibraryRoute(id: UUID, view: String? = null, focus: String? = null) {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val audio = LocalAudio.current
    val start = BooksView.entries.firstOrNull { it.name.equals(view, ignoreCase = true) } ?: BooksView.All
    val vm = viewModel(key = "books:$id:$view:$focus") { BooksViewModel(session, id, start, focus) }
    val state by vm.state.collectAsStateWithLifecycle()
    val playing = audio?.state?.collectAsStateWithLifecycle()?.value?.takeIf { it.isBook }?.book?.id?.let(::uuidOf)
    val admin = remember(session) { ManagePerms.of(session).admin }
    var menu by remember { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(playing) { vm.setPlaying(playing) }
    // Back from a book, the reader or the player: places may have moved.
    LaunchedEffect(Unit) { vm.refresh() }
    BackHandler(enabled = state.focus != null && state.query == null) { vm.open(state.view, null) }
    Box(Modifier.fillMaxSize()) {
        BooksScreen(
            state,
            BooksActions(
                onBack = nav::back,
                onSearch = vm::search,
                onMore = { menu = true },
                onView = vm::setView,
                onFocus = vm::open,
                onSeeAll = { v, s ->
                    vm.setView(v)
                    if (s != null) vm.setSort(s)
                },
                onOpen = { nav.go(Route.Book(it.id)) },
                onContinue = { e ->
                    if (e.kind == ContinueKind.Audio) {
                        audio?.listenTo(e.item.id)
                        nav.go(Route.AudiobookPlayer)
                    } else {
                        nav.go(Route.Reader(e.item.id))
                    }
                },
                onRetry = { vm.load(true) },
            ),
            bottomPadding = miniBarSpace(),
            now = System.currentTimeMillis(),
        )
        if (menu) {
            val entries = buildList {
                add(MenuEntry(BookIcons.Sort, t("Sort by"), t(state.sortOrDefault.label), more = true, run = { sorting = true }))
                add(MenuEntry(BookIcons.Refresh, t("Refresh"), run = { vm.load(true) }))
                if (admin) {
                    add(
                        MenuEntry(BookIcons.Refresh, t("Scan library files"), t("Look for new and changed media"), line = true, run = {
                            vm.scan { ok -> message = if (ok) t("Scanning {name}", "name" to state.name) else t("Couldn't start the scan") }
                        }),
                    )
                }
            }
            ItemMenuSheet(state.name, entries) { menu = false }
        }
        if (sorting) {
            SortSheet(state.sortOrDefault, onPick = { s ->
                // On All a sort opens the full Ebooks grid in that order.
                if (state.view == BooksView.All) vm.setView(BooksView.Ebooks)
                vm.setSort(s)
            }) { sorting = false }
        }
        Toast(message, { message = null }, Modifier.align(Alignment.BottomCenter).padding(bottom = miniBarSpace()))
    }
}

@Composable
private fun SortSheet(current: BookSort, onPick: (BookSort) -> Unit, onClose: () -> Unit) {
    BottomSheet(onClose) {
        SheetHead(t("Sort by"))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            for (s in BookSort.entries) PickOption(t(s.label), s == current) { onPick(s); onClose() }
        }
    }
}

/** Listen: carries on with the book if it's the one loaded, else starts it where this profile left off. */
fun AudioEngine.listenTo(id: UUID) {
    val s = state.value
    if (s.isBook && uuidOf(s.book?.id) == id) play() else playAudiobook(id)
}
