package io.github.arkmind.seedy.mobile.browse

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.theme.SeedyColors

/**
 * The phone boards' bottom sheet over a dimmed page (Request seasons' frame): 26 px top corners, the grabber, as tall
 * as its content up to 150 px from the top. Back or a tap on the dimmed page closes it.
 */
@Composable
fun BottomSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) = io.github.arkmind.seedy.mobile.parts.AboveTabBar {
    BackHandler(onBack = onDismiss)
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color(0xA8050505)).tap(onClick = onDismiss))
        AnimatedVisibility(shown, Modifier.align(Alignment.BottomCenter), enter = slideInVertically { it / 3 } + fadeIn()) {
            val shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
            Column(
                Modifier.fillMaxWidth().heightIn(max = maxHeight - 150.dp).shadow(40.dp, shape).clip(shape).background(SeedyColors.Panel).tap {},
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(SeedyColors.Stroke))
                content()
            }
        }
    }
}
