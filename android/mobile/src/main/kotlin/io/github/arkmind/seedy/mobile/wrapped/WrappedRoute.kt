package io.github.arkmind.seedy.mobile.wrapped

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.wrapped.WrappedViewModel
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.parts.PrimaryButton
import io.github.arkmind.seedy.ui.components.Medu
import io.github.arkmind.seedy.ui.components.MeduMood
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession
import kotlinx.coroutines.launch

/** Seedy Wrapped for [year], full screen and upright (from Home's banner or Settings). */
@Composable
fun WrappedRoute(year: Int) {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val vm = viewModel(key = "wrapped-$year") { WrappedViewModel(session, year) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    // A picture waiting for the storage permission (Android 9 and older).
    var pending by remember { mutableStateOf<ImageBitmap?>(null) }
    Upright()

    fun save(picture: ImageBitmap) = scope.launch {
        message = if (WrappedPictures.save(context, picture, "seedy-wrapped-$year")) t("Picture saved") else t("Couldn’t make the picture")
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val p = pending
        pending = null
        if (granted && p != null) save(p) else if (p != null) message = t("Couldn’t make the picture")
    }

    val w = state.wrapped
    when {
        w != null -> WrappedScreen(
            w,
            onClose = nav::back,
            onShare = { picture ->
                scope.launch {
                    if (!WrappedPictures.share(context, picture, "seedy-wrapped-$year", t("Share my Wrapped"))) message = t("Couldn’t make the picture")
                }
            },
            onSave = { picture ->
                val granted = !WrappedPictures.needsPermission ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                if (granted) save(picture) else {
                    pending = picture
                    ask.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            },
            message = message,
            onMessageShown = { message = null },
        )
        state.failed -> NotReady(onClose = nav::back)
        else -> Box(Modifier.fillMaxSize().background(SeedyColors.Bg), contentAlignment = Alignment.Center) {
            BackHandler(onBack = nav::back)
            Medu(MeduMood.Think, 104.dp, height = 137.dp)
        }
    }
}

/** No Wrapped for that year (or the server didn't answer): Medu says when it comes. */
@Composable
private fun NotReady(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(SeedyColors.Bg), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp).widthIn(max = 360.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Medu(MeduMood.Sleepy, 120.dp, height = 158.dp)
            SText(t("Your Wrapped isn’t ready"), size = 22.sp, weight = 900, color = Color.White, align = TextAlign.Center)
            SText(t("Seedy Wrapped arrives every December, once you’ve watched a few things."), size = 15.sp, lineHeight = 1.45f, color = SeedyColors.Text2, align = TextAlign.Center)
            PrimaryButton(t("Close"), onClose, Modifier.padding(top = 12.dp))
        }
    }
}

/** The stories are drawn upright: the phone stays in portrait while they're open. */
@Composable
private fun Upright() {
    val activity: Activity? = LocalActivity.current
    DisposableEffect(activity) {
        val before = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        onDispose { activity?.requestedOrientation = before ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
}

/** The status bar's icons dark on light stories, light on dark ones; as they were once Wrapped closes. */
@Composable
internal fun StoryBars(pale: Boolean) {
    val activity: Activity? = LocalActivity.current
    val view = LocalView.current
    val window = activity?.window ?: return
    val controller = remember(window) { WindowCompat.getInsetsController(window, view) }
    DisposableEffect(controller) {
        val light = controller.isAppearanceLightStatusBars
        val lightNav = controller.isAppearanceLightNavigationBars
        onDispose {
            controller.isAppearanceLightStatusBars = light
            controller.isAppearanceLightNavigationBars = lightNav
        }
    }
    LaunchedEffect(pale) {
        controller.isAppearanceLightStatusBars = pale
        controller.isAppearanceLightNavigationBars = pale
    }
}
