package io.github.arkmind.seedy.mobile.admin.system

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.admin.system.ActivityViewModel
import io.github.arkmind.seedy.core.admin.system.BackupsViewModel
import io.github.arkmind.seedy.core.admin.system.LogsViewModel
import io.github.arkmind.seedy.core.admin.system.PluginsViewModel
import io.github.arkmind.seedy.core.admin.system.TasksViewModel
import io.github.arkmind.seedy.core.admin.system.backupFilePath
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.nav.Route
import io.github.arkmind.seedy.ui.theme.currentSession

/* The System pages of the server area: scheduled tasks, activity log, plugins, backups and logs. */

/** Board "Android · Server · Scheduled tasks". Polls while the page is on screen. */
@Composable
fun TasksRoute() {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val vm = viewModel { TasksViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.live() } }
    TasksScreen(state, vm, onBack = nav::back, openLogs = { file, query ->
        LogsLink.file = file.ifEmpty { null }
        LogsLink.query = query
        nav.go(Route.Admin("logs"))
    })
}

/** Board "Android · Server · Activity log". Live polls every 10 s while the page is on screen. */
@Composable
fun ActivityRoute() {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val vm = viewModel { ActivityViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm) { vm.load() }
    LaunchedEffect(vm) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.live() } }
    ActivityScreen(state, vm, onBack = nav::back)
}

@Composable
fun PluginsRoute() {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val context = LocalContext.current
    val vm = viewModel { PluginsViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.load() }
    PluginsScreen(
        state, vm, onBack = nav::back,
        openUrl = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
        openSeedy = { nav.go(Route.Admin("branding")) },
    )
}

@Composable
fun BackupsRoute() {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val context = LocalContext.current
    val vm = viewModel { BackupsViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.load() }
    BackupsScreen(
        state, vm, onBack = nav::back,
        download = { b ->
            val ok = downloadFromServer(context, session, backupFilePath(b.fileName), emptyMap(), b.fileName, "application/zip")
            vm.say(if (ok) t("Downloading {file}…", "file" to b.fileName) else t("Couldn’t start the download."))
        },
        copyPath = { path ->
            val ok = copyText(context, "Backup", path)
            vm.say(if (ok) t("Path copied") else t("Couldn’t copy. Select the path instead."))
        },
    )
}

@Composable
fun LogsRoute() {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val context = LocalContext.current
    val vm = viewModel { LogsViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.load() }
    // Tasks' "see logs" opens that day's log once the list is in.
    val listed = state.files != null || state.error != null
    LaunchedEffect(listed) {
        val file = LogsLink.file
        if (file != null && listed) {
            LogsLink.file = null
            vm.open(file, LogsLink.query)
        }
    }
    LogsScreen(
        state, vm, onBack = nav::back,
        copy = {
            val text = vm.linesText()
            val ok = copyText(context, "Log", text)
            val n = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1
            vm.say(if (ok) tn(n, "Copied {n} line", "Copied {n} lines") else t("Couldn’t copy. Use Download instead."))
        },
        download = { name ->
            val ok = downloadFromServer(context, session, "/System/Logs/Log", mapOf("name" to name), name, "text/plain")
            vm.say(if (ok) t("Downloading {file}…", "file" to name) else t("Couldn’t start the download."))
        },
    )
}
