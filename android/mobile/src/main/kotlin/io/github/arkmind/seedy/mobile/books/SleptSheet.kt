package io.github.arkmind.seedy.mobile.books

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.parts.blockTaps
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.player.audio.Slept
import io.github.arkmind.seedy.ui.components.Medu
import io.github.arkmind.seedy.ui.components.MeduMood
import io.github.arkmind.seedy.ui.components.Overlay
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/**
 * Board "Android · Sleep timer": the timer paused the book and Medu fell asleep too. Resume where it stopped (the
 * timer as it was), give it 15 more minutes, or turn the timer off. Back or a tap on the page closes it.
 */
@Composable
fun SleptSheet(title: String, slept: Slept, onResume: () -> Unit, onMore: () -> Unit, onOff: () -> Unit, onDismiss: () -> Unit) = Overlay {
    BackHandler(onBack = onDismiss)
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    val bottom = max(30.dp, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp)
    val at = audioClock(slept.position)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .64f)).tap(onClick = onDismiss))
        AnimatedVisibility(shown, Modifier.align(Alignment.BottomCenter), enter = slideInVertically { it / 3 } + fadeIn()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(SeedyColors.Row).blockTaps()
                    .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = bottom),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.padding(bottom = 8.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF3A3A3A)))
                Medu(MeduMood.Sleepy, 130.dp, height = 172.dp)
                SText(
                    t("Medu fell asleep too"), Modifier.padding(top = 6.dp),
                    size = 22.sp, weight = 900, lineHeight = 1.08f, tracking = -.015f, color = SeedyColors.White, align = TextAlign.Center,
                )
                val chapter = slept.chapter
                SText(
                    if (chapter != null) {
                        t("The sleep timer paused {title} at {time}, at the end of chapter {n}.", "title" to title, "time" to at, "n" to chapter + 1)
                    } else {
                        t("The sleep timer paused {title} at {time}.", "title" to title, "time" to at)
                    },
                    Modifier.padding(top = 6.dp).widthIn(max = 300.dp),
                    size = 14.sp, lineHeight = 1.55f, color = Color(0xFFB4B4B4), align = TextAlign.Center,
                )
                Column(Modifier.padding(top = 18.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(15.dp)).background(SeedyColors.Inverse).tap(onClick = onResume),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    ) {
                        SIcon(AppIcons.Resume, size = 18.dp, color = SeedyColors.InkInverse)
                        SText(t("Resume from {time}", "time" to at), size = 15.sp, weight = 800, color = SeedyColors.InkInverse)
                    }
                    Box(
                        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(15.dp)).background(SeedyColors.Surface).tap(onClick = onMore),
                        contentAlignment = Alignment.Center,
                    ) {
                        SText(t("Add 15 minutes"), size = 15.sp, weight = 800, color = SeedyColors.White)
                    }
                }
                SText(t("Turn off the sleep timer"), Modifier.padding(top = 14.dp).tap(onClick = onOff), size = 14.sp, weight = 700, color = SeedyColors.Text2)
            }
        }
    }
}
