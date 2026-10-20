package io.github.arkmind.seedy.mobile.details

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.feature.DetailsViewModel
import io.github.arkmind.seedy.core.seedy.canRequest
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.seasons.RequestSeasonsSheet
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.currentSession
import java.util.UUID

/** Boards "Android · Series details" and "· Request seasons". */
@Composable
fun DetailsRoute(id: UUID) {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val context = LocalContext.current
    val images = LocalViewer.current.images
    val vm = viewModel(key = "details:$id") { DetailsViewModel(session, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    // The seasons the request sheet starts with ticked (empty: every open one); null while it's closed.
    var ask by rememberSaveable { mutableStateOf<List<Int>?>(null) }
    Box(Modifier.fillMaxSize()) {
        DetailsScreen(
            state = state,
            actions = DetailsActions(
                onBack = nav::back,
                onPlay = { vm.play(nav::play) },
                onEpisode = nav::play,
                onOpen = nav::open,
                onTrailer = {
                    vm.trailer { trailer ->
                        when (trailer) {
                            is DetailsViewModel.Trailer.Local -> nav.play(trailer.item)
                            is DetailsViewModel.Trailer.Url -> try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(trailer.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (_: ActivityNotFoundException) {
                            }
                        }
                    }
                },
                onPlayed = vm::togglePlayed,
                onFavorite = vm::toggleFavorite,
                onShuffle = { vm.shuffle(nav::play) },
                onSeason = vm::selectSeason,
                onRequest = { ask = it },
                onMarkSeason = vm::toggleSeasonPlayed,
            ),
            canRequest = session.canRequest,
            bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        )
        val item = state.item
        val target = item?.let { vm.seasonsTarget(images?.poster(it, 200), images?.backdrop(it, 780)) }
        if (ask != null && target != null) {
            RequestSeasonsSheet(target, onDismiss = { ask = null }, onDone = vm::markRequested, details = state.seerr, initial = ask)
        }
    }
}
