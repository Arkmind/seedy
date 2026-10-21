package io.github.arkmind.seedy.core.admin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * What the server pages read from Jellyfin, as in web/src/screens/server/api.ts. Fields are camelCase here and
 * PascalCase on the wire ([JellyfinJson]); enums stay strings so a value this app doesn't know yet still reads.
 */

/** Jellyfin's ids with or without dashes, in any case, compared the same. */
fun normId(id: String?): String = id.orEmpty().replace("-", "").lowercase()
fun sameId(a: String?, b: String?): Boolean = !a.isNullOrEmpty() && !b.isNullOrEmpty() && normId(a) == normId(b)

@Serializable
data class ItemsResult<T>(val items: List<T> = emptyList(), val totalRecordCount: Int = 0)

/** GET /System/Info. */
@Serializable
data class SystemInfo(
    val serverName: String = "",
    val version: String = "",
    val id: String = "",
    val operatingSystemDisplayName: String? = null,
    val operatingSystem: String? = null,
    val systemArchitecture: String? = null,
    val hasPendingRestart: Boolean = false,
    val isShuttingDown: Boolean = false,
    val canSelfRestart: Boolean = true,
    val programDataPath: String? = null,
    val itemsByNamePath: String? = null,
    val cachePath: String? = null,
    val logPath: String? = null,
    val internalMetadataPath: String? = null,
    val transcodingTempPath: String? = null,
)

/** GET /Items/Counts. */
@Serializable
data class ItemCounts(
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    val episodeCount: Int = 0,
    val albumCount: Int = 0,
    val songCount: Int = 0,
    val musicVideoCount: Int = 0,
    val bookCount: Int = 0,
    val boxSetCount: Int = 0,
)

/** GET /ScheduledTasks. [state] is "Idle", "Running" or "Cancelling". */
@Serializable
data class TaskInfo(
    val name: String = "",
    val state: String = "Idle",
    val currentProgressPercentage: Double? = null,
    val id: String = "",
    val lastExecutionResult: TaskResult? = null,
    val triggers: List<TaskTrigger> = emptyList(),
    val description: String = "",
    val category: String = "",
    val isHidden: Boolean = false,
    val key: String = "",
)

/** [status] is "Completed", "Failed", "Cancelled" or "Aborted". */
@Serializable
data class TaskResult(
    val startTimeUtc: String? = null,
    val endTimeUtc: String? = null,
    val status: String = "Completed",
    val name: String? = null,
    val key: String? = null,
    val id: String? = null,
    val errorMessage: String? = null,
    val longErrorMessage: String? = null,
)

/** [type] is "DailyTrigger", "WeeklyTrigger", "IntervalTrigger" or "StartupTrigger". */
@Serializable
data class TaskTrigger(
    val type: String = "IntervalTrigger",
    val timeOfDayTicks: Long? = null,
    val intervalTicks: Long? = null,
    val dayOfWeek: String? = null,
    val maxRuntimeTicks: Long? = null,
)

/** GET /Users: the parts the lists show. Editing reads the policy as JSON ([AdminUserRaw]). */
@Serializable
data class AdminUser(
    val id: String = "",
    val name: String = "",
    val primaryImageTag: String? = null,
    val hasPassword: Boolean = true,
    val lastLoginDate: String? = null,
    val lastActivityDate: String? = null,
    val policy: UserPolicyView = UserPolicyView(),
)

@Serializable
data class UserPolicyView(
    val isAdministrator: Boolean = false,
    val isHidden: Boolean = false,
    val isDisabled: Boolean = false,
    val enableRemoteAccess: Boolean = true,
    val loginAttemptsBeforeLockout: Int = -1,
    val invalidLoginAttemptCount: Int = 0,
    val maxActiveSessions: Int = 0,
    val remoteClientBitrateLimit: Int = 0,
    val enableAllFolders: Boolean = true,
    val enabledFolders: List<String> = emptyList(),
    val maxParentalRating: Int? = null,
    val accessSchedules: List<AccessSchedule> = emptyList(),
)

/** [dayOfWeek] is a weekday's English name, "Everyday", "Weekday" or "Weekend"; hours are 0–24 (0.5 steps). */
@Serializable
data class AccessSchedule(val id: Int? = null, val userId: String? = null, val dayOfWeek: String = "Everyday", val startHour: Double = 0.0, val endHour: Double = 24.0)

/** A user as Jellyfin sent it, policy and configuration kept whole for saving. */
data class AdminUserRaw(val user: AdminUser, val json: JsonObject) {
    val policy: JsonObject get() = json["Policy"] as? JsonObject ?: JsonObject(emptyMap())
    val configuration: JsonObject get() = json["Configuration"] as? JsonObject ?: JsonObject(emptyMap())
}

/** GET /Plugins. [status] is "Active", "Restart", "Disabled", "NotSupported", "Malfunctioned", "Superceded", "Deleted"… */
@Serializable
data class PluginInfo(
    val name: String = "",
    val version: String = "",
    val configurationFileName: String? = null,
    val description: String = "",
    val id: String = "",
    val canUninstall: Boolean = true,
    val hasImage: Boolean = false,
    val status: String = "Active",
)

/** GET /Packages (camelCase on the wire: read with [SeedyJson]). */
@Serializable
data class PackageInfo(
    val name: String = "",
    val description: String? = null,
    val overview: String? = null,
    val owner: String? = null,
    val category: String? = null,
    val guid: String = "",
    val versions: List<PackageVersion> = emptyList(),
    val imageUrl: String? = null,
)

@Serializable
data class PackageVersion(
    val version: String = "",
    val changelog: String? = null,
    val targetAbi: String? = null,
    val sourceUrl: String? = null,
    val checksum: String? = null,
    val timestamp: String? = null,
    val repositoryName: String = "",
    val repositoryUrl: String = "",
)

/** GET /Library/VirtualFolders. */
@Serializable
data class VirtualFolder(
    val name: String = "",
    val locations: List<String> = emptyList(),
    val collectionType: String? = null,
    val itemId: String = "",
    val primaryImageItemId: String? = null,
    val refreshProgress: Double? = null,
    val refreshStatus: String? = null,
    val libraryOptions: JsonObject? = null,
)

/** GET /System/ActivityLog/Entries. [severity] is "Information", "Warning", "Error", "Critical"… */
@Serializable
data class ActivityEntry(
    val id: Long = 0,
    val name: String = "",
    val overview: String? = null,
    val shortOverview: String? = null,
    val type: String = "",
    val itemId: String? = null,
    val date: String = "",
    val userId: String? = null,
    val userPrimaryImageTag: String? = null,
    val severity: String = "Information",
)

/** GET /Devices. */
@Serializable
data class AdminDevice(
    val name: String? = null,
    val customName: String? = null,
    val id: String = "",
    val lastUserName: String? = null,
    val appName: String? = null,
    val appVersion: String? = null,
    val lastUserId: String? = null,
    val dateLastActivity: String? = null,
    val iconUrl: String? = null,
)

/** GET /Seedy/Admin/Status (camelCase): when the server process started and what it runs on. */
@Serializable
data class SeedyAdminStatus(
    val pluginVersion: String = "",
    val serverVersion: String = "",
    val serverStartedAt: String? = null,
    val os: String? = null,
    val utcOffsetMinutes: Int? = null,
    val seerr: SeerrLinkState? = null,
    val playLog: PlayLogState? = null,
)

@Serializable
data class SeerrLinkState(val state: String = "", val message: String? = null, val checkedAt: String? = null)

@Serializable
data class PlayLogState(val enabled: Boolean = false, val retentionDays: Int = 0, val plays: Int = 0)
