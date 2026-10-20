package io.github.arkmind.seedy.mobile.details

import androidx.compose.runtime.Composable
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.ui.components.NotYet
import java.util.UUID

/** Boards "Android · Series details" and "· Request seasons". */
@Composable
fun DetailsRoute(id: UUID) {
    NotYet(t("Details"))
}
