package io.github.arkmind.seedy.mobile.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.remote.ClosedScreen
import io.github.arkmind.seedy.core.remote.RemoteControl
import io.github.arkmind.seedy.core.remote.RemoteScreen
import io.github.arkmind.seedy.core.remote.pickerLine
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.components.Medu
import io.github.arkmind.seedy.ui.components.MeduMood
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import androidx.compose.foundation.border
import io.github.arkmind.seedy.ui.theme.currentSession

/** "Control a screen" for the signed-in profile: what it picks becomes the remote's tabs, then [onControl] opens the remote. */
@Composable
fun ControlScreenPicker(onControl: () -> Unit, onDismiss: () -> Unit) {
    val session = currentSession()
    val remote = remember(session) { RemoteControl.of(session) }
    DisposableEffect(remote) {
        val stop = remote.watch(2_000)
        onDispose { stop() }
    }
    val st by remote.state.collectAsStateWithLifecycle()
    // Ticked: the screens already controlled, else the only one open.
    var picked by remember { mutableStateOf<Set<String>?>(null) }
    var sync by remember { mutableStateOf(st.sync) }
    val screens = st.screens
    val ticked = picked ?: when {
        screens == null -> emptySet()
        st.controlled.isNotEmpty() -> st.controlled.toSet()
        screens.size == 1 -> setOf(screens[0].deviceId)
        else -> emptySet()
    }
    PickerSheet(
        screens = screens,
        closed = st.closed,
        picked = ticked,
        sync = sync,
        onToggle = { id -> picked = if (id in ticked) ticked - id else ticked + id },
        onSync = { sync = it },
        onControl = {
            val order = screens.orEmpty().map { it.deviceId }.filter { it in ticked }
            remote.control(order, sync)
            onControl()
        },
        onDismiss = onDismiss,
    )
}

/**
 * Board "Android · Control a screen": the screens where Seedy is open (tick one or a few), those signed in without it
 * (greyed: open Seedy there first), "Play in sync" when there are two to tick, and Control.
 */
@Composable
fun PickerSheet(
    screens: List<RemoteScreen>?,
    closed: List<ClosedScreen>,
    picked: Set<String>,
    sync: Boolean,
    onToggle: (String) -> Unit,
    onSync: (Boolean) -> Unit,
    onControl: () -> Unit,
    onDismiss: () -> Unit,
    now: Long = android.os.SystemClock.elapsedRealtime(),
    still: Boolean = false,
) = RemoteSheet(onDismiss) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SText(t("Control a screen"), size = 22.sp, weight = 900, color = SeedyColors.White)
            SText(t("Use this phone as the remote for Seedy on your TVs and computers. Pick one, or a few."), size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Text2)
        }
        Box(Modifier.size(36.dp).clip(CircleShape).background(SeedyColors.Surface).tap(onClick = onDismiss), contentAlignment = Alignment.Center) {
            SIcon(RemoteIcons.Close, size = 18.dp, color = SeedyColors.White)
        }
    }
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            screens == null -> SText(t("Looking for your screens…"), Modifier.padding(horizontal = 6.dp, vertical = 12.dp), size = 13.sp, color = SeedyColors.Text3)
            screens.isEmpty() -> NoScreens()
            else -> {
                Label(t("Seedy is open on"), top = 4)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (s in screens) OpenRow(s, s.deviceId in picked, now, still) { onToggle(s.deviceId) }
                }
            }
        }
        if (closed.isNotEmpty()) {
            Label(t("Signed in, not open"), top = 8)
            for (c in closed) ClosedRow(c)
        }
    }
    if ((screens?.size ?: 0) > 1) {
        Row(
            Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SeedyColors.Row).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SIcon(RemoteIcons.Sync, size = 20.dp, color = SeedyColors.TextHero)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SText(t("Play in sync"), size = 14.sp, weight = 800, color = SeedyColors.White)
                SText(t("Pause, play and skip on both screens at once"), size = 12.sp, lineHeight = 1.35f, color = SeedyColors.Text3)
            }
            RemoteSwitch(sync && picked.size > 1, enabled = picked.size > 1, onChange = onSync)
        }
    }
    val n = picked.size
    val one = screens?.firstOrNull { it.deviceId in picked }
    val label = when {
        n == 0 -> t("Control a screen")
        n == 1 && one != null -> t("Control {name}", "name" to one.name)
        else -> t("Control {n} screens", "n" to n)
    }
    Row(
        Modifier.padding(top = 6.dp).fillMaxWidth().height(54.dp).alpha(if (n == 0) 0.5f else 1f).clip(RoundedCornerShape(16.dp)).background(SeedyColors.Inverse)
            .tap(enabled = n > 0, onClick = onControl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        SIcon(RemoteIcons.Remote, size = 20.dp, color = SeedyColors.InkInverse)
        SText(label, size = 16.sp, weight = 800, color = SeedyColors.InkInverse, maxLines = 1)
    }
}

@Composable
private fun Label(text: String, top: Int) {
    SText(text, Modifier.padding(start = 6.dp, end = 6.dp, top = top.dp), size = 11.sp, weight = 800, tracking = 0.1f, color = SeedyColors.Text3, uppercase = true)
}

@Composable
private fun OpenRow(s: RemoteScreen, on: Boolean, now: Long, still: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp)).background(SeedyColors.Surface)
            .then(if (on) Modifier.border(2.dp, accent, RoundedCornerShape(16.dp)) else Modifier)
            .tap(role = Role.Checkbox, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(RemoteColors.Raised), contentAlignment = Alignment.Center) {
            SIcon(kindIcon(s.kind), size = 21.dp, color = if (on) accent else SeedyColors.TextHero)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SText(s.name, Modifier.weight(1f, fill = false), size = 15.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
                s.now?.let { StatePill(!it.paused, still) }
            }
            SText(pickerLine(s), size = 12.sp, lineHeight = 1.35f, color = SeedyColors.Text3, maxLines = 1)
            s.now?.takeIf { it.durationMs > 0 }?.let { ProgressLine(it.fractionAt(now), Modifier.padding(top = 3.dp)) }
        }
        TickBox(on)
    }
}

@Composable
private fun ClosedRow(c: ClosedScreen) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).alpha(0.55f).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(RemoteColors.Raised), contentAlignment = Alignment.Center) {
            SIcon(kindIcon(c.kind), size = 21.dp, color = SeedyColors.TextHero)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            SText(c.name, size = 15.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
            SText(
                listOf(c.app, t("Open Seedy on it to control it")).filter { it.isNotEmpty() }.joinToString(" · "),
                size = 12.sp, lineHeight = 1.35f, color = SeedyColors.Text3, maxLines = 1,
            )
        }
    }
}

/** Nothing to control yet: Medu waits, and says where screens come from. */
@Composable
private fun NoScreens() {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SeedyColors.Surface).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Medu(MeduMood.Idle, 44.dp, height = 58.dp, small = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(t("No screens to control yet"), size = 14.sp, weight = 800, color = SeedyColors.White)
            SText(t("Open Seedy on your TV or computer, signed in as you, and it shows up here."), size = 12.sp, lineHeight = 1.35f, color = SeedyColors.Text3)
        }
    }
}
