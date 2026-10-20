package io.github.arkmind.seedy.tv.details

import androidx.compose.runtime.Composable
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.ui.components.NotYet
import java.util.UUID

/** Boards "Android TV · Series details" and "· Request seasons". */
@Composable
fun TvDetailsRoute(id: UUID) {
    NotYet(t("Details"))
}
