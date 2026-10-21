package io.github.arkmind.seedy.mobile.admin.seedy

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.admin.seedy.AdminRequestsViewModel
import io.github.arkmind.seedy.core.admin.seedy.BrandingViewModel
import io.github.arkmind.seedy.core.admin.seedy.HomeAdminViewModel
import io.github.arkmind.seedy.core.admin.seedy.LiveTvAdminViewModel
import io.github.arkmind.seedy.core.admin.seedy.LogoTypes
import io.github.arkmind.seedy.core.admin.seedy.NetworkingViewModel
import io.github.arkmind.seedy.core.admin.seedy.PlayLogAdminViewModel
import io.github.arkmind.seedy.core.admin.seedy.PlayLogProfile
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.ui.theme.currentSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The server's Networking and Live TV pages and Seedy's own admin pages (hub rows "networking", "livetv", "branding",
 * "homescreen", "requests", "playlog"). None has a phone board: each is built from the phone server boards' kit with
 * the web page's content and behaviour. Pages load once per visit (a rotation keeps drafts).
 */

@Composable
fun NetworkingRoute() {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val vm = viewModel { NetworkingViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { if (vm.state.value.saved == null) vm.load() }
    LaunchedEffect(vm) { vm.live() }
    NetworkingScreen(state, vm, onBack = nav::back)
}

@Composable
fun ServerLiveTvRoute() {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val vm = viewModel { LiveTvAdminViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        if (!vm.state.value.loaded) {
            vm.load()
            vm.refreshStatus()
        }
    }
    LiveTvAdminScreen(state, vm, onBack = nav::back)
}

@Composable
fun BrandingRoute() {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val vm = viewModel { BrandingViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = context.contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: uri.lastPathSegment.orEmpty()
                    val mime = resolver.getType(uri).orEmpty()
                    // One byte past the limit is enough to tell an oversized file without reading all of it.
                    val bytes = resolver.openInputStream(uri)?.use { it.readUpTo(2 * 1024 * 1024 + 1) } ?: ByteArray(0)
                    Triple(bytes, name, mime)
                }.getOrNull()
            }
            if (read == null) vm.say(t("Couldn’t upload the logo.")) else vm.uploadLogo(read.first, read.second, read.third)
        }
    }
    LaunchedEffect(vm) { if (vm.state.value.config == null) vm.load() }
    BrandingScreen(state, vm, onBack = nav::back, onPickLogo = { pick.launch(LogoTypes.toTypedArray()) })
}

@Composable
fun DefaultHomeRoute() {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val vm = viewModel { HomeAdminViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { if (vm.state.value.saved == null) vm.load() }
    DefaultHomeScreen(state, vm, onBack = nav::back)
}

@Composable
fun AdminRequestsRoute() {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val vm = viewModel { AdminRequestsViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    LaunchedEffect(vm) { if (vm.state.value.config == null) vm.load() }
    AdminRequestsScreen(state, vm, onBack = nav::back, openUrl = { url -> runCatching { uri.openUri(url) } })
}

@Composable
fun PlayLogRoute() {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val vm = viewModel { PlayLogAdminViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf<PlayLogProfile?>(null) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        val p = exporting
        exporting = null
        if (uri == null || p == null) return@rememberLauncherForActivityResult
        scope.launch {
            vm.export(p.userId)
                .onSuccess { bytes ->
                    val ok = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null }.getOrDefault(false) }
                    vm.say(if (ok) t("{name}’s plays exported", "name" to p.name) else t("Couldn’t export {name}’s plays.", "name" to p.name))
                }
                .onFailure { vm.say(it.message ?: t("Couldn’t export {name}’s plays.", "name" to p.name)) }
        }
    }
    LaunchedEffect(vm) { if (vm.state.value.config == null) vm.load() }
    PlayLogScreen(state, vm, onBack = nav::back, onExport = { p ->
        exporting = p
        val file = p.name.trim().replace(Regex("[\\\\/:*?\"<>|\\s]+"), "-").ifEmpty { p.userId }
        create.launch("seedy-plays-$file.csv")
    })
}

/** Up to [max] bytes of the stream (InputStream.readNBytes is API 33 on Android). */
private fun java.io.InputStream.readUpTo(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    while (out.size() < max) {
        val n = read(buf, 0, minOf(buf.size, max - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
