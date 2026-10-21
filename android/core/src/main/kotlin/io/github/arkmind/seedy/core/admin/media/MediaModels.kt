package io.github.arkmind.seedy.core.admin.media

import io.github.arkmind.seedy.core.admin.AdminHttp
import io.github.arkmind.seedy.core.admin.errorText
import io.github.arkmind.seedy.core.i18n.I18n
import io.github.arkmind.seedy.core.i18n.t
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.text.NumberFormat
import kotlin.math.floor

/*
 * What the media pages (Libraries, Playback, Settings) read besides the shared admin models, as in
 * web/src/screens/server/api.ts, and the helpers they share: reading and writing settings kept as Jellyfin's JSON, and
 * the server's folder picker.
 */

/** GET /Libraries/AvailableOptions: what a kind of library can use. */
@Serializable
data class AvailableOptions(
    val metadataSavers: List<OptionInfo> = emptyList(),
    val metadataReaders: List<OptionInfo> = emptyList(),
    val subtitleFetchers: List<OptionInfo> = emptyList(),
    val lyricFetchers: List<OptionInfo> = emptyList(),
    val mediaSegmentProviders: List<OptionInfo> = emptyList(),
    val typeOptions: List<LibraryTypeOptionsInfo> = emptyList(),
)

@Serializable
data class OptionInfo(val name: String = "", val defaultEnabled: Boolean = false)

@Serializable
data class LibraryTypeOptionsInfo(
    val type: String = "",
    val metadataFetchers: List<OptionInfo> = emptyList(),
    val imageFetchers: List<OptionInfo> = emptyList(),
    val similarItemProviders: List<OptionInfo> = emptyList(),
    val supportedImageTypes: List<String> = emptyList(),
    val defaultImageOptions: JsonArray? = null,
)

/** GET /Localization/Cultures. */
@Serializable
data class Culture(
    val name: String = "",
    val displayName: String = "",
    val twoLetterISOLanguageName: String = "",
    val threeLetterISOLanguageName: String = "",
    val threeLetterISOLanguageNames: List<String> = emptyList(),
)

/** GET /Localization/Countries. */
@Serializable
data class Country(val name: String = "", val displayName: String = "", val twoLetterISORegionName: String = "")

/** GET /Localization/Options: the server's interface languages. */
@Serializable
data class LocalizationOption(val name: String = "", val value: String = "")

/** GET /Environment/DirectoryContents and /Environment/Drives. [type] is "Directory", "File", "NetworkComputer"… */
@Serializable
data class FsEntry(val name: String = "", val path: String = "", val type: String = "Directory")

/** GET /System/Logs. */
@Serializable
data class LogFile(val dateCreated: String = "", val dateModified: String = "", val size: Long = 0, val name: String = "")

/* ── Settings kept as JSON ── */

fun JsonObject.bool(key: String, default: Boolean = false): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: default
fun JsonObject.int(key: String, default: Int = 0): Int = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() } ?: default
fun JsonObject.double(key: String, default: Double = 0.0): Double = (this[key] as? JsonPrimitive)?.doubleOrNull ?: default
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())

fun json(v: Boolean): JsonElement = JsonPrimitive(v)
fun json(v: Number): JsonElement = JsonPrimitive(v)
fun json(v: String?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)
fun json(v: List<String>): JsonElement = JsonArray(v.map { JsonPrimitive(it) })

/** A copy with [key] set. */
fun JsonObject.with(key: String, value: JsonElement): JsonObject = JsonObject(this + (key to value))

/** "42%" ("42 %" in French), from 0–100. */
fun percentLabel(p: Double): String = NumberFormat.getPercentInstance(I18n.locale).format(floor(p) / 100)

/** The folder a path is in ("/media/movies" → "/media"). */
fun parentOf(path: String): String = path.replace(Regex("[\\\\/][^\\\\/]+[\\\\/]?$"), "").ifEmpty { "/" }

/* ── The server's folders ── */

/** The folder picker's sheet: where it is, what's in it, and what picking does. */
data class FolderPicker(
    val title: String,
    val action: String,
    /** "" lists the server's drives. */
    val path: String,
    /** Null while loading. */
    val entries: List<FsEntry>? = null,
    /** The folder above; "" for the drives, null when there's nothing above (the drives themselves). */
    val parent: String? = null,
    val error: String? = null,
    /** Files can be picked too (fonts, certificates). */
    val files: Boolean = false,
)

/** What the folder picker sheet does; every page with one has these. */
interface FolderPickerActions {
    fun browse(path: String) {}
    fun pickFolder(path: String) {}
    fun closePicker() {}
}

/**
 * The folder picker (web/src/screens/server/parts.tsx FolderPicker): lists folders on the server, not on this device.
 * [open] starts it at a folder; picking hands the path to that call's callback.
 */
class FolderBrowser(private val http: AdminHttp, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<FolderPicker?>(null)
    val state: StateFlow<FolderPicker?> = _state.asStateFlow()
    private var onPick: ((String) -> Unit)? = null
    private var job: Job? = null

    fun open(title: String, action: String, start: String, files: Boolean = false, onPick: (String) -> Unit) {
        this.onPick = onPick
        _state.value = FolderPicker(title, action, start.trim(), files = files)
        go(start.trim())
    }

    fun go(path: String) {
        val current = _state.value ?: return
        _state.value = current.copy(path = path, entries = null, error = null, parent = if (path.isEmpty()) null else current.parent)
        job?.cancel()
        job = scope.launch {
            val listing = runCatching {
                if (path.isEmpty()) http.get<List<FsEntry>>("/Environment/Drives")
                else http.get<List<FsEntry>>("/Environment/DirectoryContents", mapOf("path" to path, "includeDirectories" to true, "includeFiles" to current.files))
            }
            val parent = if (path.isEmpty()) null else runCatching { http.get<String?>("/Environment/ParentPath", mapOf("path" to path)) }.getOrNull()?.takeIf { it != path } ?: ""
            _state.update { s ->
                if (s == null || s.path != path) s
                else s.copy(
                    entries = listing.getOrNull()?.filter { it.type != "NetworkComputer" }?.sortedWith(compareBy({ it.type == "File" }, { it.name.lowercase() })) ?: emptyList(),
                    error = listing.exceptionOrNull()?.let { if (path.isEmpty()) t("The server didn’t list its drives.") else t("The server can’t open this folder.") },
                    parent = parent,
                )
            }
        }
    }

    fun pick(path: String) {
        val then = onPick
        close()
        then?.invoke(path)
    }

    fun close() {
        job?.cancel()
        onPick = null
        _state.value = null
    }
}

/** An error as the line under a form. */
fun failText(e: Throwable, fallback: String): String = (e as? io.github.arkmind.seedy.core.admin.AdminException)?.let { errorText(it) } ?: fallback
