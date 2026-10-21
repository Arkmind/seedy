package io.github.arkmind.seedy.core.admin.seedy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.admin.AdminHttp
import io.github.arkmind.seedy.core.admin.ItemsResult
import io.github.arkmind.seedy.core.admin.JellyfinJson
import io.github.arkmind.seedy.core.admin.TaskInfo
import io.github.arkmind.seedy.core.admin.Tone
import io.github.arkmind.seedy.core.admin.errorText
import io.github.arkmind.seedy.core.admin.realDate
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.time.Clock
import java.time.Instant
import kotlin.math.roundToLong

/*
 * Server › Live TV (web/src/screens/server/LiveTV.tsx, board ServerLiveTV): tuners and guide sources save on their own
 * (/LiveTv/TunerHosts, /LiveTv/ListingProviders), channel mapping saves per channel, and the guide days and recording
 * settings wait in a draft written over /System/Configuration/livetv.
 */

/** A tuner as Jellyfin keeps it (TunerHostInfo). */
@Serializable
data class TunerHost(
    val id: String? = null,
    val url: String? = null,
    /** "m3u" or "hdhomerun". */
    val type: String = "m3u",
    val deviceId: String? = null,
    val friendlyName: String? = null,
    val importFavoritesOnly: Boolean = false,
    val allowHWTranscoding: Boolean = true,
    val allowFmp4TranscodingContainer: Boolean = false,
    val allowStreamSharing: Boolean = true,
    val fallbackMaxStreamingBitrate: Long = 30_000_000,
    val enableStreamLooping: Boolean = false,
    val source: String? = null,
    val tunerCount: Int = 0,
    val userAgent: String? = null,
    val ignoreDts: Boolean = true,
    val readAtNativeFramerate: Boolean = false,
)

@Serializable
data class NameValue(val name: String = "", val value: String = "")

@Serializable
data class NameIdPair(val name: String = "", val id: String = "")

/** A guide source (ListingsProviderInfo): an XMLTV file or a Schedules Direct lineup. */
@Serializable
data class ListingsProvider(
    val id: String? = null,
    /** "xmltv" or "schedulesdirect". */
    val type: String = "xmltv",
    val username: String? = null,
    val password: String? = null,
    val listingsId: String? = null,
    val zipCode: String? = null,
    val country: String? = null,
    val path: String? = null,
    val enabledTuners: List<String> = emptyList(),
    val enableAllTuners: Boolean = true,
    val newsCategories: List<String> = emptyList(),
    val sportsCategories: List<String> = emptyList(),
    val kidsCategories: List<String> = emptyList(),
    val movieCategories: List<String> = emptyList(),
    val channelMappings: List<NameValue> = emptyList(),
    val moviePrefix: String? = null,
    val preferredLanguage: String? = null,
    val userAgent: String? = null,
)

@Serializable
data class TunerChannelMapping(val name: String = "", val providerChannelName: String? = null, val providerChannelId: String? = null, val id: String = "")

@Serializable
data class ChannelMappingOptions(
    val tunerChannels: List<TunerChannelMapping> = emptyList(),
    val providerChannels: List<NameIdPair> = emptyList(),
    val providerName: String? = null,
)

@Serializable
private data class GuideInfo(val startDate: String? = null, val endDate: String? = null)

/** The guide days and recording settings, saved with the unsaved-changes bar. [guideDays] 0 is Auto. */
data class RecSettings(
    val guideDays: Int = 0,
    val recordingPath: String = "",
    val movieRecordingPath: String = "",
    val seriesRecordingPath: String = "",
    val prePaddingSeconds: Int = 0,
    val postPaddingSeconds: Int = 0,
    val recordingPostProcessor: String = "",
    val recordingPostProcessorArguments: String = "",
    val saveRecordingNfo: Boolean = false,
    val saveRecordingImages: Boolean = false,
) {
    fun changesFrom(o: RecSettings): Int = listOf(
        guideDays == o.guideDays, recordingPath == o.recordingPath, movieRecordingPath == o.movieRecordingPath,
        seriesRecordingPath == o.seriesRecordingPath, prePaddingSeconds == o.prePaddingSeconds, postPaddingSeconds == o.postPaddingSeconds,
        recordingPostProcessor == o.recordingPostProcessor, recordingPostProcessorArguments == o.recordingPostProcessorArguments,
        saveRecordingNfo == o.saveRecordingNfo, saveRecordingImages == o.saveRecordingImages,
    ).count { !it }
}

private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0
private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

fun recSettings(o: JsonObject) = RecSettings(
    guideDays = o.int("GuideDays"),
    recordingPath = o.str("RecordingPath"),
    movieRecordingPath = o.str("MovieRecordingPath"),
    seriesRecordingPath = o.str("SeriesRecordingPath"),
    prePaddingSeconds = o.int("PrePaddingSeconds"),
    postPaddingSeconds = o.int("PostPaddingSeconds"),
    recordingPostProcessor = o.str("RecordingPostProcessor"),
    recordingPostProcessorArguments = o.str("RecordingPostProcessorArguments"),
    saveRecordingNfo = o.bool("SaveRecordingNFO"),
    saveRecordingImages = o.bool("SaveRecordingImages"),
)

private fun orNull(s: String): JsonElement = s.trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull

private fun recPatch(r: RecSettings): Map<String, JsonElement> = mapOf(
    "GuideDays" to (if (r.guideDays > 0) JsonPrimitive(r.guideDays) else JsonNull),
    "RecordingPath" to orNull(r.recordingPath),
    "MovieRecordingPath" to orNull(r.movieRecordingPath),
    "SeriesRecordingPath" to orNull(r.seriesRecordingPath),
    "PrePaddingSeconds" to JsonPrimitive(r.prePaddingSeconds),
    "PostPaddingSeconds" to JsonPrimitive(r.postPaddingSeconds),
    "RecordingPostProcessor" to orNull(r.recordingPostProcessor),
    "RecordingPostProcessorArguments" to JsonPrimitive(r.recordingPostProcessorArguments),
    "SaveRecordingNFO" to JsonPrimitive(r.saveRecordingNfo),
    "SaveRecordingImages" to JsonPrimitive(r.saveRecordingImages),
)

/** Bodies sent to Jellyfin: PascalCase with nulls written out, so clearing a field clears it. */
@OptIn(ExperimentalSerializationApi::class)
private val BodyJson = Json(JellyfinJson) { explicitNulls = true }

/* ── Names and lines, as the web writes them ── */

fun tunerTypeLabel(type: String): String = when (type.lowercase()) {
    "m3u" -> t("M3U tuner")
    "hdhomerun" -> "HDHomeRun"
    else -> type
}

fun tunerName(x: TunerHost): String = x.friendlyName?.trim()?.takeIf { it.isNotEmpty() } ?: if (x.type == "m3u") t("IPTV playlist") else tunerTypeLabel(x.type)

fun hostOf(u: String?): String {
    if (u.isNullOrEmpty()) return ""
    return if (Regex("^[a-z]+://", RegexOption.IGNORE_CASE).containsMatchIn(u)) runCatching { URI(u).let { if (it.port > 0) "${it.host}:${it.port}" else it.host } }.getOrNull() ?: u else u
}

fun sameTuner(a: TunerHost, b: TunerHost): Boolean =
    (!a.deviceId.isNullOrEmpty() && a.deviceId == b.deviceId) || (!a.url.isNullOrEmpty() && hostOf(a.url) == hostOf(b.url))

fun newTuner(type: String) = TunerHost(type = type, url = "", friendlyName = "", tunerCount = 0, userAgent = "")

fun providerName(p: ListingsProvider): String {
    if (p.type == "xmltv") {
        val file = p.path.orEmpty().split('/', '\\').lastOrNull { it.isNotEmpty() }
        return if (file != null) "XMLTV · $file" else "XMLTV"
    }
    return "Schedules Direct"
}

private fun cap(s: String) = s.replaceFirstChar { it.uppercaseChar() }

fun providerDetail(p: ListingsProvider, tuners: List<TunerHost>): String {
    val forTuners = if (p.enableAllTuners || p.enabledTuners.isEmpty()) t("All tuners")
    else t("{tuners} only", "tuners" to tuners.filter { it.id != null && it.id in p.enabledTuners }.joinToString(", ") { tunerName(it) })
    if (p.type == "xmltv") {
        fun cat(label: String, list: List<String>) = if (list.isNotEmpty()) t("{label}: {list}", "label" to t(label), "list" to list.joinToString("|") { cap(it) }) else ""
        return listOf(cat("Movies", p.movieCategories), cat("Kids", p.kidsCategories), cat("News", p.newsCategories), cat("Sports", p.sportsCategories), forTuners)
            .filter { it.isNotEmpty() }.joinToString(" · ")
    }
    return listOf(p.username, p.country, p.zipCode, forTuners).filter { !it.isNullOrEmpty() }.joinToString(" · ")
}

fun blankProvider(type: String) = ListingsProvider(
    type = type, path = "", username = "", listingsId = "", zipCode = "", country = "", enabledTuners = emptyList(), enableAllTuners = true,
    newsCategories = listOf("news", "journalism", "documentary", "current affairs"), sportsCategories = listOf("sports", "basketball", "baseball", "football"),
    kidsCategories = listOf("kids", "family", "children", "childrens", "disney"), movieCategories = listOf("movie"), moviePrefix = "", userAgent = "",
)

/** "just now", "12 min ago", "2 hours ago", "3 days ago". */
fun updatedAgo(d: Instant, clock: Clock): String {
    val min = ((clock.millis() - d.toEpochMilli()) / 60_000.0).roundToLong()
    if (min < 1) return t("just now")
    if (min < 60) return t("{n} min ago", "n" to min)
    val h = (min / 60.0).roundToLong().toInt()
    if (h < 24) return tn(h, "{n} hour ago", "{n} hours ago")
    return tn((h / 24.0).roundToLong().toInt(), "{n} day ago", "{n} days ago")
}

fun guideDaysLabel(n: Int): String = if (n > 0) tn(n, "{n} day", "{n} days") else t("Auto")

data class TunerRow(val id: String, val name: String, val m3u: Boolean, val sub: String, val online: Boolean?)

data class SourceRow(val id: String, val name: String, val kind: String, val detail: String, val status: String, val tone: Tone)

data class LiveTvAdminState(
    val loaded: Boolean = false,
    val loadError: String? = null,
    val tuners: List<TunerHost> = emptyList(),
    val tunerRows: List<TunerRow> = emptyList(),
    val providers: List<ListingsProvider> = emptyList(),
    val sourceRows: List<SourceRow> = emptyList(),
    /** The channel count, shown when there's one tuner. */
    val channels: Int? = null,
    val recSaved: RecSettings? = null,
    val rec: RecSettings? = null,
    val refreshing: Boolean = false,
    val refreshPct: Int? = null,
    val saving: Boolean = false,
    val message: String? = null,
) {
    val recValue: RecSettings? get() = rec ?: recSaved
    val changes: Int get() = if (rec != null && recSaved != null) rec.changesFrom(recSaved) else 0
}

interface LiveTvAdminActions {
    fun refreshGuide() {}
    fun editRec(fn: (RecSettings) -> RecSettings) {}
    fun discardRec() {}
    fun saveRec() {}
    fun removeTuner(id: String) {}
    fun removeProvider(id: String) {}
    /** The editor closed: the server may have saved a guide source while finding lineups. */
    fun reload() {}
    fun retry() {}
    /** Channel mapping changed: [unmapped] channels of that guide source have no listings now. */
    fun mapped(providerId: String, unmapped: Int) {}
    fun say(text: String) {}
    fun messageShown() {}
    /** Saves a tuner; null when it worked, else what to tell the person. */
    suspend fun saveTuner(tuner: TunerHost, isNew: Boolean): String? = null
    suspend fun discover(): Result<List<TunerHost>> = Result.success(emptyList())
    suspend fun saveProvider(p: ListingsProvider, pw: String?, isNew: Boolean): String? = null
    /** Saves the account (lineups need it signed in) and lists its lineups. */
    suspend fun findLineups(p: ListingsProvider, pw: String?): Result<Pair<ListingsProvider, List<NameIdPair>>> = Result.success(p to emptyList())
    /** Schedules Direct's countries: code to name. */
    suspend fun countries(): List<Pair<String, String>> = emptyList()
    suspend fun mappingOptions(providerId: String): Result<ChannelMappingOptions> = Result.success(ChannelMappingOptions())
    suspend fun map(providerId: String, tunerChannelId: String, providerChannelId: String): Result<TunerChannelMapping> = Result.failure(UnsupportedOperationException())
    suspend fun folders(path: String, files: Boolean): FolderListing = FolderListing(path, emptyList(), null)
}

class LiveTvAdminViewModel(
    private val session: UserSession,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel(), LiveTvAdminActions {
    private val http = AdminHttp(session.api)
    private val _state = MutableStateFlow(LiveTvAdminState())
    val state: StateFlow<LiveTvAdminState> = _state.asStateFlow()

    /** Tuners and guide sources as Jellyfin sent them, so saving one keeps what this app doesn't edit. */
    private var rawTuners = mapOf<String, JsonObject>()
    private var rawProviders = mapOf<String, JsonObject>()
    private var found: List<TunerHost>? = null
    private var task: TaskInfo? = null
    private var guideEnd: Instant? = null
    private var unmapped = mapOf<String, Int>()
    private var following: String? = null
    private var announce = false
    private var follow: Job? = null

    fun load(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) _state.update { it.copy(loadError = null) }
            try {
                val o = http.getObject("/System/Configuration/livetv")
                val tunerJson = (o["TunerHosts"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                val providerJson = (o["ListingProviders"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                val tuners = tunerJson.map { JellyfinJson.decodeFromJsonElement(TunerHost.serializer(), it) }
                val providers = providerJson.map { JellyfinJson.decodeFromJsonElement(ListingsProvider.serializer(), it) }
                rawTuners = tuners.zip(tunerJson).mapNotNull { (t, j) -> t.id?.let { it to j } }.toMap()
                rawProviders = providers.zip(providerJson).mapNotNull { (p, j) -> p.id?.let { it to j } }.toMap()
                val rec = recSettings(o)
                val before = _state.value
                _state.update {
                    it.copy(
                        loaded = true, loadError = null, tuners = tuners, providers = providers,
                        recSaved = rec, rec = if (silent) before.rec?.takeIf { r -> r.changesFrom(rec) > 0 } else null,
                    )
                }
                publish()
                if (tuners.any { it.type == "hdhomerun" }) discoverForStatus()
                mappings(providers)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!silent) _state.update { it.copy(loadError = errorText(e)) }
            }
        }
    }

    /** Channel count, the guide task and how far the listings go. */
    fun refreshStatus() {
        viewModelScope.launch {
            runCatching {
                http.get<ItemsResult<JsonObject>>("/LiveTv/Channels", mapOf("userId" to session.userId.toString(), "limit" to 0, "enableImages" to false, "enableUserData" to false))
            }.onSuccess { r -> _state.update { it.copy(channels = r.totalRecordCount) } }.onFailure { _state.update { it.copy(channels = null) } }
            publish()
        }
        viewModelScope.launch {
            runCatching { http.get<List<TaskInfo>>("/ScheduledTasks", mapOf("isHidden" to false)) }.getOrNull()?.let { list ->
                task = list.firstOrNull { it.key == "RefreshGuide" }
                task?.let { if (it.state != "Idle" && following != it.id) followTask(it.id, false) }
                publish()
            }
        }
        viewModelScope.launch {
            runCatching { http.get<GuideInfo>("/LiveTv/GuideInfo") }.getOrNull()?.let { guideEnd = realDate(it.endDate); publish() }
        }
    }

    private fun discoverForStatus() {
        viewModelScope.launch {
            found = runCatching { http.get<List<TunerHost>>("/LiveTv/Tuners/Discover", mapOf("newDevicesOnly" to false)) }.getOrDefault(emptyList())
            publish()
        }
    }

    private fun mappings(providers: List<ListingsProvider>) {
        for (p in providers) {
            val id = p.id ?: continue
            viewModelScope.launch {
                runCatching { http.get<ChannelMappingOptions>("/LiveTv/ChannelMappingOptions", mapOf("providerId" to id)) }.getOrNull()?.let { m ->
                    unmapped = unmapped + (id to m.tunerChannels.count { it.providerChannelId.isNullOrEmpty() })
                    publish()
                }
            }
        }
    }

    private fun publish() = _state.update { s ->
        val one = s.tuners.size == 1
        val tunerRows = s.tuners.map { x ->
            val parts = mutableListOf(tunerTypeLabel(x.type), if (x.type == "hdhomerun") hostOf(x.url) else x.url.orEmpty())
            if (x.tunerCount > 0) parts += tn(x.tunerCount, "{n} stream", "{n} streams")
            if (one && s.channels != null) parts += tn(s.channels, "{n} channel", "{n} channels")
            val f = found
            TunerRow(
                x.id.orEmpty(), tunerName(x), x.type == "m3u", parts.filter { it.isNotEmpty() }.joinToString(" · "),
                if (x.type != "hdhomerun" || f == null) null else f.any { sameTuner(it, x) },
            )
        }
        val last = task?.lastExecutionResult
        val days = guideEnd?.let { maxOf(0L, ((it.toEpochMilli() - clock.millis()) / 86_400_000.0).roundToLong()).toInt() } ?: 0
        val sourceRows = s.providers.map { p ->
            val miss = p.id?.let { unmapped[it] } ?: 0
            val end = realDate(last?.endTimeUtc)
            val (text, tone) = when {
                miss > 0 -> tn(miss, "{n} channel not mapped", "{n} channels not mapped") to Tone.Pending
                last?.status == "Failed" -> t("Last update failed {ago}", "ago" to updatedAgo(end ?: Instant.now(clock), clock)) to Tone.Danger
                end != null -> (if (days > 0) t("Updated {ago} · {days} of listings", "ago" to updatedAgo(end, clock), "days" to tn(days, "{n} day", "{n} days")) else t("Updated {ago}", "ago" to updatedAgo(end, clock))) to Tone.Ok
                else -> t("Not updated yet") to Tone.Normal
            }
            SourceRow(p.id.orEmpty(), providerName(p), if (p.type == "xmltv") "XMLTV" else t("Lineup"), providerDetail(p, s.tuners), text, tone)
        }
        val running = task?.state == "Running" || task?.state == "Cancelling"
        s.copy(
            tunerRows = tunerRows, sourceRows = sourceRows, refreshing = running,
            refreshPct = task?.currentProgressPercentage?.takeIf { running && it > 0 }?.let { Math.round(it).toInt() },
        )
    }

    /* ── Refresh guide data: runs the task and follows it ── */

    private fun followTask(id: String, say: Boolean) {
        following = id
        announce = say || announce
        follow?.cancel()
        follow = viewModelScope.launch {
            while (true) {
                delay(1_500)
                val list = runCatching { http.get<List<TaskInfo>>("/ScheduledTasks", mapOf("isHidden" to false)) }.getOrNull() ?: continue
                val job = list.firstOrNull { it.id == id }
                task = job
                publish()
                if (job != null && job.state != "Idle") continue
                val told = announce
                following = null
                announce = false
                if (told) {
                    val ok = job?.lastExecutionResult?.status == "Completed"
                    say(if (ok) t("Guide data refreshed") else t("Refreshing the guide didn’t finish. Check the log."))
                }
                refreshStatus()
                return@launch
            }
        }
    }

    override fun refreshGuide() {
        viewModelScope.launch {
            try {
                val job = task ?: http.get<List<TaskInfo>>("/ScheduledTasks", mapOf("isHidden" to false)).firstOrNull { it.key == "RefreshGuide" }
                    ?: throw IllegalStateException(t("This server has no Refresh Guide task."))
                http.send("/ScheduledTasks/Running/${job.id}")
                task = job.copy(state = "Running", currentProgressPercentage = 0.0)
                publish()
                followTask(job.id, true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(t("Couldn’t start refreshing the guide.") + " " + (if (e is IllegalStateException) e.message.orEmpty() else errorText(e)))
            }
        }
    }

    /* ── Recording settings ── */

    override fun editRec(fn: (RecSettings) -> RecSettings) = _state.update { s ->
        val cur = s.recValue ?: return@update s
        val next = fn(cur)
        s.copy(rec = if (s.recSaved != null && next.changesFrom(s.recSaved) == 0) null else next)
    }

    override fun discardRec() = _state.update { it.copy(rec = null) }

    override fun saveRec() {
        val r = _state.value.rec ?: return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            try {
                val saved = http.patchObject("/System/Configuration/livetv", recPatch(r))
                _state.update { it.copy(recSaved = recSettings(saved), rec = null, message = t("Live TV settings saved")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(t("Couldn’t save the Live TV settings.") + " " + errorText(e))
            } finally {
                _state.update { it.copy(saving = false) }
            }
        }
    }

    /* ── Tuners ── */

    override suspend fun saveTuner(tuner: TunerHost, isNew: Boolean): String? {
        val url = tuner.url.orEmpty().trim()
        val m3u = tuner.type == "m3u"
        if (url.isEmpty()) return if (m3u) t("Add the playlist’s file or URL.") else t("Add the tuner’s IP address.")
        val body = tuner.copy(url = url, userAgent = tuner.userAgent?.trim()?.ifEmpty { null }, friendlyName = tuner.friendlyName?.trim()?.ifEmpty { null })
        val encoded = BodyJson.encodeToJsonElement(TunerHost.serializer(), body).jsonObject
        val raw = body.id?.let { rawTuners[it] }
        return try {
            http.raw(org.jellyfin.sdk.api.client.HttpMethod.POST, "/LiveTv/TunerHosts", body = raw?.mergedWith(encoded) ?: encoded)
            say(if (isNew) t("{name} added. Fetching its channels…", "name" to tunerName(body)) else t("{name} saved", "name" to tunerName(body)))
            load(silent = true)
            viewModelScope.launch { delay(4_000); refreshStatus() }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            (if (m3u) t("The server couldn’t open that playlist. Check the address and try again.") else t("The server couldn’t reach that tuner.")) + " " + errorText(e)
        }
    }

    override fun removeTuner(id: String) {
        val tuner = _state.value.tuners.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            runCatching { http.delete("/LiveTv/TunerHosts", mapOf("id" to id)) }
                .onSuccess { say(t("{name} removed", "name" to tunerName(tuner))); load(silent = true); refreshStatus() }
                .onFailure { if (it is CancellationException) throw it; say(t("Couldn’t remove the tuner.") + " " + errorText(it)) }
        }
    }

    override suspend fun discover(): Result<List<TunerHost>> = try {
        Result.success(http.get<List<TunerHost>>("/LiveTv/Tuners/Discover", mapOf("newDevicesOnly" to false)))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(IllegalStateException(t("The server couldn’t search the network.")))
    }

    /* ── Guide sources ── */

    private fun providerBody(p: ListingsProvider): JsonObject {
        val encoded = BodyJson.encodeToJsonElement(ListingsProvider.serializer(), p).jsonObject
        return p.id?.let { rawProviders[it] }?.mergedWith(encoded) ?: encoded
    }

    private suspend fun postProvider(p: ListingsProvider, pw: String?, validateListings: Boolean, validateLogin: Boolean): ListingsProvider {
        val query = mapOf("pw" to pw?.takeIf { it.isNotEmpty() }, "validateListings" to validateListings, "validateLogin" to validateLogin)
        val bytes = http.raw(org.jellyfin.sdk.api.client.HttpMethod.POST, "/LiveTv/ListingProviders", query, providerBody(p))
        return if (bytes.isEmpty()) p else JellyfinJson.decodeFromString(ListingsProvider.serializer(), bytes.decodeToString())
    }

    override suspend fun saveProvider(p: ListingsProvider, pw: String?, isNew: Boolean): String? {
        val sd = p.type == "schedulesdirect"
        if (!sd && p.path.orEmpty().isBlank()) return t("Add the XMLTV file or URL.")
        if (sd && (p.username.isNullOrEmpty() || (p.password.isNullOrEmpty() && pw.isNullOrEmpty()))) return t("Add the Schedules Direct username and password.")
        if (sd && p.listingsId.isNullOrEmpty()) return t("Find and pick a lineup first.")
        return try {
            postProvider(if (sd) p else p.copy(path = p.path.orEmpty().trim()), pw, validateListings = true, validateLogin = sd)
            say(if (isNew) t("Guide source added") else t("Guide source saved"))
            load(silent = true)
            refreshStatus()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            (if (sd) t("Schedules Direct didn’t accept that.") else t("The server couldn’t read that XMLTV file.")) + " " + errorText(e)
        }
    }

    override suspend fun findLineups(p: ListingsProvider, pw: String?): Result<Pair<ListingsProvider, List<NameIdPair>>> = try {
        val saved = postProvider(p, pw, validateListings = false, validateLogin = true)
        val list = http.get<List<NameIdPair>>(
            "/LiveTv/ListingProviders/Lineups",
            mapOf("id" to saved.id, "type" to "schedulesdirect", "location" to p.zipCode, "country" to p.country),
        )
        Result.success(p.copy(id = saved.id, password = saved.password) to list)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(IllegalStateException(t("Schedules Direct didn’t accept that account.") + " " + errorText(e)))
    }

    override suspend fun countries(): List<Pair<String, String>> = runCatching {
        val o = http.getObject("/LiveTv/ListingProviders/SchedulesDirect/Countries")
        o.values.flatMap { (it as? JsonArray).orEmpty() }.mapNotNull { c ->
            val obj = c as? JsonObject ?: return@mapNotNull null
            val code = obj["shortName"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            code to (obj["fullName"]?.jsonPrimitive?.contentOrNull ?: code)
        }.sortedBy { it.second }
    }.getOrDefault(emptyList())

    override fun removeProvider(id: String) {
        viewModelScope.launch {
            runCatching { http.delete("/LiveTv/ListingProviders", mapOf("id" to id)) }
                .onSuccess { say(t("Guide source removed")); load(silent = true) }
                .onFailure { if (it is CancellationException) throw it; say(t("Couldn’t remove the guide source.") + " " + errorText(it)) }
        }
    }

    override fun reload() = load(silent = true)

    override fun retry() {
        load()
        refreshStatus()
    }

    /* ── Channel mapping ── */

    override suspend fun mappingOptions(providerId: String): Result<ChannelMappingOptions> = try {
        Result.success(http.get<ChannelMappingOptions>("/LiveTv/ChannelMappingOptions", mapOf("providerId" to providerId)))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(IllegalStateException(t("The server couldn’t list the channels.")))
    }

    override suspend fun map(providerId: String, tunerChannelId: String, providerChannelId: String): Result<TunerChannelMapping> = try {
        val body = kotlinx.serialization.json.buildJsonObject {
            put("ProviderId", JsonPrimitive(providerId))
            put("TunerChannelId", JsonPrimitive(tunerChannelId))
            put("ProviderChannelId", JsonPrimitive(providerChannelId))
        }
        val res = http.post<TunerChannelMapping>("/LiveTv/ChannelMappings", body)
        // The provider's unmapped count follows the sheet's list (it re-reads when it closes).
        Result.success(res)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(IllegalStateException(t("Couldn’t map that channel.") + " " + errorText(e)))
    }

    override fun mapped(providerId: String, unmapped: Int) {
        val n = unmapped
        this.unmapped = this.unmapped + (providerId to n)
        publish()
    }

    override suspend fun folders(path: String, files: Boolean): FolderListing = listFolder(http, path, files)

    override fun say(text: String) = _state.update { it.copy(message = text) }

    override fun messageShown() = _state.update { it.copy(message = null) }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
