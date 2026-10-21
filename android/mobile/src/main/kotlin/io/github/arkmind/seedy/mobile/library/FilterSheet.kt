package io.github.arkmind.seedy.mobile.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.browse.Filters
import io.github.arkmind.seedy.core.browse.Flag
import io.github.arkmind.seedy.core.feature.LibraryViewModel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.mobile.browse.BottomSheet
import io.github.arkmind.seedy.mobile.parts.HeaderButton
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import kotlinx.coroutines.delay

/** The library's Filters sheet (as the web's): what to show, quality, genres, decades and ratings, with the count. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(state: LibraryViewModel.State, count: suspend (Filters) -> Int?, onApply: (Filters) -> Unit, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(state.filters) }
    var n by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(draft) {
        n = null
        delay(220)
        n = count(draft)
    }
    BottomSheet(onDismiss) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SText(t("Filters"), Modifier.weight(1f), size = 17.sp, weight = 800, color = SeedyColors.White)
            HeaderButton(WebIcons.Close, onDismiss, color = SeedyColors.Text2, stroke = 2.4f)
        }
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Group(t("Show only")) { for (f in Flag.entries.take(4)) Option(t(f.label), f in draft.flags) { draft = draft.toggle(f) } }
            Group(t("Quality")) { for (f in Flag.entries.drop(4)) Option(t(f.label), f in draft.flags) { draft = draft.toggle(f) } }
            val o = state.options
            if (o == null) {
                Group(t("Genres")) { repeat(6) { Box(Modifier.height(36.dp).padding(horizontal = 40.dp).clip(RoundedCornerShape(999.dp)).background(SeedyColors.Surface)) } }
            } else {
                if (o.genres.isNotEmpty()) Group(t("Genres")) { for (g in o.genres) Option(g, g in draft.genres) { draft = draft.toggleGenre(g) } }
                if (o.decades.isNotEmpty()) Group(t("Decades")) { for (d in o.decades) Option("${d}s", d in draft.decades) { draft = draft.toggleDecade(d) } }
                if (o.ratings.isNotEmpty()) Group(t("Parental rating")) { for (r in o.ratings) Option(r, r in draft.ratings) { draft = draft.toggleRating(r) } }
            }
        }
        val bottom = max(26.dp, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp)
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = bottom), verticalAlignment = Alignment.CenterVertically) {
            SText(
                t("Clear all"),
                Modifier.clip(RoundedCornerShape(12.dp)).tap(enabled = draft.count > 0) { draft = Filters() }.padding(horizontal = 6.dp, vertical = 12.dp),
                size = 14.sp, weight = 700, color = if (draft.count > 0) SeedyColors.White else SeedyColors.Text3,
            )
            Spacer(Modifier.weight(1f))
            val label = when (val c = n) {
                null -> t("Show titles")
                0 -> t("No titles match")
                else -> t("Show {count}", "count" to tn(c, "{n} title", "{n} titles"))
            }
            Box(
                Modifier.height(48.dp).clip(RoundedCornerShape(13.dp)).background(SeedyColors.Inverse).tap { onApply(draft) }.padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                SText(label, size = 14.sp, weight = 800, color = SeedyColors.InkInverse, maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Group(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SText(label, size = 12.sp, weight = 800, tracking = 0.1f, color = SeedyColors.Text3, uppercase = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun Option(label: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.height(36.dp).clip(RoundedCornerShape(999.dp)).background(if (on) SeedyColors.Inverse else SeedyColors.Surface).tap(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (on) SIcon(WebIcons.Check, size = 14.dp, color = SeedyColors.InkInverse, stroke = 2.8f)
        SText(label, size = 13.sp, weight = if (on) 800 else 600, color = if (on) SeedyColors.InkInverse else SeedyColors.Text, maxLines = 1)
    }
}
