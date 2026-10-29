package io.github.arkmind.seedy.mobile.audio

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText

private val Headphones = IconDef(2.2f, "M4 15v-3a8 8 0 0 1 16 0v3", "M4.5 14h1a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1-1.5 1.5h-1a1.5 1.5 0 0 1-1.5-1.5v-3a1.5 1.5 0 0 1 1.5-1.5Z", "M18.5 14h1a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1-1.5 1.5h-1a1.5 1.5 0 0 1-1.5-1.5v-3a1.5 1.5 0 0 1 1.5-1.5Z")

private val Wired = setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET)
private val Bluetooth = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID)

/** Where the sound goes now: a Bluetooth device's name, "Headphones", or "This phone". Follows devices coming and going. */
@Composable
fun rememberAudioOutput(): String {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(AudioManager::class.java) }
    var name by remember { mutableStateOf(outputName(manager)) }
    DisposableEffect(manager) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { name = outputName(manager) }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { name = outputName(manager) }
        }
        manager?.registerAudioDeviceCallback(callback, null)
        onDispose { manager?.unregisterAudioDeviceCallback(callback) }
    }
    return name
}

private fun outputName(manager: AudioManager?): String {
    val outputs = manager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
    outputs.firstOrNull { it.type in Bluetooth }?.let { d -> d.productName?.toString()?.takeIf { it.isNotBlank() }?.let { return it } }
    if (outputs.any { it.type in Wired }) return t("Headphones")
    return t("This phone")
}

/** Opens the system's output picker (Android 14+), else its media output panel, else Bluetooth settings. */
fun openOutputSwitcher(context: Context) {
    if (Build.VERSION.SDK_INT >= 34 && runCatching { MediaRouter2.getInstance(context).showSystemOutputSwitcher() }.getOrDefault(false)) return
    val intents = listOf(
        Intent("com.android.settings.panel.action.MEDIA_OUTPUT").putExtra("com.android.settings.panel.extra.PACKAGE_NAME", context.packageName),
        Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
    )
    for (i in intents) {
        try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
}

/**
 * The output chip of boards "Android · Now playing" and "Android · Audiobook": headphones and the device's name in the
 * accent on a light tint; tapping it opens the system's output picker.
 */
@Composable
fun OutputChip(name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    Row(
        modifier.height(36.dp).clip(RoundedCornerShape(11.dp)).background(Color(0x1AFFFFFF)).tap(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SIcon(Headphones, size = 17.dp, color = accent)
        SText(name, size = 13.sp, weight = 800, color = accent, maxLines = 1)
    }
}

/** The media volume, 0–1, and a setter (the audiobook board's slider); follows the volume keys. */
@Composable
fun rememberMediaVolume(): Pair<Float, (Float) -> Unit> {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(AudioManager::class.java) }
    val max = remember(manager) { manager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 1 }
    var level by remember { mutableIntStateOf(manager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0) }
    DisposableEffect(manager) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                level = manager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: level
            }
        }
        val filter = android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        androidx.core.content.ContextCompat.registerReceiver(context, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return level.toFloat() / max to { v ->
        val to = (v.coerceIn(0f, 1f) * max).let { kotlin.math.round(it).toInt() }
        manager?.setStreamVolume(AudioManager.STREAM_MUSIC, to, 0)
        level = to
    }
}
