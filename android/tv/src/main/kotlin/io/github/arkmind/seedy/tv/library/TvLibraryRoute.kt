package io.github.arkmind.seedy.tv.library

import androidx.compose.runtime.Composable
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.ui.components.NotYet
import java.util.UUID

/** Board "Android TV · Movies library": one library's grid (also the rail's Movies and Shows). */
@Composable
fun TvLibraryRoute(id: UUID) {
    NotYet(t("Library"))
}
