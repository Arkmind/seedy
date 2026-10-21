package io.github.arkmind.seedy.mobile.admin.seedy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.admin.seedy.ChannelMappingOptions
import io.github.arkmind.seedy.core.admin.seedy.ListingsProvider
import io.github.arkmind.seedy.core.admin.seedy.LiveTvAdminActions
import io.github.arkmind.seedy.core.admin.seedy.LiveTvAdminState
import io.github.arkmind.seedy.core.admin.seedy.NameIdPair
import io.github.arkmind.seedy.core.admin.seedy.TunerChannelMapping
import io.github.arkmind.seedy.core.admin.seedy.TunerHost
import io.github.arkmind.seedy.core.admin.seedy.blankProvider
import io.github.arkmind.seedy.core.admin.seedy.newTuner
import io.github.arkmind.seedy.core.admin.seedy.providerName
import io.github.arkmind.seedy.core.admin.seedy.tunerName
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.settings.Choice
import io.github.arkmind.seedy.mobile.admin.ConfirmSheet
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.admin.PillStyle
import io.github.arkmind.seedy.mobile.admin.SaveBarRoom
import io.github.arkmind.seedy.mobile.settings.PickRow
import io.github.arkmind.seedy.mobile.settings.Section
import io.github.arkmind.seedy.mobile.settings.SectionFoot
import io.github.arkmind.seedy.mobile.settings.SegRow
import io.github.arkmind.seedy.mobile.settings.SettingsPage
import io.github.arkmind.seedy.mobile.settings.SwitchRow
import io.github.arkmind.seedy.mobile.settings.cssBox
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import kotlinx.coroutines.launch

/** A tuner switch, for the tuner types it applies to (as the web lists them). */
private class TunerSwitch(val name: String, val help: String, val types: Set<String>, val get: (TunerHost) -> Boolean, val set: (TunerHost, Boolean) -> TunerHost)

private val TunerSwitches = listOf(
    TunerSwitch("Only favourite channels", "Import only channels marked as favourite on the tuner", setOf("hdhomerun"), { it.importFavoritesOnly }) { x, v -> x.copy(importFavoritesOnly = v) },
    TunerSwitch("Hardware transcoding on the tuner", "Let the tuner convert streams itself, so the server does less", setOf("hdhomerun"), { it.allowHWTranscoding }) { x, v -> x.copy(allowHWTranscoding = v) },
    TunerSwitch("Allow fMP4 container", "Needed for HEVC and HDR channels. Turn off if playback fails", setOf("m3u"), { it.allowFmp4TranscodingContainer }) { x, v -> x.copy(allowFmp4TranscodingContainer = v) },
    TunerSwitch("Share streams", "Several people watching one channel use one tuner slot", setOf("m3u"), { it.allowStreamSharing }) { x, v -> x.copy(allowStreamSharing = v) },
    TunerSwitch("Auto-loop live streams", "For sources that send only a few seconds at a time", setOf("m3u"), { it.enableStreamLooping }) { x, v -> x.copy(enableStreamLooping = v) },
    TunerSwitch("Ignore decoding timestamps", "Can fix missing audio on some channels", setOf("m3u"), { it.ignoreDts }) { x, v -> x.copy(ignoreDts = v) },
    TunerSwitch("Read at native frame rate", "Can fix streams that stop too early", setOf("m3u"), { it.readAtNativeFramerate }) { x, v -> x.copy(readAtNativeFramerate = v) },
)

/** A tuner's settings (the web's inline editor, as a page): Cancel and Save tuner at the foot. */
@Composable
internal fun TunerEditor(draft: TunerHost, isNew: Boolean, state: LiveTvAdminState, actions: LiveTvAdminActions, onClose: () -> Unit, onChange: (TunerHost) -> Unit) {
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf(false) }
    var streams by remember { mutableStateOf(draft.tunerCount.toString()) }
    var mbps by remember { mutableStateOf(Math.round(draft.fallbackMaxStreamingBitrate / 1_000_000.0).toString()) }
    val scope = rememberCoroutineScope()
    val m3u = draft.type == "m3u"
    val set = { x: TunerHost -> error = null; onChange(x) }
    val save: () -> Unit = {
        busy = true
        error = null
        scope.launch {
            val err = actions.saveTuner(draft, isNew)
            busy = false
            if (err == null) onClose() else error = err
        }
    }
    SettingsPage(
        tunerTitle(draft, isNew),
        tunerSub(draft, isNew, if (state.tuners.size == 1 && !isNew) state.channels else null),
        onClose,
        overlay = {
            if (removing) {
                ConfirmSheet(
                    t("Remove {name}?", "name" to tunerName(draft)), t("Its channels and their listings leave Live TV. Recordings already made are kept."),
                    t("Remove tuner"), { draft.id?.let(actions::removeTuner); onClose() }, { removing = false }, danger = true,
                )
            }
            UnsavedBar("", t("Save tuner"), onClose, save, saving = busy, discardLabel = t("Cancel"), savingLabel = t("Checking…"))
        },
    ) {
        if (isNew) {
            Section(null) {
                row {
                    SegRow(t("Type"), t("What kind of tuner this is"), listOf(Choice("m3u", t("M3U playlist (IPTV)")), Choice("hdhomerun", "HDHomeRun")), draft.type) { v ->
                        set(newTuner(v).copy(friendlyName = draft.friendlyName, url = draft.url))
                        streams = "0"
                        mbps = "30"
                    }
                }
            }
        }
        FieldSection(null) {
            FieldBlock(t("Tuner name"), draft.friendlyName.orEmpty(), { set(draft.copy(friendlyName = it)) }, placeholder = if (isNew) t("Name this tuner") else tunerName(draft))
            FieldBlock(
                if (m3u) t("File or URL") else t("IP address"), draft.url.orEmpty(), { set(draft.copy(url = it)) },
                help = if (m3u) t("M3U playlist") else t("The tuner’s address on your network"),
                error = if (error != null && draft.url.isNullOrBlank()) "" else null,
                placeholder = if (m3u) "http://iptv.local/playlist.m3u" else "192.168.1.40", keyboard = KeyboardType.Uri,
            )
            if (m3u) FieldBlock(t("User agent"), draft.userAgent.orEmpty(), { set(draft.copy(userAgent = it)) }, help = t("Optional, sent with every request"), placeholder = t("Default"))
        }
        FieldSection(
            t("Limits"),
            foot = if (m3u) t("0 streams means no limit. Bitrate is used when it can’t be detected") else t("Bitrate is used when it can’t be detected"),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (m3u) {
                    FieldBlock(t("Simultaneous streams"), streams, { v ->
                        streams = v.filter(Char::isDigit).take(2)
                        set(draft.copy(tunerCount = streams.toIntOrNull() ?: 0))
                    }, Modifier.weight(1f), keyboard = KeyboardType.Number)
                }
                FieldBlock(t("Fallback bitrate in Mbps"), mbps, { v ->
                    mbps = v.filter(Char::isDigit).take(3)
                    set(draft.copy(fallbackMaxStreamingBitrate = (mbps.toIntOrNull() ?: 1).coerceIn(1, 400) * 1_000_000L))
                }, Modifier.weight(1f), keyboard = KeyboardType.Number)
            }
        }
        Section(null) {
            for (s in TunerSwitches.filter { draft.type in it.types }) row { SwitchRow(t(s.name), t(s.help), s.get(draft)) { v -> set(s.set(draft, v)) } }
        }
        val err = error
        if (err != null) SText(err, Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp).cssBox(12, 1.45f, up = true), size = 12.sp, lineHeight = 1.45f, color = SeedyColors.Danger)
        else SectionFoot(t("Channels with DRM are never imported."))
        if (!isNew) {
            PillButton(t("Remove tuner"), { removing = true }, Modifier.padding(start = 16.dp, top = 22.dp), style = PillStyle.Danger)
        }
        SaveBarRoom()
    }
}

private fun splitList(s: String): List<String> = s.split('|', ',').map { it.trim() }.filter { it.isNotEmpty() }

/** A guide source: an XMLTV file (path, categories) or a Schedules Direct account and lineup; which tuners use it. */
@Composable
internal fun SourceEditor(provider: ListingsProvider?, tuners: List<TunerHost>, actions: LiveTvAdminActions, onClose: () -> Unit) {
    val isNew = provider == null
    var p by remember { mutableStateOf(provider ?: blankProvider("xmltv")) }
    var movies by remember { mutableStateOf(p.movieCategories.joinToString(", ")) }
    var kids by remember { mutableStateOf(p.kidsCategories.joinToString(", ")) }
    var news by remember { mutableStateOf(p.newsCategories.joinToString(", ")) }
    var sports by remember { mutableStateOf(p.sportsCategories.joinToString(", ")) }
    var pw by remember { mutableStateOf("") }
    var countries by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var lineups by remember { mutableStateOf<List<NameIdPair>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val sd = p.type == "schedulesdirect"
    val set = { x: ListingsProvider -> error = null; p = x }
    LaunchedEffect(sd) { if (sd && countries == null) countries = actions.countries() }

    fun resetCats(x: ListingsProvider) {
        movies = x.movieCategories.joinToString(", ")
        kids = x.kidsCategories.joinToString(", ")
        news = x.newsCategories.joinToString(", ")
        sports = x.sportsCategories.joinToString(", ")
    }

    val save: () -> Unit = {
        busy = true
        error = null
        val body = if (sd) p else p.copy(
            path = p.path.orEmpty().trim(), movieCategories = splitList(movies), kidsCategories = splitList(kids),
            newsCategories = splitList(news), sportsCategories = splitList(sports),
        )
        scope.launch {
            val err = actions.saveProvider(body, pw.ifEmpty { null }, isNew)
            busy = false
            if (err == null) onClose() else error = err
        }
    }
    val findLineups: () -> Unit = {
        busy = true
        error = null
        scope.launch {
            actions.findLineups(p, pw.ifEmpty { null })
                .onSuccess { (saved, list) ->
                    pw = ""
                    lineups = list
                    p = saved.copy(listingsId = saved.listingsId?.takeIf { it.isNotEmpty() } ?: list.firstOrNull()?.id)
                }
                .onFailure { error = it.message }
            busy = false
        }
    }

    SettingsPage(
        if (isNew) t("Add guide source") else providerName(p),
        t("Where channel listings come from."),
        onClose,
        overlay = {
            when (sheet) {
                "file" -> FolderSheet(t("Choose the XMLTV file"), p.path.orEmpty(), true, actions::folders, { v -> set(p.copy(path = v)) }, { sheet = null })
                "country" -> SearchPickSheet(
                    t("Country"), null, countries.orEmpty().map { Choice(it.first, it.second) }, p.country,
                    { v -> set(p.copy(country = v)) }, { sheet = null },
                )
                "lineup" -> SearchPickSheet(
                    t("Lineup"), null, lineups.orEmpty().map { Choice(it.id, it.name) }, p.listingsId,
                    { v -> set(p.copy(listingsId = v)) }, { sheet = null },
                )
            }
            UnsavedBar(
                "", if (isNew) t("Add guide source") else t("Save"), onClose, save,
                saving = busy, discardLabel = t("Cancel"), savingLabel = t("Checking…"),
            )
        },
    ) {
        if (isNew) {
            Section(null) {
                row {
                    SegRow(t("Guide source type"), null, listOf(Choice("xmltv", t("XMLTV file")), Choice("schedulesdirect", "Schedules Direct")), p.type) { v ->
                        val b = blankProvider(v)
                        set(b)
                        resetCats(b)
                        lineups = null
                    }
                }
            }
        }
        if (!sd) {
            FieldSection(null) {
                FieldBlock(t("File or URL"), p.path.orEmpty(), { set(p.copy(path = it)) }, placeholder = t("/config/xmltv/tv-guide.xml or https://…"), keyboard = KeyboardType.Uri)
                PillButton(t("Browse"), { sheet = "file" }, leading = { SIcon(WebIcons.Folder, size = 15.dp, color = SeedyColors.White) })
            }
            FieldSection(t("Categories"), foot = t("Listing categories, separated by commas, that put programmes in Movies, Kids, News and Sports.")) {
                FieldBlock(t("Movies categories"), movies, { movies = it })
                FieldBlock(t("Kids categories"), kids, { kids = it })
                FieldBlock(t("News categories"), news, { news = it })
                FieldBlock(t("Sports categories"), sports, { sports = it })
            }
            FieldSection(null) {
                FieldBlock(t("Movie prefix"), p.moviePrefix.orEmpty(), { set(p.copy(moviePrefix = it)) }, placeholder = t("None"))
                FieldBlock(t("User agent"), p.userAgent.orEmpty(), { set(p.copy(userAgent = it)) }, placeholder = t("Default"))
            }
        } else {
            FieldSection(null) {
                FieldBlock(t("Username"), p.username.orEmpty(), { set(p.copy(username = it)) })
                FieldBlock(t("Password"), pw, { pw = it; error = null }, placeholder = if (!p.password.isNullOrEmpty()) t("Saved") else "", password = true)
                FieldBlock(t("Postal code"), p.zipCode.orEmpty(), { set(p.copy(zipCode = it)) })
            }
            Section(null) {
                row {
                    val name = countries?.firstOrNull { it.first == p.country }?.second ?: p.country?.takeIf { it.isNotEmpty() } ?: t("Choose…")
                    PickRow(t("Country"), null, name) { if (!countries.isNullOrEmpty()) sheet = "country" }
                }
                row {
                    val name = lineups?.firstOrNull { it.id == p.listingsId }?.name ?: p.listingsId?.takeIf { it.isNotEmpty() } ?: t("Find lineups for your area")
                    PickRow(t("Lineup"), null, name) { if (!lineups.isNullOrEmpty()) sheet = "lineup" else findLineups() }
                }
            }
            PillButton(
                t("Find lineups"), findLineups, Modifier.padding(start = 16.dp, top = 12.dp),
                enabled = !busy && !p.username.isNullOrEmpty() && !p.country.isNullOrEmpty() && !p.zipCode.isNullOrEmpty(),
            )
        }
        if (tuners.isNotEmpty()) {
            Section(t("Use for")) {
                row { SwitchRow(t("All tuners"), null, p.enableAllTuners) { v -> set(p.copy(enableAllTuners = v)) } }
                if (!p.enableAllTuners) {
                    for (x in tuners) {
                        val id = x.id ?: continue
                        row {
                            SwitchRow(tunerName(x), null, id in p.enabledTuners) { on ->
                                set(p.copy(enabledTuners = if (on) p.enabledTuners.filter { it != id } + id else p.enabledTuners.filter { it != id }, enableAllTuners = false))
                            }
                        }
                    }
                }
            }
        }
        error?.let { SText(it, Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp), size = 12.sp, lineHeight = 1.45f, color = SeedyColors.Danger) }
        SaveBarRoom()
    }
}

/** Which listing each tuner channel shows; each pick saves at once. */
@Composable
internal fun MapChannels(provider: ListingsProvider, actions: LiveTvAdminActions, onClose: () -> Unit) {
    val id = provider.id.orEmpty()
    var opts by remember { mutableStateOf<ChannelMappingOptions?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<TunerChannelMapping?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(id) {
        actions.mappingOptions(id).onSuccess { opts = it }.onFailure { error = it.message }
    }
    SettingsPage(
        t("Map channels"),
        t("Pick the listing each tuner channel should show from {source}. Channels with the same name or number are matched automatically.", "source" to providerName(provider)),
        onClose,
        overlay = {
            val o = opts
            picking?.let { c ->
                if (o != null) {
                    SearchPickSheet(
                        t("Listing for {name}", "name" to c.name), null,
                        listOf(Choice("", t("Not mapped"))) + o.providerChannels.map { Choice(it.id, it.name) },
                        c.providerChannelId.orEmpty(),
                        { v ->
                            scope.launch {
                                actions.map(id, c.id, v)
                                    .onSuccess { res ->
                                        val cur = opts ?: return@onSuccess
                                        val next = cur.copy(tunerChannels = cur.tunerChannels.map {
                                            if (it.id == c.id) it.copy(providerChannelName = res.providerChannelName, providerChannelId = v.ifEmpty { null }) else it
                                        })
                                        opts = next
                                        actions.mapped(id, next.tunerChannels.count { it.providerChannelId.isNullOrEmpty() })
                                    }
                                    .onFailure { actions.say(it.message ?: t("Couldn’t map that channel.")) }
                            }
                        },
                        { picking = null },
                        searchLabel = t("Search channels"),
                    )
                }
            }
        },
    ) {
        val o = opts
        when {
            error != null -> SText(error!!, Modifier.padding(20.dp), size = 13.sp, color = SeedyColors.Danger)
            o == null -> LoadingCards(1, 300.dp)
            o.tunerChannels.isEmpty() -> SText(t("No channels yet. Add a tuner, then refresh the guide data."), Modifier.padding(20.dp), size = 13.sp, color = SeedyColors.Text2)
            else -> {
                if (o.tunerChannels.size > 8) BoardInput(q, { q = it }, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), placeholder = t("Search channels"))
                val shown = o.tunerChannels.filter { q.isBlank() || it.name.contains(q.trim(), ignoreCase = true) }
                Section(null) {
                    for (c in shown) {
                        row {
                            PickRow(
                                c.name,
                                c.providerChannelName?.let { t("Shows {name}", "name" to it) } ?: t("No listings"),
                                c.providerChannelName ?: if (c.providerChannelId.isNullOrEmpty()) t("Not mapped") else c.providerChannelId.orEmpty(),
                                valueColor = if (c.providerChannelId.isNullOrEmpty()) SeedyColors.Pending else SeedyColors.TextHero,
                            ) { picking = c }
                        }
                    }
                }
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 40.dp))
    }
}
