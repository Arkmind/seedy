package io.github.arkmind.seedy.mobile.notify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.arkmind.seedy.ui.theme.LocalCore

/**
 * Asks Android 13+ for leave to post notifications, at a moment it makes sense: the person turned "Tell me when a
 * request is ready" on ([force]), or opened or made a request while it's on (once; a "no" is respected). Never at
 * launch.
 */
@Composable
fun rememberNotificationAsk(): (force: Boolean) -> Unit {
    val context = LocalContext.current
    val core = LocalCore.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember(launcher, core) {
        { force ->
            val prefs = context.getSharedPreferences("seedy.requests.ask", Context.MODE_PRIVATE)
            val wanted = force || core?.device?.current?.notifyRequests == true
            val granted = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (wanted && !granted && (force || !prefs.getBoolean("asked", false))) {
                prefs.edit().putBoolean("asked", true).apply()
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
