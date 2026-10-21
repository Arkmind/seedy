package io.github.arkmind.seedy.tv.details

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.feature.DetailsViewModel
import io.github.arkmind.seedy.core.seedy.canRequest
import io.github.arkmind.seedy.tv.nav.LocalTvNav
import io.github.arkmind.seedy.tv.seasons.TvRequestSeasonsDialog
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.currentSession
import java.util.UUID

/** Boards "Android TV · Series details" and "· Request seasons". */
@Composable
fun TvDetailsRoute(id: UUID) {
    val session = currentSession()
    val nav = LocalTvNav.current
    val context = LocalContext.current
    val images = LocalViewer.current.images
    val vm = viewModel(key = "details:$id") { DetailsViewModel(session, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    // Back from the player: progress and watched marks may have changed.
    LaunchedEffect(Unit) { vm.refresh() }
    var ask by rememberSaveable { mutableStateOf<List<Int>?>(null) }
    val play = remember { FocusRequester() }
    Box(Modifier.fillMaxSize()) {
        TvDetailsScreen(
            state,
            TvDetailsActions(
                onPlay = { vm.play(nav::play) },
                onEpisode = nav::play,
                onOpen = nav::open,
                onTrailer = {
                    vm.trailer { trailer ->
                        when (trailer) {
                            is DetailsViewModel.Trailer.Local -> nav.play(trailer.item)
                            // YouTube's TV app takes the link (Android TV has it).
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
            ),
            canRequest = session.canRequest,
            playFocus = play,
        )
        val item = state.item
        val target = item?.let { vm.seasonsTarget(images?.poster(it, 300), images?.backdrop(it, 1280)) }
        if (ask != null && target != null) {
            TvRequestSeasonsDialog(target, onDismiss = { ask = null }, onDone = vm::markRequested, details = state.seerr, initial = ask, returnFocus = { play.requestFocus() })
        }
    }
}
