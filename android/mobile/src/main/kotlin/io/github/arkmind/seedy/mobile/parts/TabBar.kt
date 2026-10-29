package io.github.arkmind.seedy.mobile.parts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.audio.miniBarSpace
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/** The phone's four places. Requests only when the server's Seedy is connected to Seerr. */
enum class Tab(val label: String, val icon: IconDef) {
    Home("Home", AppIcons.Home),
    Libraries("Libraries", AppIcons.Libraries),
    Favorites("Favorites", WebIcons.Heart),
    Requests("Requests", AppIcons.Requests),
}

/** How much of the bottom the tab bar (and the mini player over it, while one shows) covers, for lists that scroll under it. */
@Composable
fun tabBarHeight(): Dp = barHeight() + miniBarSpace()

/** The tab bar alone: the mini player floats 8 px above it. */
@Composable
fun barHeight(): Dp = 10.dp + 32.dp + 5.dp + 17.dp + bottomInset()

@Composable
private fun bottomInset(): Dp = max(26.dp, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 8.dp)

/** Board "Android · Home" bottom bar: a light pill behind the current place's icon. */
@Composable
fun TabBar(tabs: List<Tab>, current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(SeedyColors.Panel)
            .drawBehind { drawLine(SeedyColors.Line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = bottomInset()),
    ) {
        for (tab in tabs) {
            val on = tab == current
            Column(
                Modifier.weight(1f).tap(role = Role.Tab) { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    Modifier.size(60.dp, 32.dp).clip(RoundedCornerShape(16.dp)).then(if (on) Modifier.background(SeedyColors.Inverse) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    SIcon(tab.icon, size = 22.dp, color = if (on) SeedyColors.InkInverse else SeedyColors.Text2, stroke = 2.2f)
                }
                SText(t(tab.label), Modifier.height(17.dp), size = 12.sp, weight = if (on) 800 else 600, color = if (on) SeedyColors.White else SeedyColors.Text2)
            }
        }
    }
}

