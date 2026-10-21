package io.github.arkmind.seedy.core.admin

import io.github.arkmind.seedy.core.i18n.I18n
import io.github.arkmind.seedy.core.i18n.t
import kotlinx.serialization.Serializable
import java.text.NumberFormat
import kotlin.math.roundToInt

/*
 * The hub's Storage section (board "Android · Server"): Jellyfin's folders (GET /System/Info/Storage) grouped by the
 * disk they sit on and named after what they hold, exactly as the web's Server · Overview does
 * (web/src/screens/server/storage.ts).
 */

/** One folder and the disk it's on ([deviceId] is the mount point). */
@Serializable
data class FolderStorage(
    val path: String = "",
    val freeSpace: Long = 0,
    val usedSpace: Long = 0,
    val storageType: String? = null,
    val deviceId: String? = null,
)

@Serializable
data class LibraryStorage(val id: String = "", val name: String = "", val folders: List<FolderStorage> = emptyList())

/** GET /System/Info/Storage (admins). */
@Serializable
data class SystemStorage(
    val programDataFolder: FolderStorage? = null,
    val webFolder: FolderStorage? = null,
    val imageCacheFolder: FolderStorage? = null,
    val cacheFolder: FolderStorage? = null,
    val logFolder: FolderStorage? = null,
    val internalMetadataFolder: FolderStorage? = null,
    val transcodingTempFolder: FolderStorage? = null,
    val libraries: List<LibraryStorage> = emptyList(),
)

/** What a disk mostly holds, which names it and sets its place in the list. */
enum class DiskKind { Server, Library, Cache, Transcodes, Web, Other }

data class DiskFolder(val label: String, val path: String)

/** A disk: its name and mount, how full it is and what's on it. */
data class Disk(
    val kind: DiskKind,
    val name: String,
    val mount: String,
    val used: Long,
    val total: Long,
    val free: Long,
    val pct: Int,
    val ram: Boolean,
    /** "RAM disk, emptied on restart", "Network share". */
    val tag: String?,
    val folders: List<DiskFolder>,
    /** The libraries on it, for the notice's "where … live". */
    val libraries: List<String>,
) {
    val level: Tone get() = diskLevel(pct)

    /** "Program data, Metadata, Cache, Image cache, Logs", after the tag when there is one. */
    val holds: String get() = listOfNotNull(tag, folders.map { it.label }.distinct().joinToString(", ").ifEmpty { null }).joinToString(" · ")
}

private enum class Role(val label: String) {
    Data("Program data"), Metadata("Metadata"), Cache("Cache"), Images("Image cache"), Logs("Logs"),
    Transcodes("Transcodes"), Web("Web files"), Library(""),
}

/** The disks, Server data first, then the libraries', Cache, Transcodes, Web client and the rest. */
fun disks(s: SystemStorage): List<Disk> {
    data class Entry(val role: Role, val label: String, val f: FolderStorage)
    val entries = mutableListOf<Entry>()
    fun add(role: Role, f: FolderStorage?) {
        if (f != null) entries += Entry(role, t(role.label), f)
    }
    add(Role.Data, s.programDataFolder)
    add(Role.Metadata, s.internalMetadataFolder)
    add(Role.Cache, s.cacheFolder)
    add(Role.Images, s.imageCacheFolder)
    add(Role.Logs, s.logFolder)
    add(Role.Transcodes, s.transcodingTempFolder)
    add(Role.Web, s.webFolder)
    for (lib in s.libraries) for (f in lib.folders) entries += Entry(Role.Library, lib.name, f)

    val groups = LinkedHashMap<String, MutableList<Entry>>()
    for (e in entries) {
        if (e.f.usedSpace + e.f.freeSpace <= 0) continue
        val key = e.f.deviceId?.takeIf { it.isNotEmpty() } ?: e.f.path
        groups.getOrPut(key) { mutableListOf() } += e
    }

    val list = groups.map { (mount, es) ->
        val roles = es.map { it.role }.toSet()
        fun only(vararg rs: Role) = roles.all { it in rs }
        val libraries = es.filter { it.role == Role.Library }.map { it.label }.distinct()
        val (kind, name) = when {
            Role.Data in roles -> DiskKind.Server to t("Server data")
            only(Role.Library) -> DiskKind.Library to (libraries.singleOrNull() ?: t("Media libraries"))
            only(Role.Transcodes) -> DiskKind.Transcodes to t("Transcodes")
            only(Role.Web) -> DiskKind.Web to t("Web client")
            Role.Cache in roles || Role.Images in roles -> DiskKind.Cache to t("Cache")
            else -> DiskKind.Other to t("Disk")
        }
        val f = es.first().f
        val total = f.usedSpace + f.freeSpace
        // A disk that is just one folder (a RAM disk for transcodes) is already named after it.
        val alone = es.size == 1 && es[0].f.path == mount
        val seen = HashSet<String>()
        Disk(
            kind = kind,
            name = name,
            mount = mount,
            used = f.usedSpace,
            total = total,
            free = f.freeSpace,
            pct = (f.usedSpace.toDouble() / total * 100).roundToInt(),
            ram = f.storageType == "Ram",
            tag = when (f.storageType) {
                "Ram" -> t("RAM disk, emptied on restart")
                "Network" -> t("Network share")
                else -> null
            },
            folders = es.filter { !alone && seen.add(it.label + it.f.path) }.map { DiskFolder(it.label, it.f.path) },
            libraries = libraries,
        )
    }
    return list.sortedBy { it.kind.ordinal }
}

/** Notices start at 85 %, red from 95 %. */
const val DiskWarnAt = 85

fun diskLevel(pct: Int): Tone = when {
    pct >= 95 -> Tone.Danger
    pct >= DiskWarnAt -> Tone.Pending
    else -> Tone.Ok
}

/** Binary units the way the board writes them: "10.9 TiB", "242 GiB", "2.6 GiB". */
fun diskSize(bytes: Long): String {
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")
    var v = bytes.coerceAtLeast(0).toDouble()
    var i = 0
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    val digits = if (v >= 100 || i == 0) 0 else 1
    val f = NumberFormat.getInstance(I18n.locale).apply {
        minimumFractionDigits = digits
        maximumFractionDigits = digits
    }
    return "${f.format(v)} ${units[i]}"
}

/** The notice's line: "1.6 TiB left on /config, where metadata and the cache live". */
fun diskNotice(d: Disk): String {
    val free = diskSize(d.free)
    return when {
        d.kind == DiskKind.Server -> t("{free} left on {mount}, where metadata and the cache live", "free" to free, "mount" to d.mount)
        d.kind == DiskKind.Library && d.libraries.isNotEmpty() -> t("{free} left on {mount}, where {names} live", "free" to free, "mount" to d.mount, "names" to listing(d.libraries))
        else -> t("{free} left on {mount}", "free" to free, "mount" to d.mount)
    }
}
