package io.github.arkmind.seedy.mobile.update

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.requestAppUpdateInfo
import io.github.arkmind.seedy.core.data.io
import io.github.arkmind.seedy.core.update.ApkInstaller
import io.github.arkmind.seedy.core.update.Releases
import io.github.arkmind.seedy.core.update.UpdateOffer
import io.github.arkmind.seedy.core.update.UpdateStore
import io.github.arkmind.seedy.core.update.versionNameOf
import io.github.arkmind.seedy.ui.components.openUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The phone's "is there a newer Seedy?" check, once per run of the app. Installed from Google Play, Play says whether
 * there's an update (and its size); anywhere else, GitHub's releases do (asked at most every 6 hours). Either way the
 * update screen's list comes from the release's description on GitHub. Debug builds never check.
 */
object AppUpdates {
    /** The update to offer, until it's answered (Update or Later) in this run. */
    var offer by mutableStateOf<UpdateOffer?>(null)
        private set

    /** The installed version's name ("0.4.6"). */
    var installed = ""
        private set

    private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var download: Job? = null

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        UpdatedNotice.clear(app)
        if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return
        scope.launch { offer = runCatching { check(app) }.getOrNull() }
    }

    private suspend fun check(context: Context): UpdateOffer? {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        installed = info.versionName.orEmpty()
        val code = info.code()
        val store = UpdateStore(context)
        val now = System.currentTimeMillis()
        val offer = if (fromPlay(context)) playOffer(context, store, now) else gitHubOffer(store, code, now)
        return offer?.takeIf { it.code > code && !store.snoozed(it.code, now) }
    }

    private suspend fun gitHubOffer(store: UpdateStore, installed: Long, now: Long): UpdateOffer? {
        store.cached(now)?.let { return it.takeIf { it.code > 0 } }
        val releases = io { Releases.fetch() } ?: return null
        return Releases.newest(releases, installed)?.offer().also { store.save(it, now) }
    }

    private suspend fun playOffer(context: Context, store: UpdateStore, now: Long): UpdateOffer? {
        val info = AppUpdateManagerFactory.create(context).requestAppUpdateInfo()
        val availability = info.updateAvailability()
        if (availability != UpdateAvailability.UPDATE_AVAILABLE && availability != UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) return null
        val code = info.availableVersionCode().toLong()
        val notes = store.notesFor(code)
            ?: io { Releases.fetch() }?.firstOrNull { it.code == code }?.offer()?.also { store.save(it, now) }
        return (notes ?: UpdateOffer(versionNameOf(code), code))
            .copy(bytes = info.totalBytesToDownload().takeIf { it > 0 }, fromPlay = true, apkUrl = null)
    }

    /** Outside Google Play: downloads the APK here and hands it to Android's installer ([ApkInstaller.state]). */
    fun install(context: Context, offer: UpdateOffer) {
        val url = offer.apkUrl ?: return
        if (download?.isActive == true) return
        val app = context.applicationContext
        download = scope.launch { ApkInstaller.install(app, url, offer.code, offer.bytes, offer.apkSha256) }
    }

    /** "Later": stops a download, and isn't offered again for a week (unless a newer version comes out). */
    fun later(context: Context) {
        download?.cancel()
        offer?.let { UpdateStore(context).later(it.code, System.currentTimeMillis()) }
        offer = null
    }

    /** Gone for this run (the update started). */
    fun done() {
        offer = null
    }

    private fun fromPlay(context: Context): Boolean {
        val pm = context.packageManager
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(context.packageName).installingPackageName
            else @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName)
        }.getOrNull()
        return installer == "com.android.vending"
    }

    private fun PackageInfo.code(): Long =
        if (Build.VERSION.SDK_INT >= 28) longVersionCode else @Suppress("DEPRECATION") versionCode.toLong()
}

/**
 * The update screen over the phone's tabs while there's an update to offer ([visible]: only at the top of the tabs,
 * never over the player or a book). Update starts Google Play's own update screen; outside Play, the APK downloads and
 * installs from here (the browser takes it on a build without the updater).
 */
@Composable
fun UpdatePrompt(visible: Boolean) {
    val offer = AppUpdates.offer ?: return
    if (!visible) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // A finished update restarts the app; a cancelled one comes back to this screen as it was.
    val play = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }
    val install by ApkInstaller.state.collectAsState()
    // Back from "Install unknown apps": the download starts once Seedy may install.
    var allowing by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (allowing) {
            allowing = false
            if (context.packageManager.canRequestPackageInstalls()) AppUpdates.install(context, offer)
        }
    }
    UpdateScreen(
        offer = offer,
        installed = AppUpdates.installed,
        onUpdate = {
            if (offer.fromPlay) {
                scope.launch {
                    val manager = AppUpdateManagerFactory.create(context)
                    val started = runCatching {
                        val info = manager.requestAppUpdateInfo()
                        info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) &&
                            manager.startUpdateFlowForResult(info, play, AppUpdateOptions.defaultOptions(AppUpdateType.IMMEDIATE))
                    }.getOrDefault(false)
                    if (!started) context.openUrl("market://details?id=${context.packageName}")
                }
            } else if (offer.apkUrl != null && ApkInstaller.available(context)) {
                // Android's "Install unknown apps" first (asked once): going there mid-install would cancel it.
                if (context.packageManager.canRequestPackageInstalls()) {
                    AppUpdates.install(context, offer)
                } else {
                    allowing = true
                    runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
                }
            } else {
                context.openUrl(offer.apkUrl ?: offer.url)
                AppUpdates.done()
            }
        },
        onLater = { AppUpdates.later(context) },
        onChanges = { context.openUrl(offer.url) },
        install = install,
    )
}
