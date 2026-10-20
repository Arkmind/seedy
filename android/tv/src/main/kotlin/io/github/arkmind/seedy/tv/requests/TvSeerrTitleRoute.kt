package io.github.arkmind.seedy.tv.requests

import androidx.compose.runtime.Composable
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.ui.components.NotYet

/** A Seerr title that isn't on the server. */
@Composable
fun TvSeerrTitleRoute(mediaType: String, tmdbId: Int) {
    NotYet(t("Request"))
}
