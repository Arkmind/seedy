package io.github.arkmind.seedy.ui.update

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
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
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.photos.bytesLabel
import io.github.arkmind.seedy.core.update.InstallState
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The apps' "is there a newer Seedy?" check (phone and TV), each time the app comes to the front (at most every 6 hours:
 * TVs keep apps running in the background for days instead of closing them). Installed from Google Play, Play says
 * whether there's an update (and its size); anywhere else, GitHub's releases do. Either way the update screen's list
 * comes from the release's description on GitHub. Debug builds never check.
 */
object AppUpdates {
    /** The update to offer, until it's answered (Update or Later). */
    var offer by mutableStateOf<UpdateOffer?>(null)
        private set

    /** The installed version's name ("0.4.6"). */
    var installed = ""
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var download: Job? = null
    private var checking: Job? = null
    /** When this run last got an answer ([SystemClock.elapsedRealtime], which counts the TV's standby too). */
    private var checkedAt: Long? = null
    /** How long each try of a check waits first. */
    private val tries = listOf(0L, 15_000L, 60_000L)

    /**
     * Checks again when the app comes to the front (ON_START), unless an offer is on screen or this run got an answer
     * less than [UpdateStore.RecheckMs] ago. A failed check tries again a little later (a TV back from standby is
     * often still joining its Wi-Fi), then at the next ON_START.
     */
    fun start(context: Context) {
        val app = context.applicationContext
        if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return
        if (offer != null || checking?.isActive == true || download?.isActive == true) return
        checkedAt?.let { if (SystemClock.elapsedRealtime() - it < UpdateStore.RecheckMs) return }
        checking = scope.launch {
            for (wait in tries) {
                delay(wait)
                val answer = runCatching { check(app) }
                if (answer.isSuccess) {
                    checkedAt = SystemClock.elapsedRealtime()
                    offer = answer.getOrNull()
                    return@launch
                }
            }
        }
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
        val releases = io { Releases.fetch() } ?: error("GitHub can't be reached")
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

/** What an update screen's buttons do, and where the APK's install is at. */
class UpdateActions(val install: InstallState, val onUpdate: () -> Unit, val onLater: () -> Unit, val onChanges: () -> Unit)

/**
 * The update screens' buttons (phone and TV). Update starts Google Play's own update screen; outside Play, the APK
 * downloads and installs from the app (after Android's "Install unknown apps", asked once), or the browser takes it on
 * a build without the updater. Later stops a download and waits a week. "See everything that changed" opens the
 * release.
 */
@Composable
fun rememberUpdateActions(offer: UpdateOffer): UpdateActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // A finished update restarts the app; a cancelled one comes back to the screen as it was.
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
    return UpdateActions(
        install = install,
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
                // Android's "Install unknown apps" first: going there in the middle of an install cancels it.
                val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                if (context.packageManager.canRequestPackageInstalls()) {
                    AppUpdates.install(context, offer)
                } else if (runCatching { context.startActivity(settings) }.isSuccess) {
                    allowing = true
                } else {
                    // No such page on this device: Android's install dialog asks instead.
                    AppUpdates.install(context, offer)
                }
            } else {
                context.openUrl(offer.apkUrl ?: offer.url)
                AppUpdates.done()
            }
        },
        onLater = { AppUpdates.later(context) },
        onChanges = { context.openUrl(offer.url) },
    )
}

/** About how long the download takes at about 1 MB/s, in tens of seconds or whole minutes ([wifi]: "… on Wi‑Fi"). */
fun downloadTime(bytes: Long, wifi: Boolean): String {
    val s = bytes / 1_000_000.0
    return when {
        s < 55 -> {
            val n = ((s / 10).roundToInt() * 10).coerceAtLeast(10)
            if (wifi) t("about {n} seconds on Wi‑Fi", "n" to n) else t("about {n} seconds", "n" to n)
        }
        s < 90 -> if (wifi) t("about a minute on Wi‑Fi") else t("about a minute")
        else -> {
            val n = (s / 60).roundToInt()
            if (wifi) t("about {n} minutes on Wi‑Fi", "n" to n) else t("about {n} minutes", "n" to n)
        }
    }
}

/** "18.4 MB · about 20 seconds on Wi‑Fi · from Google Play" ([wifi]: the phone's). */
fun sourceLine(offer: UpdateOffer, wifi: Boolean): String = listOfNotNull(
    offer.bytes?.let(::bytesLabel),
    offer.bytes?.let { downloadTime(it, wifi) },
    if (offer.fromPlay) t("from Google Play") else t("from GitHub"),
).joinToString(" · ")
