package io.github.arkmind.seedy.core.update

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import io.github.arkmind.seedy.core.data.io
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Where installing a new APK is at. */
sealed interface InstallState {
    data object Idle : InstallState

    /** [progress] 0..1, or null while the size isn't known. */
    data class Downloading(val progress: Float?) : InstallState

    /** Handed to Android: its "Update this app?" dialog, then the install. */
    data object Installing : InstallState

    /** The download or the install failed ([download]: no file, a cut connection or a checksum that doesn't match). */
    data class Failed(val download: Boolean) : InstallState
}

/**
 * Updates the app from an APK outside Google Play: streams the download straight into an Android install session
 * (checked against GitHub's SHA-256 when there is one), then commits it. Android asks the person to confirm, and the
 * first time to allow Seedy to install apps; it only accepts an APK signed like the installed one. Builds for Google
 * Play leave out the REQUEST_INSTALL_PACKAGES permission ([available] is then false): Play updates them.
 */
object ApkInstaller {
    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state

    fun available(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().contains(Manifest.permission.REQUEST_INSTALL_PACKAGES)
    }.getOrDefault(false)

    /** Downloads [url] ([bytes] long, [sha256] in hex when known) and starts installing it. Cancelling stops the download. */
    suspend fun install(context: Context, url: String, bytes: Long?, sha256: String?) {
        val app = context.applicationContext
        val installer = app.packageManager.packageInstaller
        _state.value = InstallState.Downloading(null)
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (bytes != null) setSize(bytes)
            // Always Android's own dialog (it ends on "App installed · Open"), never a silent swap of the running app.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = runCatching { installer.createSession(params) }.getOrElse {
            _state.value = InstallState.Failed(download = true)
            return
        }
        try {
            installer.openSession(id).use { session ->
                io { download(session, url, bytes, sha256) }
                _state.value = InstallState.Installing
                session.commit(statusIntent(app, id).intentSender)
            }
        } catch (e: CancellationException) {
            installer.abandonSession(id)
            _state.value = InstallState.Idle
            throw e
        } catch (_: Exception) {
            runCatching { installer.abandonSession(id) }
            _state.value = InstallState.Failed(download = true)
        }
    }

    private suspend fun download(session: PackageInstaller.Session, url: String, bytes: Long?, sha256: String?) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "Seedy-Android")
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        try {
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            val total = c.contentLengthLong.takeIf { it > 0 } ?: bytes
            val digest = MessageDigest.getInstance("SHA-256")
            session.openWrite("seedy.apk", 0, total ?: -1).use { out ->
                c.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        if (total != null) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != shown) {
                                shown = percent
                                _state.value = InstallState.Downloading((done.toFloat() / total).coerceAtMost(1f))
                            }
                        }
                    }
                    if (total != null) check(done == total) { "Got $done of $total bytes" }
                }
                session.fsync(out)
            }
            if (sha256 != null) {
                val got = digest.digest().joinToString("") { "%02x".format(it) }
                check(got.equals(sha256, ignoreCase = true)) { "SHA-256 mismatch" }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun statusIntent(context: Context, session: Int): PendingIntent {
        val intent = Intent(context, InstallStatusReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, session, intent, flags)
    }

    internal fun onStatus(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    _state.value = InstallState.Failed(download = false)
                    return
                }
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    _state.value = InstallState.Failed(download = false)
                }
            }
            // The new version replaces this one: Android stops the app.
            PackageInstaller.STATUS_SUCCESS -> _state.value = InstallState.Idle
            // "Cancel" in Android's dialog.
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = InstallState.Idle
            else -> _state.value = InstallState.Failed(download = false)
        }
    }
}

/** Android's answers about an install session (see [ApkInstaller]). */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = ApkInstaller.onStatus(context, intent)
}
