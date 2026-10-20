package io.github.arkmind.seedy.mobile.library

import androidx.compose.runtime.Composable
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.ui.components.NotYet
import java.util.UUID

/** Board "Android · Movies library": one library's grid. */
@Composable
fun LibraryRoute(id: UUID) {
    NotYet(t("Library"))
}
