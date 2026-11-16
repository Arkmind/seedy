package io.github.arkmind.seedy.tv.remote

import android.app.Activity
import androidx.activity.ComponentActivity
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.ui.focus.FocusDirection
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.arkmind.seedy.core.remote.ScreenCommand
import io.github.arkmind.seedy.core.remote.ScreenReceiver
import io.github.arkmind.seedy.core.remote.ScreenReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The TV as a screen phones control (the phone boards "Remote for a TV" and "Remote · typing on the TV"): what the
 * phone typed waits in [text] for Search, the open player takes playback commands through [player], and Search says
 * it's asking for text with [report].
 */
class TvScreen(val receiver: ScreenReceiver) {
    /** Text typed on the phone, until Search takes it. */
    val text = MutableStateFlow<String?>(null)

    /** The open player's handler for play, pause, seek and tracks. */
    var player: ((ScreenCommand) -> Unit)? = null

    suspend fun report(r: ScreenReport) = receiver.report(r)
}

val LocalTvScreen = staticCompositionLocalOf<TvScreen?> { null }

/**
 * Presses a key on the TV as its own remote would: the D-pad, OK, Back and Menu reach the focused screen the usual way.
 * A key sent from here skips the window's own handling, so what the screen leaves alone gets it here: an arrow moves
 * the focus with [moveFocus] (between the rail, a keyboard and its results…), Back goes to the back handlers.
 */
fun press(activity: ComponentActivity, code: Int, moveFocus: (FocusDirection) -> Boolean) {
    val at = SystemClock.uptimeMillis()
    fun event(action: Int) = KeyEvent(at, SystemClock.uptimeMillis(), action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_DPAD)
    val window = activity.window
    val down = window.superDispatchKeyEvent(event(KeyEvent.ACTION_DOWN))
    val up = window.superDispatchKeyEvent(event(KeyEvent.ACTION_UP))
    if (down || up) return
    when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> moveFocus(FocusDirection.Up)
        KeyEvent.KEYCODE_DPAD_DOWN -> moveFocus(FocusDirection.Down)
        KeyEvent.KEYCODE_DPAD_LEFT -> moveFocus(FocusDirection.Left)
        KeyEvent.KEYCODE_DPAD_RIGHT -> moveFocus(FocusDirection.Right)
        KeyEvent.KEYCODE_BACK -> activity.onBackPressedDispatcher.onBackPressed()
    }
}

/** Shows a message a phone (or the admin's Playing now) sent. */
fun showMessage(activity: Activity, text: String) {
    Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
}

/** Search tells the phones it's asking for text, with what's typed and the title focused; it stops asking when it closes. */
@Composable
fun AskingForText(text: String, focused: String?) {
    val screen = LocalTvScreen.current ?: return
    LaunchedEffect(text, focused) { screen.report(ScreenReport(asking = "search", text = text, itemId = focused)) }
    val latest = rememberUpdatedState(screen)
    DisposableEffect(Unit) {
        onDispose {
            val s = latest.value
            kotlinx.coroutines.MainScope().launch { s.report(ScreenReport()) }
        }
    }
}
