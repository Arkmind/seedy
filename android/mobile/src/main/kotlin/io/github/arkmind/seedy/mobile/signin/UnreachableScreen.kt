package io.github.arkmind.seedy.mobile.signin

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import io.github.arkmind.seedy.ui.components.rememberStillMotion
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import kotlinx.coroutines.delay

/** "192.168.1.20:8096": a server's address as the unreachable pages print it. */

/**
 * Board "Android · Server unreachable": Medu dropped the cable. Try again now, or pick another server; Seedy tries by
 * itself every few seconds ([retryIn] seconds left, null while a try runs). Once the server has [answered], Medu
 * picks itself up and waves (Oops → Hello), the page says it's back online, and [onWelcomed] lets the app open a
 * moment after Medu settles (at once with motion off).
 */
@Composable
fun UnreachableScreen(
    server: SavedServer,
    retryIn: Int?,
    onRetry: () -> Unit,
    onChooseServer: () -> Unit,
    answered: Boolean = false,
    onWelcomed: () -> Unit = {},
) {
    val still = rememberStillMotion()
    val welcome by rememberUpdatedState(onWelcomed)
    var waved by remember { mutableStateOf(false) }
    LaunchedEffect(waved) {
        if (!waved) return@LaunchedEffect
        if (!still) delay(900)
        welcome()
    }
    // Nothing left to try once the server answers: the buttons and the countdown fade, keeping their room.
    val actions by animateFloatAsState(if (answered) 0f else 1f, label = "unreachable-actions")
    val top = max(34.dp, WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 10.dp)
    val bottom = max(28.dp, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp)
    Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
        DeepSea(DeepScenes.PhoneUnreachable)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = top, bottom = bottom)) {
            BrandMark(server.seedy?.name ?: "Seedy", Modifier.padding(start = 20.dp), markSize = 28.dp, radius = 7.dp, letterSize = 15.sp, wordSize = 18.sp)
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 120.dp - 34.dp - 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Medu(if (answered) MeduMood.Wave else MeduMood.Oops, 190.dp, height = 251.dp, onSettled = { waved = it == MeduMood.Wave })
                SText(
                    if (answered) t("Back online") else t("Can’t reach your server"), Modifier.padding(top = 8.dp),
                    size = 26.sp, weight = 900, lineHeight = 1.08f, tracking = -.015f, color = SeedyColors.White, align = TextAlign.Center,
                )
                SText(
                    if (answered) t("{server} is answering again.", "server" to server.name)
                    else t("Medu dropped the cable. Check that {server} is on and connected, then try again.", "server" to server.name),
                    Modifier.padding(top = 8.dp).widthIn(max = 300.dp),
                    size = 15.sp, lineHeight = 1.53f, color = DeepText, align = TextAlign.Center,
                )
                Column(Modifier.padding(top = 22.dp).width(300.dp).alpha(actions), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(15.dp)).background(SeedyColors.Inverse).tap(enabled = !answered, onClick = onRetry),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    ) {
                        SIcon(WebIcons.Refetch, size = 18.dp, color = SeedyColors.InkInverse, stroke = 2.2f)
                        SText(t("Try again"), size = 15.sp, weight = 800, color = SeedyColors.InkInverse)
                    }
                    Box(
                        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(15.dp)).background(SeedyColors.Surface).tap(enabled = !answered, onClick = onChooseServer),
                        contentAlignment = Alignment.Center,
                    ) {
                        SText(t("Choose another server"), size = 15.sp, weight = 800, color = SeedyColors.White)
                    }
                }
                Row(Modifier.padding(top = 18.dp).alpha(actions), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
