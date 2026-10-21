package io.github.arkmind.seedy.tv.seasons

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import io.github.arkmind.seedy.core.feature.RequestSeasonsViewModel
import io.github.arkmind.seedy.core.feature.SeasonsResult
import io.github.arkmind.seedy.core.feature.SeasonsTarget
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.seedy.SeerrDetails
import io.github.arkmind.seedy.tv.parts.TvPressable
import io.github.arkmind.seedy.tv.parts.bp
import io.github.arkmind.seedy.tv.parts.bsp
import io.github.arkmind.seedy.tv.parts.focusRing
import io.github.arkmind.seedy.tv.parts.focusScale
import io.github.arkmind.seedy.tv.parts.focusSoon
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession

/**
 * Board "Android TV · Request seasons": the dialog that asks Seerr for a series' seasons, over any page (a series'
 * details, Search, a Seerr title). Show it while it should be open; [onDone] gets what was requested, then it closes.
 */
@Composable
fun TvRequestSeasonsDialog(
    target: SeasonsTarget,
    onDismiss: () -> Unit,
    onDone: (SeasonsResult) -> Unit = {},
    details: SeerrDetails? = null,
    initial: List<Int>? = null,
    /** Where the remote goes back to once the dialog has closed (it keeps the remote while it's open). */
    returnFocus: (() -> Boolean)? = null,
) {
    val session = currentSession()
    val view = androidx.compose.ui.platform.LocalView.current
    if (returnFocus != null) {
        // The dialog may still be on screen for a frame (and keeps the remote while it is): try each frame.
        androidx.compose.runtime.DisposableEffect(Unit) {
            onDispose {
                var tries = 0
                fun attempt() {
                    if (!runCatching { returnFocus() }.getOrDefault(false) && ++tries < 30) view.postOnAnimation(::attempt)
                }
                view.postOnAnimation(::attempt)
            }
        }
    }
    val vm = viewModel(key = "request-seasons") { RequestSeasonsViewModel(session) }
    LaunchedEffect(target, initial) { vm.open(target, details, initial) }
    val state by vm.state.collectAsStateWithLifecycle()
    if (state.target != target) return
    TvRequestSeasonsContent(
        state,
        onToggle = vm::toggle, onToggleAll = vm::toggleAll, on4k = vm::set4k,
        advanced = TvAdvancedActions(vm::setServer, vm::setProfile, vm::setFolder, vm::setUser),
        onRetry = vm::retry,
        onSubmit = { vm.submit { r -> onDone(r); onDismiss() } },
        onDismiss = onDismiss,
    )
}

class TvAdvancedActions(val server: (Int) -> Unit, val profile: (Int) -> Unit, val folder: (String) -> Unit, val user: (Int) -> Unit)

/** The dialog itself over the dimmed page (stateless, for screenshot tests). */
@Composable
fun TvRequestSeasonsContent(
    state: RequestSeasonsViewModel.State,
    onToggle: (Int) -> Unit,
    onToggleAll: () -> Unit,
    on4k: (Boolean) -> Unit,
    advanced: TvAdvancedActions,
    onRetry: () -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    firstFocus: FocusRequester = remember { FocusRequester() },
    /** The season the remote starts on (default: the first that can be requested). */
    focusSeason: Int? = null,
) = io.github.arkmind.seedy.ui.components.Overlay {
    BackHandler(onBack = onDismiss)
    val panel = Color(0xFF141414)
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xC7050505))) {
        val shape = RoundedCornerShape(28.bp)
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 96.bp).width(1040.bp).heightIn(max = maxHeight - 140.bp)
                .shadow(50.bp, shape).border(2.bp, Color.White.copy(alpha = 0.06f), shape).clip(shape).background(panel)
                // The remote stays in the dialog until it closes.
                .focusProperties { onExit = { cancelFocusChange() } }.focusGroup(),
        ) {
            Head(state)
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 40.bp, end = 40.bp, top = 8.bp, bottom = 24.bp),
                verticalArrangement = Arrangement.spacedBy(10.bp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SText(t("Seasons"), Modifier.weight(1f), size = 22.bsp, weight = 800, color = SeedyColors.White)
                    if (state.open.size > 1) SelectAll(state, onToggleAll)
                }
                val firstOpen = focusSeason ?: state.rows.firstOrNull { !it.locked }?.n
                when {
                    state.failed && state.details == null -> Plain(t("Couldn't load the seasons from Seerr."), t("Try again"), onRetry, Modifier.focusRequester(firstFocus))
                    state.details == null -> repeat(3) { Box(Modifier.fillMaxWidth().height(80.bp).clip(RoundedCornerShape(18.bp)).background(SeedyColors.Row)) }
                    state.rows.isEmpty() -> SText(t("Seerr doesn’t list any seasons for this series yet."), size = 19.bsp, color = SeedyColors.Text2)
                    else -> Column(verticalArrangement = Arrangement.spacedBy(8.bp)) {
                        for (row in state.rows) {
                            SeasonRow(row, on = !row.locked && row.n in state.picked, { onToggle(row.n) }, if (row.n == firstOpen) Modifier.focusRequester(firstFocus) else Modifier)
                        }
                    }
                }
                if (state.can4k || state.hasAdvanced) {
                    Row(Modifier.padding(top = 6.bp), horizontalArrangement = Arrangement.spacedBy(10.bp)) {
                        if (state.can4k) {
                            Option(t("Request in 4K"), t("Sent to the 4K server separately"), { on4k(!state.is4k) }, Modifier.weight(1f)) { TvSwitch(state.is4k) }
                        }
                        if (state.hasAdvanced) Advanced(state, advanced, Modifier.weight(1f))
                    }
                }
            }
            Footer(state, onSubmit, onDismiss)
        }
    }
    LaunchedEffect(state.details != null || state.failed) { if (state.details != null || state.failed) firstFocus.focusSoon() }
}

@Composable
private fun Head(state: RequestSeasonsViewModel.State) {
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(Color(0xFF463B5E), Color(0xFF2F2840), Color(0xFF16121F)))))
        state.backdrop?.let { AsyncImage(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color(0x1A141414), Color(0xFF141414)))))
        Row(
            Modifier.padding(start = 40.bp, end = 40.bp, top = 36.bp, bottom = 22.bp),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(28.bp),
        ) {
            Artwork(state.poster, Modifier.size(112.bp, 168.bp).shadow(15.bp, RoundedCornerShape(14.bp)).clip(RoundedCornerShape(14.bp)), placeholder = Color(0xFF2F2840))
            Column(Modifier.padding(bottom = 6.bp), verticalArrangement = Arrangement.spacedBy(8.bp)) {
                SText(
                    if (state.target?.fromLibrary == true) t("Request missing seasons") else t("Request from Seerr"),
                    size = 16.bsp, weight = 800, tracking = 0.1f, color = SeedyColors.Text2, uppercase = true, maxLines = 1,
                )
                SText(state.title, size = 38.bsp, weight = 900, lineHeight = 1.05f, color = SeedyColors.White, maxLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.bp)) {
                    SText(
                        t("SERIES"), Modifier.clip(CircleShape).background(SeedyColors.TypeSeries).padding(horizontal = 12.bp, vertical = 4.bp),
                        size = 13.bsp, weight = 800, tracking = 0.1f, color = SeedyColors.White,
                    )
                    SText(state.meta, size = 18.bsp, color = SeedyColors.Text2, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SelectAll(state: RequestSeasonsViewModel.State, onClick: () -> Unit) {
    TvPressable(onClick) { focused ->
        Row(
            Modifier.focusRing(focused, radius = 15.bp, gap = 3.bp, ring = 4.bp, gapColor = Color(0xFF141414)).height(52.bp).clip(RoundedCornerShape(15.bp))
                .background(if (focused) SeedyColors.Inverse else Color.White.copy(alpha = 0.06f)).padding(horizontal = 16.bp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.bp),
        ) {
            val label = if (state.target?.fromLibrary == true) tn(state.open.size, "Select the {n} missing season", "Select all {n} missing seasons")
            else tn(state.open.size, "Select the {n} open season", "Select all {n} open seasons")
            SText(label, size = 18.bsp, weight = 700, color = if (focused) SeedyColors.InkInverse else SeedyColors.Text, maxLines = 1)
            CheckBox(on = state.all || state.some, size = 28.bp, check = 19.bp, mixed = state.some)
        }
    }
}

@Composable
private fun CheckBox(on: Boolean, size: androidx.compose.ui.unit.Dp, check: androidx.compose.ui.unit.Dp, locked: Boolean = false, mixed: Boolean = false) {
    Box(Modifier.size(size).clip(RoundedCornerShape(if (size > 30.bp) 9.bp else 8.bp)).background(if (on) LocalAccent.current else SeedyColors.Surface2), contentAlignment = Alignment.Center) {
        if (on || locked) SIcon(if (mixed) WebIcons.Minus else WebIcons.Check, size = check, color = if (on) SeedyColors.InkInverse else SeedyColors.Text3, stroke = 3.2f)
    }
}

@Composable
private fun SeasonRow(row: RequestSeasonsViewModel.Row, on: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val accent = LocalAccent.current
    TvPressable({ if (!row.locked) onClick() }, modifier) { focused ->
        val shape = RoundedCornerShape(18.bp)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 80.bp).focusRing(focused, radius = 18.bp, gap = 3.bp, ring = 4.bp, gapColor = Color(0xFF141414)).clip(shape)
                .background(if (on) Color(0xFF1C1C1C) else Color.Transparent)
                .then(if (focused) Modifier else Modifier.border(2.bp, if (on) accent else Color(0xFF222222), shape))
                .padding(horizontal = 20.bp, vertical = 10.bp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.bp),
        ) {
            CheckBox(on = on, size = 32.bp, check = 21.bp, locked = row.locked)
            Artwork(row.thumb, Modifier.size(40.bp, 60.bp).clip(RoundedCornerShape(8.bp)).alpha(if (row.locked) 0.5f else 1f), placeholder = Color(0xFF2F2840))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.bp)) {
                SText(row.name, size = 21.bsp, weight = 700, color = if (row.locked) SeedyColors.Text2 else SeedyColors.White, maxLines = 1)
                if (row.sub.isNotEmpty()) SText(row.sub, size = 16.bsp, color = SeedyColors.Text3, maxLines = 1)
            }
            StatusPill(row)
        }
    }
}

@Composable
private fun StatusPill(row: RequestSeasonsViewModel.Row) {
    val (bg, ink, dot) = when (row.kind) {
        RequestSeasonsViewModel.Kind.Server -> Triple(SeedyColors.Ok.copy(alpha = 0.1f), SeedyColors.Ok, SeedyColors.Ok)
        RequestSeasonsViewModel.Kind.Pending -> Triple(SeedyColors.Pending.copy(alpha = 0.1f), SeedyColors.Pending, SeedyColors.Pending)
        RequestSeasonsViewModel.Kind.Blocked -> Triple(Color(0xFF262626), SeedyColors.TextHero, SeedyColors.Danger)
        else -> Triple(Color(0xFF262626), SeedyColors.TextHero, Color(0xFF6A6A6A))
    }
    Row(
        Modifier.clip(CircleShape).background(bg).padding(horizontal = 14.bp, vertical = 6.bp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.bp),
    ) {
        Box(Modifier.size(8.bp).background(dot, CircleShape))
        SText(row.status, size = 16.bsp, weight = 700, color = ink, maxLines = 1)
    }
}

@Composable
private fun Option(title: String, sub: String, onClick: () -> Unit, modifier: Modifier = Modifier, trailing: @Composable () -> Unit) {
    TvPressable(onClick, modifier) { focused ->
        Row(
            Modifier.fillMaxWidth().focusRing(focused, radius = 18.bp, gap = 3.bp, ring = 4.bp, gapColor = Color(0xFF141414)).clip(RoundedCornerShape(18.bp))
                .background(SeedyColors.Row).padding(horizontal = 20.bp, vertical = 14.bp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.bp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.bp)) {
                SText(title, size = 19.bsp, weight = 700, color = SeedyColors.White, maxLines = 1)
                if (sub.isNotEmpty()) SText(sub, size = 15.bsp, color = SeedyColors.Text3, maxLines = 1)
            }
            trailing()
        }
    }
}

/** The TV boards' switch: 60×34, the accent when on, a 24 px knob. */
@Composable
private fun TvSwitch(on: Boolean) {
    val knob by animateDpAsState(if (on) 31.bp else 5.bp, label = "knob")
    Box(Modifier.size(60.bp, 34.bp).clip(CircleShape).background(if (on) LocalAccent.current else SeedyColors.Surface2)) {
        Box(Modifier.align(Alignment.CenterStart).offset(x = knob).size(24.bp).background(if (on) SeedyColors.InkInverse else SeedyColors.Text3, CircleShape))
    }
}

/** Advanced: "HD-1080p · /data/tv · as you"; OK opens its choices, each OK moves to the next option. */
@Composable
private fun Advanced(state: RequestSeasonsViewModel.State, actions: TvAdvancedActions, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.bp)) {
        Option(t("Advanced"), state.advancedSummary, { open = !open }) {
            SIcon(if (open) WebIcons.ChevronDown else AppIcons.Chevron, size = 22.bp, color = SeedyColors.Text2, stroke = 2.4f)
        }
        if (!open) return@Column
        val adv = state.options?.advanced ?: return@Column
        val server = state.server
        fun <T> next(list: List<T>, current: T?): T = list[(list.indexOf(current) + 1).mod(list.size)]
        if (state.servers.size > 1) Option(t("Server"), server?.name.orEmpty(), { actions.server(next(state.servers, server).id) }) {}
        if (server != null && server.profiles.isNotEmpty()) {
            val p = server.profiles.firstOrNull { it.id == state.profileId }
            Option(t("Quality profile"), p?.name.orEmpty(), { actions.profile(next(server.profiles, p).id) }) {}
        }
        if (server != null && server.rootFolders.size > 1) {
            val f = server.rootFolders.firstOrNull { it.path == state.rootFolder }
            Option(t("Folder"), state.rootFolder.orEmpty(), { actions.folder(next(server.rootFolders, f).path) }) {}
        }
        if (adv.users.size > 1) {
            val u = adv.users.firstOrNull { it.id == state.forUser }
            Option(t("Request for"), u?.displayName.orEmpty(), { actions.user(next(adv.users, u).id) }) {}
        }
    }
}

@Composable
private fun Plain(text: String, action: String, onClick: () -> Unit, modifier: Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.bp)) {
        SText(text, size = 19.bsp, color = SeedyColors.Text2)
        FooterButton(action, onClick, modifier)
    }
}

@Composable
private fun Footer(state: RequestSeasonsViewModel.State, onSubmit: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().drawBehind { drawLine(SeedyColors.Line, Offset(0f, 0f), Offset(size.width, 0f), 2.bp.toPx()) }
            .padding(start = 40.bp, end = 40.bp, top = 22.bp, bottom = 28.bp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.bp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.bp)) {
            state.error?.let { SText(it, size = 17.bsp, weight = 600, color = SeedyColors.Danger, maxLines = 1) }
            val approve = state.approve
            if (approve != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.bp)) {
                    val tone = if (approve) SeedyColors.Ok else SeedyColors.Pending
                    Box(Modifier.size(26.bp).background(tone.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                        SIcon(if (approve) WebIcons.Check else AppIcons.ClockHands, size = 16.bp, color = tone, stroke = 3f)
                    }
                    SText(state.note, size = 17.bsp, color = SeedyColors.Text2, maxLines = 2)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.bp)) {
            FooterButton(t("Cancel"), onDismiss)
            FooterButton(
                when {
                    state.busy -> t("Requesting…")
                    state.count > 0 -> tn(state.count, "Request {n} season", "Request {n} seasons")
                    else -> t("Request seasons")
                },
                onSubmit, primary = true, enabled = state.canSend,
            )
        }
    }
}

@Composable
private fun FooterButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true) {
    TvPressable({ if (enabled) onClick() }, modifier) { focused ->
        Row(
            Modifier.focusScale(focused, 1.04f).focusRing(focused, radius = 18.bp, gap = 4.bp, ring = 4.bp, gapColor = Color(0xFF141414)).height(64.bp)
                .clip(RoundedCornerShape(18.bp))
                .background(
                    when {
                        primary && enabled -> SeedyColors.Inverse
                        primary -> SeedyColors.Surface2
                        focused -> SeedyColors.Inverse
                        else -> Color.White.copy(alpha = 0.1f)
                    },
                )
                .padding(horizontal = if (primary) 32.bp else 28.bp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.bp),
        ) {
            val ink = when {
                primary && enabled -> SeedyColors.InkInverse
                primary -> SeedyColors.Text3
                focused -> SeedyColors.InkInverse
                else -> SeedyColors.White
            }
            if (primary) SIcon(WebIcons.Request, size = 24.bp, color = ink)
            SText(label, size = 20.bsp, weight = if (primary) 800 else 700, color = ink, maxLines = 1)
        }
    }
}
