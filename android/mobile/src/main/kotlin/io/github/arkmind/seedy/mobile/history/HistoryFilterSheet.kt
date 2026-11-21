package io.github.arkmind.seedy.mobile.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.arkmind.seedy.core.history.HistoryFilters
import io.github.arkmind.seedy.core.history.HistoryOptions
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.browse.BottomSheet
import io.github.arkmind.seedy.mobile.library.FiltersHead
import io.github.arkmind.seedy.mobile.library.Group
import io.github.arkmind.seedy.mobile.library.Option
import io.github.arkmind.seedy.mobile.library.SheetActions
import io.github.arkmind.seedy.ui.theme.SeedyColors
import kotlinx.coroutines.delay

/**
 * The history's Filters sheet (as the web's): finished or in progress, hidden titles, when and on which device, with
 * how many entries they leave. [options] null while the years and devices load.
 */
@Composable
fun HistoryFilterSheet(
    initial: HistoryFilters,
    options: HistoryOptions?,
    count: suspend (HistoryFilters) -> Int?,
    onApply: (HistoryFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var n by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(draft) {
        n = null
        delay(220)
        n = count(draft)
    }
    BottomSheet(onDismiss) {
        FiltersHead(onDismiss)
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Group(t("Show only")) {
                for (s in listOf(HistoryFilters.FINISHED, HistoryFilters.IN_PROGRESS)) {
                    Option(HistoryFilters.stateLabel(s), draft.state == s) { draft = draft.toggleState(s) }
                }
            }
            Group(t("Hidden titles")) { Option(t("Show hidden"), draft.hidden) { draft = draft.copy(hidden = !draft.hidden) } }
            // A year or device picked earlier stays offered, so it can be turned off.
            val years = ((options?.years?.map { "$it" } ?: emptyList()) + listOfNotNull(draft.period?.takeIf { it.toIntOrNull() != null }))
                .distinct().sortedDescending()
            Group(t("When")) {
                for (p in listOf("7d", "30d") + years) Option(HistoryFilters.periodLabel(p), draft.period == p) { draft = draft.togglePeriod(p) }
            }
            if (options == null) {
                Group(t("Devices")) { repeat(3) { Box(Modifier.width(110.dp).height(36.dp).clip(RoundedCornerShape(999.dp)).background(SeedyColors.Surface)) } }
            } else {
                val devices = options.devices + draft.devices.filter { it !in options.devices }
                if (devices.isNotEmpty()) Group(t("Devices")) { for (d in devices) Option(d, d in draft.devices) { draft = draft.toggleDevice(d) } }
            }
        }
        SheetActions(n, draft.count > 0, onClear = { draft = HistoryFilters() }, onApply = { onApply(draft) })
    }
}
