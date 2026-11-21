package io.github.arkmind.seedy.mobile.library

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/** A sort the Sort menu offers: its label (translated), whether it starts descending, and whether it has a direction. */
class SortChoice(val label: String, val descending: Boolean, val directional: Boolean = true)

/** The Sort menu (libraries, Viewing history): the sorts, then Ascending and Descending; [onPick] gets the index and direction. */
@Composable
fun SortMenu(open: Boolean, choices: List<SortChoice>, current: Int, descending: Boolean, onDismiss: () -> Unit, onPick: (Int, Boolean) -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss, containerColor = SeedyColors.Row, shape = RoundedCornerShape(12.dp)) {
        SText(t("Sort by"), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), size = 11.sp, weight = 800, tracking = 0.1f, color = SeedyColors.Text3, uppercase = true)
        choices.forEachIndexed { i, s ->
            DropdownMenuItem(
                text = { SText(s.label, size = 14.sp, weight = if (i == current) 800 else 600, color = SeedyColors.White) },
                trailingIcon = if (i == current) ({ SIcon(WebIcons.Check, size = 16.dp, color = LocalAccent.current) }) else null,
                onClick = { onPick(i, if (i == current) descending else s.descending) },
            )
        }
        if (choices.getOrNull(current)?.directional == true) {
            HorizontalDivider(color = SeedyColors.Surface2)
            for (d in listOf(false, true)) {
                DropdownMenuItem(
                    text = { SText(if (d) t("Descending") else t("Ascending"), size = 14.sp, weight = if (d == descending) 800 else 600, color = SeedyColors.White) },
                    leadingIcon = { SIcon(WebIcons.SortEpisodes, size = 16.dp, color = SeedyColors.Text2) },
                    trailingIcon = if (d == descending) ({ SIcon(WebIcons.Check, size = 16.dp, color = LocalAccent.current) }) else null,
                    onClick = { onPick(current, d) },
                )
            }
        }
    }
}
