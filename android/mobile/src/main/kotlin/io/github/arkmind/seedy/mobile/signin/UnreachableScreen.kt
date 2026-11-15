package io.github.arkmind.seedy.mobile.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.data.SavedServer
import io.github.arkmind.seedy.core.data.host
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.components.BrandMark
import io.github.arkmind.seedy.ui.components.DeepScenes
import io.github.arkmind.seedy.ui.components.DeepSea
import io.github.arkmind.seedy.ui.components.Medu
import io.github.arkmind.seedy.ui.components.MeduMood
import io.github.arkmind.seedy.ui.components.Spinner
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/** "192.168.1.20:8096": a server's address as the unreachable pages print it. */

/**
 * Board "Android · Server unreachable": Medu dropped the cable. Try again now, or pick another server; Seedy tries by
 * itself every few seconds ([retryIn] seconds left, null while a try runs).
 */
@Composable
fun UnreachableScreen(server: SavedServer, retryIn: Int?, onRetry: () -> Unit, onChooseServer: () -> Unit) {
    val top = max(34.dp, WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 10.dp)
    val bottom = max(28.dp, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp)
    Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
        DeepSea(DeepScenes.PhoneUnreachable)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = top, bottom = bottom)) {
            BrandMark(server.seedy?.name ?: "Seedy", Modifier.padding(start = 20.dp), markSize = 28.dp, radius = 7.dp, letterSize = 15.sp, wordSize = 18.sp)
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 120.dp - 34.dp - 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Medu(MeduMood.Oops, 190.dp, height = 251.dp)
                SText(
                    t("Can’t reach your server"), Modifier.padding(top = 8.dp),
                    size = 26.sp, weight = 900, lineHeight = 1.08f, tracking = -.015f, color = SeedyColors.White, align = TextAlign.Center,
                )
                SText(
                    t("Medu dropped the cable. Check that {server} is on and connected, then try again.", "server" to server.name),
                    Modifier.padding(top = 8.dp).widthIn(max = 300.dp),
                    size = 15.sp, lineHeight = 1.53f, color = DeepText, align = TextAlign.Center,
                )
                Column(Modifier.padding(top = 22.dp).width(300.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(15.dp)).background(SeedyColors.Inverse).tap(onClick = onRetry),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    ) {
                        SIcon(WebIcons.Refetch, size = 18.dp, color = SeedyColors.InkInverse, stroke = 2.2f)
                        SText(t("Try again"), size = 15.sp, weight = 800, color = SeedyColors.InkInverse)
                    }
                    Box(
                        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(15.dp)).background(SeedyColors.Surface).tap(onClick = onChooseServer),
                        contentAlignment = Alignment.Center,
                    ) {
                        SText(t("Choose another server"), size = 15.sp, weight = 800, color = SeedyColors.White)
                    }
                }
                Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spinner(13.dp, SeedyColors.Text)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText(server.host, style = BaseText.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = SeedyColors.Text3), maxLines = 1)
                        SText(
                            " · " + if (retryIn != null) t("trying again in {n} s", "n" to retryIn) else t("trying again…"),
                            size = 12.sp, color = SeedyColors.Text3, maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
