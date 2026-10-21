package io.github.arkmind.seedy.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Where a page puts its sheets and dialogs so they cover the app's own chrome too (the phone's tab bar, the TV's
 * rail), as on the boards. The shell draws [content] over everything.
 */
class OverlayHost {
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
}

val LocalOverlayHost = staticCompositionLocalOf<OverlayHost?> { null }

/** Shows [content] over the whole screen while this is in the composition; in place when there's no host. */
@Composable
fun Overlay(content: @Composable () -> Unit) {
    val host = LocalOverlayHost.current
    if (host == null) {
        content()
        return
    }
    val latest by rememberUpdatedState(content)
    DisposableEffect(host) {
        val slot: @Composable () -> Unit = { latest() }
        host.content = slot
        onDispose { if (host.content === slot) host.content = null }
    }
}
