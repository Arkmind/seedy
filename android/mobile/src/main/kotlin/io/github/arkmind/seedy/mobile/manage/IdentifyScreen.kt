package io.github.arkmind.seedy.mobile.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.manage.IdentifyActions
import io.github.arkmind.seedy.core.manage.IdentifyState
import io.github.arkmind.seedy.core.manage.IdentifyViewModel
import io.github.arkmind.seedy.core.manage.Match
import io.github.arkmind.seedy.mobile.admin.BoardField
import io.github.arkmind.seedy.mobile.admin.ErrorCard
import io.github.arkmind.seedy.mobile.admin.FieldSection
import io.github.arkmind.seedy.mobile.admin.LoadingCards
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.admin.PillStyle
import io.github.arkmind.seedy.mobile.admin.SaveBarRoom
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.settings.SettingsPage
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.components.boxShadow
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession

/** Identify (board ItemIdentify) as a phone page, for admins; [onDone] leaves with what to say. */
@Composable
fun IdentifyRoute(id: String, onBack: () -> Unit) {
    val session = currentSession()
    val vm = viewModel(key = "identify:$id") { IdentifyViewModel(session, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    IdentifyScreen(state, vm, onBack, onDone = { onBack() })
}

@Composable
fun IdentifyScreen(state: IdentifyState, actions: IdentifyActions, onBack: () -> Unit, onDone: (String) -> Unit = {}) {
    var message by remember { mutableStateOf<String?>(null) }
    SettingsPage(
        t("Identify"),
        lede = null,
        onBack = onBack,
        overlay = {
            if (!state.loading && !state.failed) IdentifyFooter(state, actions) { actions.apply(onDone) }
            Toast(message ?: state.error?.takeIf { !state.failed }, { message = null }, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
        },
    ) {
        ItemHead(state.poster, state.question, t("Pick the right match and Seedy replaces the info. The files stay where they are."))
        when {
            state.failed -> ErrorCard(t("Couldn’t load this title"), actions::retry, Modifier.padding(top = 16.dp), state.error.orEmpty())
            state.loading -> LoadingCards(2)
            else -> {
                FieldSection(null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            FieldLabel(t("Name"))
                            BoardField(state.name, actions::setName, onDone = actions::search)
                        }
                        Column(Modifier.weight(0.42f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            FieldLabel(t("Year"))
                            BoardField(state.year, actions::setYear, keyboard = KeyboardType.Number, onDone = actions::search)
                        }
                    }
                    val search = @Composable { modifier: Modifier ->
                        val enabled = !state.searching && state.name.isNotBlank()
                        Row(
                            modifier.height(46.dp).clip(RoundedCornerShape(13.dp)).background(if (enabled) SeedyColors.Inverse else SeedyColors.Surface2).tap(enabled = enabled, onClick = actions::search),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val ink = if (enabled) SeedyColors.InkInverse else SeedyColors.Text3
                            SIcon(ManageIcons.Search, size = 16.dp, color = ink)
                            SText(if (state.searching) t("Searching…") else t("Search"), size = 15.sp, weight = 700, color = ink, maxLines = 1)
                        }
                    }
                    val rows = state.ids.chunked(2)
                    for (row in rows) {
                        // An id alone on the last line has Search beside it, as on the board.
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                            for (f in row) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FieldLabel(t("{provider} ID", "provider" to f.label))
                                    BoardField(f.value, { actions.setId(f.key, it) }, placeholder = if (f.key.equals("Imdb", true)) "tt…" else t("Optional"), onDone = actions::search)
                                }
                            }
                            if (row.size == 1) search(Modifier.weight(1f))
                        }
                    }
                    if (rows.lastOrNull()?.size != 1) search(Modifier.fillMaxWidth())
                }
                Results(state, actions)
                SaveBarRoom()
            }
        }
    }
}

@Composable
private fun Results(state: IdentifyState, actions: IdentifyActions) {
    val matches = state.matches
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SText(if (matches == null) t("Searching…") else state.summary, size = 15.sp, weight = 800, color = SeedyColors.White)
        val from = matches.orEmpty().mapNotNull { m -> m.meta.split(" · ").lastOrNull()?.takeIf { it in setOf("TMDb", "TVDb", "IMDb") } }.distinct()
        if (from.isNotEmpty()) SText(t("From {providers} · sorted by how close they are", "providers" to io.github.arkmind.seedy.core.admin.listing(from)), size = 12.sp, color = SeedyColors.Text3)
    }
    when {
        matches == null -> Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(2) { Box(Modifier.weight(1f).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Panel)) }
        }
        matches.isEmpty() -> SText(t("Nothing found. Try another name, the year, or an id from the provider’s site."), Modifier.padding(horizontal = 20.dp), size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Text2)
        else -> Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            for (row in matches.withIndex().chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    for ((i, m) in row) MatchCard(m, i == state.picked, Modifier.weight(1f)) { actions.pick(i) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MatchCard(m: Match, picked: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Column(modifier.tap(role = Role.RadioButton, onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .then(if (picked) Modifier.drawBehind {
                    val gap = 4.dp.toPx()
                    drawRoundRect(accent, topLeft = Offset(-gap, -gap), size = Size(size.width + gap * 2, size.height + gap * 2), cornerRadius = CornerRadius(15.dp.toPx()), style = Stroke(2.dp.toPx()))
                } else Modifier)
                .clip(RoundedCornerShape(12.dp)).background(SeedyColors.Surface),
        ) {
            if (m.art == null) SText(m.title, Modifier.padding(12.dp), size = 15.sp, weight = 900, lineHeight = 1f, color = SeedyColors.Text, uppercase = true, maxLines = 3)
            else Artwork(m.art, Modifier.matchParentSize(), placeholder = SeedyColors.Surface)
            if (m.current) CurrentTag()
            if (picked) {
                Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(24.dp).clip(RoundedCornerShape(8.dp)).background(accent), contentAlignment = Alignment.Center) {
                    SIcon(WebIcons.Check, size = 14.dp, color = SeedyColors.InkInverse, stroke = 3.2f)
                }
            }
        }
        Column(Modifier.padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            SText(m.title, size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (m.meta.isNotEmpty()) SText(m.meta, size = 12.sp, color = SeedyColors.Text3, maxLines = 2)
            if (m.source.isNotEmpty()) SText(m.source, size = 12.sp, weight = 700, color = if (picked) accent else SeedyColors.Text2, maxLines = 2)
        }
    }
}

@Composable
private fun BoxScope.CurrentTag() {
    SText(
        t("Current match"),
        Modifier.align(Alignment.BottomStart).padding(8.dp).clip(CircleShape).background(SeedyColors.Bg.copy(alpha = 0.85f)).padding(horizontal = 9.dp, vertical = 3.dp),
        size = 11.sp, weight = 800, color = SeedyColors.White, maxLines = 1,
    )
}

/** The pinned foot: Replace the existing artwork, and Use this match. */
@Composable
private fun BoxScope.IdentifyFooter(state: IdentifyState, actions: IdentifyActions, onApply: () -> Unit) {
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Row(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .boxShadow(Color.Black.copy(alpha = 0.5f), blur = 30.dp, y = (-10).dp)
            .background(SeedyColors.Row).tap {}
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = max(26.dp, nav + 12.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.weight(1f).tap(role = Role.Checkbox) { actions.setReplaceImages(!state.replaceImages) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CheckBox(state.replaceImages)
            SText(t("Replace the existing artwork"), size = 13.sp, weight = 700, color = SeedyColors.White, maxLines = 2)
        }
        PillButton(if (state.applying) t("Applying…") else t("Use this match"), onApply, style = PillStyle.Light, enabled = state.canApply, height = 44.dp)
    }
}
