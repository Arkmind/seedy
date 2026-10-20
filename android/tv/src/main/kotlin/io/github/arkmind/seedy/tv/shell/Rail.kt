package io.github.arkmind.seedy.tv.shell

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.tv.parts.TvPressable
import io.github.arkmind.seedy.tv.parts.bp
import io.github.arkmind.seedy.tv.parts.bsp
import io.github.arkmind.seedy.tv.parts.focusRing
import io.github.arkmind.seedy.ui.components.ProfileSquare
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/** The TV's places, in the rail's order. Requests only when the server's Seedy is connected to Seerr. */
enum class TvPlace(val label: String, val icon: IconDef) {
    Search("Search", AppIcons.RailSearch),
    Home("Home", AppIcons.Home),
    Movies("Movies", AppIcons.Movies),
    Shows("Shows", AppIcons.Shows),
    LiveTv("Live TV", AppIcons.LiveTv),
    Favorites("Favorites", WebIcons.Heart),
    Requests("Requests", AppIcons.Requests),
}

/** The rail's sliders icon (Settings). */
private val SettingsIcon = IconDef(
    2f, "M4 7h9", "M17 7h3", "M4 17h3", "M11 17h9",
    "M13 7a2 2 0 1 0 4 0a2 2 0 1 0 -4 0Z", "M7 17a2 2 0 1 0 4 0a2 2 0 1 0 -4 0Z",
)

/** How far the page starts from the left edge, past the collapsed rail (boards: 176 px). */
val RailInset = 176.bp

/**
 * Boards "Android TV · Home" and "· Menu open": a 112 px rail of icons that opens into a 380 px labelled menu over a
 * dimmed page while the remote is on it. Moving right leaves it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvRail(
    places: List<TvPlace>,
    current: TvPlace,
    onPlace: (TvPlace) -> Unit,
    onProfile: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val currentRequester = remember { FocusRequester() }
    val width by animateDpAsState(if (open) 380.bp else 112.bp, label = "rail")
    Box(modifier.fillMaxSize()) {
        if (open) Box(Modifier.fillMaxSize().background(Color(0x9E050505)))
        Column(
            Modifier.fillMaxHeight().width(width)
                .then(if (open) Modifier.shadow(30.bp).background(Color(0xFF121212)) else Modifier)
                .onFocusChanged { open = it.hasFocus }
                .focusProperties { onEnter = { currentRequester.requestFocus() } }
                .focusGroup()
                .padding(vertical = 54.bp, horizontal = if (open) 28.bp else 0.bp),
            horizontalAlignment = if (open) Alignment.Start else Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.bp),
        ) {
            ProfileButton(open, onProfile)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (open) 8.bp else 10.bp, Alignment.CenterVertically)) {
                for (place in places) {
                    RailItem(
                        icon = place.icon, label = t(place.label), open = open, current = place == current,
                        modifier = if (place == current) Modifier.focusRequester(currentRequester) else Modifier,
                        onClick = { onPlace(place) },
                    )
                }
            }
            RailItem(SettingsIcon, t("Settings"), open, current = false, onClick = onSettings, quiet = true)
        }
    }
}

@Composable
private fun ProfileButton(open: Boolean, onClick: () -> Unit) {
    val viewer = LocalViewer.current
    TvPressable(onClick) { focused ->
        Row(
            Modifier.focusRing(focused, radius = 18.bp, gap = 4.bp, ring = 4.bp, gapColor = Color(0xFF121212))
                .clip(RoundedCornerShape(18.bp)).padding(horizontal = if (open) 12.bp else 0.bp, vertical = if (open) 8.bp else 0.bp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.bp),
        ) {
            ProfileSquare(viewer.name, viewer.imageUrl, 0, size = 52.bp, radius = 26.bp, letterSize = 22.bsp)
            if (open) {
                Column(verticalArrangement = Arrangement.spacedBy(2.bp)) {
                    SText(viewer.name, size = 20.bsp, weight = 800, color = SeedyColors.White, maxLines = 1)
                    SText(t("Switch profile"), size = 15.bsp, color = SeedyColors.Text3, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun RailItem(
    icon: IconDef,
    label: String,
    open: Boolean,
    current: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    quiet: Boolean = false,
) {
    TvPressable(onClick, modifier) { focused ->
        val ink = when {
            focused && open -> SeedyColors.InkInverse
            current -> SeedyColors.White
            open && !quiet -> SeedyColors.TextHero
            open -> SeedyColors.TextHero
            else -> SeedyColors.Text3
        }
        val bg = when {
            focused && open -> SeedyColors.Inverse
            focused -> Color.White.copy(alpha = 0.24f)
            current && !open -> Color.White.copy(alpha = 0.12f)
            else -> Color.Transparent
        }
        if (open) {
            Row(
                Modifier.fillMaxWidth().height(64.bp)
                    .focusRing(focused, radius = 18.bp, gap = 4.bp, ring = 4.bp, gapColor = Color(0xFF121212))
                    .clip(RoundedCornerShape(18.bp)).background(bg).padding(horizontal = 20.bp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.bp),
            ) {
                SIcon(icon, size = 28.bp, color = ink)
                SText(label, Modifier.weight(1f), size = 21.bsp, weight = if (focused || current) 800 else 600, color = ink, maxLines = 1)
                if (current) Box(Modifier.size(8.bp).background(LocalAccent.current, CircleShape))
            }
        } else {
            Box(Modifier.size(60.bp).clip(RoundedCornerShape(18.bp)).background(bg), contentAlignment = Alignment.Center) {
                SIcon(icon, size = 28.bp, color = ink)
            }
        }
    }
}
