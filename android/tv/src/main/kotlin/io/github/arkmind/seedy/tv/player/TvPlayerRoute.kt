package io.github.arkmind.seedy.tv.player

import androidx.compose.runtime.Composable
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.ui.components.NotYet
import java.util.UUID

/** Boards "Android TV · Player" and its panels. */
@Composable
fun TvPlayerRoute(id: UUID) {
    NotYet(t("Player"))
}
