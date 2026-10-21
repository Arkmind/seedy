package io.github.arkmind.seedy.mobile.seasons

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.feature.RequestSeasonsViewModel
import io.github.arkmind.seedy.core.feature.SeasonsResult
import io.github.arkmind.seedy.core.feature.SeasonsTarget
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.seedy.SeerrDetails
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.components.SeedySwitch
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession

/**
 * Board "Android · Request seasons": the sheet that asks Seerr for a series' seasons. Any page can open it (a series'
 * details, Search, a Seerr title): show it while it should be open; [onDone] gets what was requested, then it closes.
 * [details] is Seerr's answer when the page already has it; [initial] the seasons to start ticked.
 */
@Composable
fun RequestSeasonsSheet(
    target: SeasonsTarget,
    onDismiss: () -> Unit,
    onDone: (SeasonsResult) -> Unit = {},
    details: SeerrDetails? = null,
    initial: List<Int>? = null,
) {
    val session = currentSession()
    val vm = viewModel(key = "request-seasons") { RequestSeasonsViewModel(session) }
    LaunchedEffect(target, initial) { vm.open(target, details, initial) }
    val state by vm.state.collectAsStateWithLifecycle()
    if (state.target != target) return
    RequestSeasonsContent(
        state = state,
        onToggle = vm::toggle,
        onToggleAll = vm::toggleAll,
        on4k = vm::set4k,
        advanced = AdvancedActions(vm::setServer, vm::setProfile, vm::setFolder, vm::setUser),
        onRetry = vm::retry,
        onSubmit = { vm.submit { r -> onDone(r); onDismiss() } },
        onDismiss = onDismiss,
    )
}

/** The Advanced section's choices. */
class AdvancedActions(val server: (Int) -> Unit, val profile: (Int) -> Unit, val folder: (String) -> Unit, val user: (Int) -> Unit)

/** The sheet itself, over a dimmed page (stateless, for previews and screenshot tests). */
@Composable
fun RequestSeasonsContent(
    state: RequestSeasonsViewModel.State,
    onToggle: (Int) -> Unit,
    onToggleAll: () -> Unit,
    on4k: (Boolean) -> Unit,
    advanced: AdvancedActions,
    onRetry: () -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) = io.github.arkmind.seedy.mobile.parts.AboveTabBar {
    BackHandler(onBack = onDismiss)
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color(0xA8050505)).tap(onClick = onDismiss))
        AnimatedVisibility(shown, enter = slideInVertically { it / 3 } + fadeIn()) {
            Column(
                Modifier.fillMaxSize().padding(top = 150.dp)
                    .shadow(40.dp, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp), ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).background(SeedyColors.Panel)
                    .tap {},
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(SeedyColors.Stroke))
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Head(state)
                    state.options?.quota?.let { Quota(state) }
                    SeasonsHead(state, onToggleAll)
                    when {
                        state.failed && state.details == null -> {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SText(t("Couldn't load the seasons from Seerr."), size = 13.sp, color = SeedyColors.Text2)
                                SText(t("Try again"), Modifier.clip(RoundedCornerShape(12.dp)).background(SeedyColors.Surface).tap(onClick = onRetry).padding(horizontal = 14.dp, vertical = 10.dp), size = 13.sp, weight = 700, color = SeedyColors.White)
                            }
                        }
                        state.details == null -> repeat(3) { Box(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(13.dp)).background(SeedyColors.Row)) }
                        state.rows.isEmpty() -> SText(t("Seerr doesn’t list any seasons for this series yet."), size = 13.sp, color = SeedyColors.Text2)
                        else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (row in state.rows) SeasonRow(row, on = !row.locked && row.n in state.picked) { onToggle(row.n) }
                        }
                    }
                    if (state.can4k) Option(t("Request in 4K"), t("Sent to the 4K server separately")) { SeedySwitch(state.is4k, on4k) }
                    if (state.hasAdvanced) Advanced(state, advanced)
                }
                Footer(state, onSubmit)
            }
        }
    }
}

@Composable
private fun Head(state: RequestSeasonsViewModel.State) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Artwork(state.poster, Modifier.size(56.dp, 84.dp).clip(RoundedCornerShape(9.dp)), placeholder = Color(0xFF2F2840))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SText(
                if (state.target?.fromLibrary == true) t("Request missing seasons") else t("Request from Seerr"),
                size = 11.sp, weight = 800, tracking = 0.1f, color = SeedyColors.Text2, uppercase = true, maxLines = 1,
            )
            SText(state.title, size = 17.sp, weight = 800, lineHeight = 1.2f, color = SeedyColors.White, maxLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SText(
                    t("SERIES"), Modifier.clip(CircleShape).background(SeedyColors.TypeSeries).padding(horizontal = 8.dp, vertical = 2.dp),
                    size = 10.sp, weight = 800, tracking = 0.1f, color = SeedyColors.White,
                )
                SText(state.meta, size = 12.sp, color = SeedyColors.Text2, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Quota(state: RequestSeasonsViewModel.State) {
    val quota = state.options?.quota ?: return
    val accent = LocalAccent.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(SeedyColors.Row).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SText(quotaName(quota.days), Modifier.weight(1f), size = 12.sp, weight = 700, color = SeedyColors.Text, maxLines = 1)
            SText(
                if (state.over > 0) tn(state.over, "{left} of {limit} left · {n} season too many", "{left} of {limit} left · {n} seasons too many", "left" to quota.remaining, "limit" to quota.limit)
                else t("{left} of {limit} left", "left" to quota.remaining, "limit" to quota.limit),
                size = 12.sp, color = if (state.over > 0) SeedyColors.Danger else SeedyColors.Text2, maxLines = 1,
            )
        }
        val used = if (quota.limit > 0) quota.used.toFloat() / quota.limit else 0f
        val asked = if (quota.limit > 0) minOf(state.count, quota.limit - quota.used).toFloat() / quota.limit else 0f
        Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(SeedyColors.Surface2)) {
            if (used > 0f) Box(Modifier.fillMaxHeight().weight(used).background(Color(0xFF6A6A6A)))
            if (asked > 0f) Box(Modifier.fillMaxHeight().weight(asked).background(if (state.over > 0) SeedyColors.Danger else accent))
            val rest = 1f - used - asked
            if (rest > 0f) Spacer(Modifier.weight(rest))
        }
    }
}

/** "Season requests this week", "… today", "… per 3 days". */
fun quotaName(days: Int): String = when (days) {
    7 -> t("Season requests this week")
    1 -> t("Season requests today")
    else -> tn(days, "Season requests, per {n} day", "Season requests, per {n} days")
}

@Composable
private fun SeasonsHead(state: RequestSeasonsViewModel.State, onToggleAll: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SText(t("Seasons"), Modifier.weight(1f), size = 15.sp, weight = 800, color = SeedyColors.White)
        if (state.open.size > 1) {
            Row(
                Modifier.height(44.dp).tap(onClick = onToggleAll).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SText(t("Select all"), size = 13.sp, weight = 700, color = SeedyColors.Text)
                CheckBox(on = state.all || state.some, size = 22, check = 15, mixed = state.some)
            }
        }
    }
}

@Composable
private fun CheckBox(on: Boolean, size: Int, check: Int, locked: Boolean = false, mixed: Boolean = false) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(7.dp)).background(if (on) LocalAccent.current else SeedyColors.Surface2),
        contentAlignment = Alignment.Center,
    ) {
        if (on || locked) SIcon(if (mixed) WebIcons.Minus else WebIcons.Check, size = check.dp, color = if (on) SeedyColors.InkInverse else SeedyColors.Text3, stroke = 3.2f)
    }
}

@Composable
private fun SeasonRow(row: RequestSeasonsViewModel.Row, on: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(13.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(shape).background(if (on) Color(0xFF1C1C1C) else Color.Transparent)
            .border(1.dp, if (on) accent else Color(0xFF222222), shape)
            .then(if (row.locked) Modifier else Modifier.tap(onClick = onClick)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CheckBox(on = on, size = 24, check = 16, locked = row.locked)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(row.name, size = 14.sp, weight = 700, color = if (row.locked) SeedyColors.Text2 else SeedyColors.White, maxLines = 1)
            if (row.sub.isNotEmpty()) SText(row.sub, size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
        }
        StatusPill(row)
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
        Modifier.clip(CircleShape).background(bg).padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).background(dot, CircleShape))
        SText(row.status, size = 11.sp, weight = 700, color = ink, maxLines = 1)
    }
}

@Composable
private fun Option(title: String, sub: String, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(SeedyColors.Row)
            .then(if (onClick != null) Modifier.tap(onClick = onClick) else Modifier)
            .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(title, size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
            if (sub.isNotEmpty()) SText(sub, size = 12.sp, color = SeedyColors.Text3, maxLines = 2)
        }
        trailing()
    }
}

/** Advanced: which server, quality profile and folder, and who the request is for (as Seerr allows the person). */
@Composable
private fun Advanced(state: RequestSeasonsViewModel.State, actions: AdvancedActions) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Option(t("Advanced"), state.advancedSummary, onClick = { open = !open }) {
            SIcon(if (open) WebIcons.ChevronDown else WebIcons.ChevronRight, size = 18.dp, color = SeedyColors.Text2, stroke = 2.4f)
        }
        if (!open) return@Column
        val server = state.server
        val adv = state.options?.advanced ?: return@Column
        if (state.servers.size > 1) Choice(t("Server"), server?.name.orEmpty(), state.servers.map { it.name to { actions.server(it.id) } })
        if (server != null && server.profiles.isNotEmpty()) {
            Choice(t("Quality profile"), server.profiles.firstOrNull { it.id == state.profileId }?.name.orEmpty(), server.profiles.map { it.name to { actions.profile(it.id) } })
        }
        if (server != null && server.rootFolders.isNotEmpty()) {
            Choice(t("Folder"), state.rootFolder.orEmpty(), server.rootFolders.map { it.path to { actions.folder(it.path) } })
        }
        if (adv.users.size > 1) {
            Choice(
                t("Request for"), adv.users.firstOrNull { it.id == state.forUser }?.displayName.orEmpty(),
                adv.users.map { (if (it.id == adv.me) t("{name} (you)", "name" to it.displayName) else it.displayName) to { actions.user(it.id) } },
            )
        }
    }
}

@Composable
private fun Choice(label: String, value: String, options: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        Option(label, value, onClick = { open = true }) { SIcon(WebIcons.ChevronDown, size = 16.dp, color = SeedyColors.Text2, stroke = 2.4f) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = SeedyColors.Row, shape = RoundedCornerShape(12.dp)) {
            for ((name, pick) in options) {
                DropdownMenuItem(text = { SText(name, size = 14.sp, weight = 700) }, onClick = { open = false; pick() })
            }
        }
    }
}

@Composable
private fun Footer(state: RequestSeasonsViewModel.State, onSubmit: () -> Unit) {
    val bottom = max(26.dp, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp)
    Column(
        Modifier.fillMaxWidth().drawBehind { drawLine(SeedyColors.Line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(start = 16.dp, end = 16.dp, top = 13.dp, bottom = bottom),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        state.error?.let { SText(it, size = 12.sp, weight = 600, color = SeedyColors.Danger) }
        val approve = state.approve
        if (approve != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val tone = if (approve) SeedyColors.Ok else SeedyColors.Pending
                Box(Modifier.size(18.dp).background(tone.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                    SIcon(if (approve) WebIcons.Check else AppIcons.ClockHands, size = 12.dp, color = tone, stroke = 3f)
                }
                SText(state.note, size = 12.sp, color = SeedyColors.Text2, maxLines = 2)
            }
        }
        val enabled = state.canSend
        Row(
            Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp)).background(if (enabled) SeedyColors.Inverse else SeedyColors.Surface2)
                .tap(enabled = enabled, onClick = onSubmit),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
        ) {
            val ink = if (enabled) SeedyColors.InkInverse else SeedyColors.Text3
            SIcon(WebIcons.Request, size = 19.dp, color = ink)
            SText(
                when {
                    state.busy -> t("Requesting…")
                    state.count > 0 -> tn(state.count, "Request {n} season", "Request {n} seasons")
                    else -> t("Request seasons")
                },
                size = 15.sp, weight = 800, color = ink, maxLines = 1,
            )
        }
    }
}
