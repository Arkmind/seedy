package io.github.arkmind.seedy.core.admin.system

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.admin.AdminHttp
import io.github.arkmind.seedy.core.admin.PackageInfo
import io.github.arkmind.seedy.core.admin.PluginInfo
import io.github.arkmind.seedy.core.admin.SeedyJson
import io.github.arkmind.seedy.core.admin.SystemInfo
import io.github.arkmind.seedy.core.admin.TaskComparator
import io.github.arkmind.seedy.core.admin.TaskGroups
import io.github.arkmind.seedy.core.admin.TaskInfo
import io.github.arkmind.seedy.core.admin.TaskTrigger
import io.github.arkmind.seedy.core.admin.errorText
import io.github.arkmind.seedy.core.admin.lastRun
import io.github.arkmind.seedy.core.admin.logNameFor
import io.github.arkmind.seedy.core.admin.nextRun
import io.github.arkmind.seedy.core.admin.nextText
import io.github.arkmind.seedy.core.admin.pluginUpdates
import io.github.arkmind.seedy.core.admin.realDate
import io.github.arkmind.seedy.core.admin.scheduleText
import io.github.arkmind.seedy.core.admin.taskDesc
import io.github.arkmind.seedy.core.admin.taskGroup
import io.github.arkmind.seedy.core.admin.taskName
import io.github.arkmind.seedy.core.i18n.I18n
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Clock
import java.time.Instant
import kotlin.math.roundToInt

/*
 * Scheduled tasks (board "Android · Server · Scheduled tasks", web/src/screens/server/Tasks.tsx): the server's tasks
 * in the board's groups, what each did last, run and stop, and the schedule sheet. Polls every second while
 * something runs, every 10 s otherwise.
 */

/** How a task's last-run text is coloured: grey, red (failed, with "see logs") or the accent (running). */
enum class LastTone { Normal, Failed, Running }

data class TaskRow(
    val id: String,
    val name: String,
    val desc: String,
    /** "Every 12 hours", "Daily at 03:00 (server time)". */
    val schedule: String,
    /** "Running since 20:41", "Today, 22 min", "Failed on Sunday". */
    val last: String,
    val tone: LastTone = LastTone.Normal,
    /** The day's server log and what to look for in it, when the last run failed. */
    val logFile: String? = null,
    val logQuery: String? = null,
    val error: String? = null,
    val running: Boolean = false,
    val cancelling: Boolean = false,
    /** 0–1 while running with a known progress; null while it doesn't say. */
    val progress: Float? = null,
    /** "42%". */
    val pct: String = "",
    val busy: Boolean = false,
    val triggers: List<TaskTrigger> = emptyList(),
)

data class TaskGroupRows(val label: String, val tasks: List<TaskRow>)

data class TasksState(
    /** "1 running · next: Clean transcode folder at 03:00. Tap a task to change its schedule." */
    val lede: String = "",
    /** Null while loading. */
    val groups: List<TaskGroupRows>? = null,
    /** Why the list couldn't load (with nothing to show). */
    val error: String? = null,
    val savingSchedule: Boolean = false,
    val message: String? = null,
)

interface TasksActions {
    fun run(id: String) {}
    fun stop(id: String) {}
    /** Saves [triggers] as the task's schedule; [done] says whether it worked (the sheet then closes). */
    fun saveTriggers(id: String, triggers: List<TaskTrigger>, done: (Boolean) -> Unit) {}
    fun retry() {}
    fun messageShown() {}
}

/** When a task was first seen running in this app's run ("Running since 20:41"): Jellyfin doesn't say. */
private object TaskStarts {
    val map = mutableMapOf<String, Instant>()
}

/** Seedy's own tasks, named in the viewer's language. */
private val SeedyTasks = mapOf(
    "SeedyPlayLogCleanup" to ("Seedy: trim play log" to "Deletes plays older than the retention setting"),
    "SeedyAutomaticBackup" to ("Seedy: weekly backup" to "Makes the weekly backup set in Backups and removes old ones"),
)

fun systemTaskName(task: TaskInfo): String = SeedyTasks[task.key]?.let { t(it.first) } ?: taskName(task)
fun systemTaskDesc(task: TaskInfo): String = SeedyTasks[task.key]?.let { t(it.second) } ?: taskDesc(task)

/** "42%" ("42 %" in French). */
fun percentText(p: Double): String = NumberFormat.getPercentInstance(I18n.locale).format(p.roundToInt() / 100.0)

class TasksViewModel(
    private val session: UserSession,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel(), TasksActions {
    private val http = AdminHttp(session.api)
    private val _state = MutableStateFlow(TasksState(lede = t("Reading the server’s tasks…")))
    val state: StateFlow<TasksState> = _state.asStateFlow()

    private var tasks: List<TaskInfo>? = null
    private var previous: Map<String, String>? = null
    private val pending = mutableSetOf<String>()
    private var offset: Int? = ServerClock.offset
    private var updates: Int? = null
    private var failed = false

    /** Reads the tasks now and keeps them current until cancelled (1 s while one runs, 10 s otherwise). */
    suspend fun live() {
        if (offset == null) viewModelScope.launch { offset = ServerClock.load(http, session.seedyApi != null); publish() }
        if (updates == null) viewModelScope.launch { loadUpdates() }
        while (true) {
            val ok = poll()
            delay(if (!ok) 15_000 else if (tasks.orEmpty().any { it.state != "Idle" }) 1_000 else 10_000)
        }
    }

    private suspend fun poll(): Boolean = try {
        val list = http.get<List<TaskInfo>>("/ScheduledTasks", mapOf("isHidden" to false))
        val before = previous
        for (x in list) {
            if (x.state != "Idle") {
                if (TaskStarts.map[x.id] == null && before != null && before[x.id] == "Idle") TaskStarts.map[x.id] = clock.instant()
            } else {
                TaskStarts.map.remove(x.id)
            }
        }
        previous = list.associate { it.id to it.state }
        tasks = list
        failed = false
        publish()
        true
    } catch (e: Throwable) {
        rethrowCancel(e)
        failed = true
        if (tasks == null) _state.update { it.copy(error = t("Couldn’t load the scheduled tasks. {error}", "error" to errorText(e))) }
        publish()
        false
    }

    /** "2 updates found" under Check for plugin updates: the catalog against what's installed. */
    private suspend fun loadUpdates() {
        runCatching {
            val info = http.get<SystemInfo>("/System/Info")
            val plugins = http.get<List<PluginInfo>>("/Plugins")
            val packages = http.get<List<PackageInfo>>("/Packages", json = SeedyJson)
            updates = pluginUpdates(plugins, packages, info.version).size
            publish()
        }.onFailure(::rethrowCancel)
    }

    private fun publish() {
        val list = tasks
        val onServerClock = ServerClock.differs(offset, clock)
        _state.update { st ->
            if (list == null) return@update st.copy(lede = if (failed) t("Couldn’t read the server’s tasks.") else t("Reading the server’s tasks…"))
            val groups = TaskGroups.map { g -> TaskGroupRows(t(g), list.filter { taskGroup(it) == g }.sortedWith(TaskComparator).map { row(it, onServerClock) }) }.filter { it.tasks.isNotEmpty() }
            st.copy(lede = lede(list), groups = groups, error = null)
        }
    }

    private fun lede(list: List<TaskInfo>): String {
        val running = list.count { it.state != "Idle" }
        val next = list.filter { it.state == "Idle" }.mapNotNull { x -> nextRun(x, clock, offset)?.let { x to it } }.minByOrNull { it.second }
        val parts = listOfNotNull(
            if (running > 0) t("{n} running", "n" to running) else t("Nothing running"),
            next?.let { t("next: {task} {when}", "task" to systemTaskName(it.first), "when" to nextText(it.second, clock)) },
        )
        return t("{status}. Tap a task to change its schedule.", "status" to parts.joinToString(" · "))
    }

    private fun row(task: TaskInfo, onServerClock: Boolean): TaskRow {
        val last = lastRun(task, clock, TaskStarts.map[task.id])
        val result = task.lastExecutionResult
        val end = realDate(result?.endTimeUtc) ?: realDate(result?.startTimeUtc)
        val timed = task.triggers.any { it.type == "DailyTrigger" || it.type == "WeeklyTrigger" }
        val found = updates?.takeIf { task.key == "PluginUpdates" && task.state == "Idle" && result?.status == "Completed" && it > 0 }
        val p = task.currentProgressPercentage
        val running = task.state != "Idle"
        return TaskRow(
            id = task.id,
            name = systemTaskName(task),
            desc = systemTaskDesc(task),
            schedule = withServerTime(scheduleText(task), timed && onServerClock),
            last = found?.let { tn(it, "{n} update found", "{n} updates found") } ?: last.text,
            tone = when {
                last.running -> LastTone.Running
                last.failed -> LastTone.Failed
                else -> LastTone.Normal
            },
            logFile = if (last.failed && end != null) logNameFor(end) else null,
            logQuery = if (last.failed) task.name else null,
            error = last.error,
            running = running,
            cancelling = task.state == "Cancelling",
            progress = if (running && p != null && p > 0) (p / 100).toFloat().coerceIn(0f, 1f) else null,
            pct = if (running && p != null && p > 0) percentText(p.coerceAtMost(100.0)) else "",
            busy = task.id in pending,
            triggers = task.triggers,
        )
    }

    private fun say(text: String) = _state.update { it.copy(message = text) }

    override fun messageShown() = _state.update { it.copy(message = null) }

    override fun retry() {
        viewModelScope.launch { poll() }
    }

    private fun named(id: String) = tasks?.firstOrNull { it.id == id }?.let(::systemTaskName) ?: ""

    override fun run(id: String) {
        if (id in pending) return
        pending += id
        publish()
        viewModelScope.launch {
            runCatching { http.send("/ScheduledTasks/Running/$id") }
                .onSuccess {
                    TaskStarts.map[id] = clock.instant()
                    tasks = tasks?.map { if (it.id == id) it.copy(state = "Running") else it }
                    previous = previous?.plus(id to "Running")
                }
                .onFailure { rethrowCancel(it); say(t("Couldn’t start {task}. {error}", "task" to named(id), "error" to errorText(it))) }
            pending -= id
            publish()
            poll()
        }
    }

    override fun stop(id: String) {
        if (id in pending) return
        pending += id
        publish()
        viewModelScope.launch {
            runCatching { http.delete("/ScheduledTasks/Running/$id") }
                .onSuccess { tasks = tasks?.map { if (it.id == id) it.copy(state = "Cancelling") else it } }
                .onFailure { rethrowCancel(it); say(t("Couldn’t stop {task}. {error}", "task" to named(id), "error" to errorText(it))) }
            pending -= id
            publish()
            poll()
        }
    }

    override fun saveTriggers(id: String, triggers: List<TaskTrigger>, done: (Boolean) -> Unit) {
        _state.update { it.copy(savingSchedule = true) }
        viewModelScope.launch {
            val name = named(id)
            val ok = runCatching { http.send("/ScheduledTasks/$id/Triggers", jellyfinList(triggers)) }
                .onFailure { rethrowCancel(it); say(t("Couldn’t save the schedule. {error}", "error" to errorText(it))) }
                .isSuccess
            if (ok) {
                tasks = tasks?.map { if (it.id == id) it.copy(triggers = triggers) else it }
                say(t("Schedule saved for {task}", "task" to name))
            }
            _state.update { it.copy(savingSchedule = false) }
            publish()
            done(ok)
            if (ok) poll()
        }
    }
}
