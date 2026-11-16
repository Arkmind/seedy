package io.github.arkmind.seedy.core.remote

import io.github.arkmind.seedy.core.admin.people.DeviceKind
import io.github.arkmind.seedy.core.format.episodeTag
import io.github.arkmind.seedy.core.format.ticksToSeconds
import io.github.arkmind.seedy.core.format.year
import io.github.arkmind.seedy.core.i18n.t
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SessionInfoDto
import java.util.UUID

/** What a screen plays, as the remote shows it. Positions run on from [seenAt] while it plays. */
data class NowOn(
    val item: BaseItemDto,
    /** The episode's or the film's name: "The Pass", "Paper Lanterns". */
    val title: String,
    /** "Northbound · S3 E2" for an episode, the title otherwise. */
    val heading: String,
    /** "Movie · 2025", "Northbound · S3 E2", "Live TV". */
    val kicker: String,
    val positionMs: Long,
    val durationMs: Long,
    val paused: Boolean,
    val canSeek: Boolean = true,
    /** When [positionMs] was read, on the phone's elapsed-time clock. */
    val seenAt: Long = 0,
    val audio: List<MediaStream> = emptyList(),
    val subtitles: List<MediaStream> = emptyList(),
    val audioIndex: Int? = null,
    val subtitleIndex: Int? = null,
    /** The player's own volume, 0–100 (Seedy web's slider), when the screen reports one. */
    val volume: Int? = null,
    val muted: Boolean = false,
) {
    /** Where it is at [now]: still while paused, running on while it plays. */
    fun positionAt(now: Long): Long {
        val p = if (paused || seenAt == 0L) positionMs else positionMs + (now - seenAt).coerceAtLeast(0)
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }

    fun fractionAt(now: Long): Float = if (durationMs > 0) positionAt(now).toFloat() / durationMs else 0f
}

/** A screen where Seedy (or another Jellyfin app that takes commands) is open, signed in as someone this phone may control. */
data class RemoteScreen(
    val sessionId: String,
    val deviceId: String,
    val name: String,
    val kind: DeviceKind,
    /** "Android TV", "Seedy web in Chrome". */
    val app: String,
    /** The art's corner chip: "Seedy web · Chrome". */
    val chip: String,
    val now: NowOn? = null,
    /** The screen asks for text ("search": the TV's Search is open), with what's typed there. */
    val asking: String? = null,
    val text: String? = null,
    /** The title it has focused or shows. */
    val focusedId: UUID? = null,
    val fullscreen: Boolean = false,
    val miniPlayer: Boolean = false,
    val commands: Set<GeneralCommandType> = emptySet(),
    /** Someone named it in Jellyfin's Devices ("MacBook"): "the MacBook" reads right, "the Chrome on Linux" doesn't. */
    val named: Boolean = true,
) {
    /** The TV remote (touchpad, Back, Home, Type) or the computer's (what plays, its transport). */
    val isTv: Boolean get() = kind == DeviceKind.Tv
    val playing: Boolean get() = now != null && !now.paused
}

/** A device signed in to the profile without Seedy open on it ("Signed in, not open"). */
data class ClosedScreen(val id: String, val name: String, val kind: DeviceKind, val app: String)

/** What a screen tells its remotes through Seedy (GET /Seedy/Remote/States). */
@Serializable
data class ScreenState(
    val deviceId: String = "",
    val asking: String? = null,
    val text: String? = null,
    val itemId: String? = null,
    val fullscreen: Boolean = false,
    val miniPlayer: Boolean = false,
)

/** What this screen tells its remotes (POST /Seedy/Remote/State). */
@Serializable
data class ScreenReport(
    val asking: String? = null,
    val text: String? = null,
    val itemId: String? = null,
    val fullscreen: Boolean = false,
    val miniPlayer: Boolean = false,
)

private val Browser = Regex("\\b(Chrome|Chromium|Firefox|Safari|Edge|Opera|Vivaldi|Brave)\\b", RegexOption.IGNORE_CASE)
private val TvWords = Regex("\\btv\\b|android ?tv|fire ?tv|roku|shield|chromecast|apple ?tv|tvos|tizen|webos|kodi|xbox|playstation")
private val TabletWords = Regex("ipad|tablet|galaxy tab")
private val PhoneWords = Regex("iphone|ipod|mobile|phone|pixel|swiftfin|findroid|\\bios\\b|seedy for android$|jellyfin android$")

/** The four kinds the boards draw, from the app's and the device's names. */
fun screenKind(client: String, deviceName: String): DeviceKind {
    val app = client.lowercase()
    val all = "$app $deviceName".lowercase()
    return when {
        TvWords.containsMatchIn(app) -> DeviceKind.Tv
        TabletWords.containsMatchIn(all) -> DeviceKind.Tablet
        PhoneWords.containsMatchIn(app) -> DeviceKind.Phone
        TvWords.containsMatchIn(all) -> DeviceKind.Tv
        else -> DeviceKind.Computer
    }
}

private fun browserOf(deviceName: String): String? = Browser.find(deviceName)?.groupValues?.get(1)

/** "Android TV", "Seedy web in Chrome", "Jellyfin web in Firefox"; other apps by their name. */
fun screenApp(client: String, deviceName: String): String {
    val browser = browserOf(deviceName)
    return when (client) {
        "Seedy for Android TV" -> t("Android TV")
        "Seedy for Android" -> t("Seedy for Android")
        "Seedy" -> if (browser != null) t("Seedy web in {browser}", "browser" to browser) else t("Seedy web")
        "Jellyfin Web" -> if (browser != null) t("Jellyfin web in {browser}", "browser" to browser) else client
        else -> client
    }
}

/** The art's chip: "Seedy web · Chrome". */
fun screenChip(client: String, deviceName: String): String {
    val browser = browserOf(deviceName)
    return when (client) {
        "Seedy" -> listOfNotNull(t("Seedy web"), browser).joinToString(" · ")
        "Jellyfin Web" -> listOfNotNull("Jellyfin web", browser).joinToString(" · ")
        else -> screenApp(client, deviceName)
    }
}

/** What a session plays, read at [now]; null when it plays nothing. */
fun nowOn(s: SessionInfoDto, now: Long): NowOn? {
    val item = s.nowPlayingItem ?: return null
    val p = s.playState
    val streams = item.mediaStreams.orEmpty()
    val tag = item.episodeTag
    val (heading, kicker) = when (item.type) {
        BaseItemKind.EPISODE -> {
            val show = listOfNotNull(item.seriesName, tag.ifEmpty { null }).joinToString(" · ")
            show.ifEmpty { item.name.orEmpty() } to show
        }
        BaseItemKind.TV_CHANNEL -> item.name.orEmpty() to t("Live TV")
        BaseItemKind.MOVIE -> item.name.orEmpty() to listOfNotNull(t("Movie"), item.year?.toString()).joinToString(" · ")
        BaseItemKind.AUDIO -> item.name.orEmpty() to listOfNotNull(item.albumArtist ?: item.artists?.firstOrNull(), item.album).joinToString(" · ")
        else -> item.name.orEmpty() to listOfNotNull(item.year?.toString()).joinToString(" · ")
    }
    return NowOn(
        item = item,
        title = item.name.orEmpty(),
        heading = heading,
        kicker = kicker,
        positionMs = (ticksToSeconds(p?.positionTicks) * 1000).toLong(),
        durationMs = (ticksToSeconds(item.runTimeTicks) * 1000).toLong(),
        paused = p?.isPaused == true,
        canSeek = p?.canSeek != false,
        seenAt = now,
        audio = streams.filter { it.type == MediaStreamType.AUDIO },
        subtitles = streams.filter { it.type == MediaStreamType.SUBTITLE },
        audioIndex = p?.audioStreamIndex,
        subtitleIndex = p?.subtitleStreamIndex,
        volume = p?.volumeLevel,
        muted = p?.isMuted == true,
    )
}

/** A Jellyfin session as a screen, with what it told Seedy ([state]). */
fun remoteScreen(s: SessionInfoDto, state: ScreenState?, now: Long): RemoteScreen {
    val client = s.client.orEmpty()
    val device = s.deviceName.orEmpty()
    return RemoteScreen(
        sessionId = s.id.orEmpty(),
        deviceId = s.deviceId.orEmpty(),
        name = device.ifEmpty { client.ifEmpty { t("Screen") } },
        kind = screenKind(client, device),
        app = screenApp(client, device),
        chip = screenChip(client, device),
        now = nowOn(s, now),
        asking = state?.asking,
        text = state?.text,
        focusedId = state?.itemId?.let { runCatching { UUID.fromString(dashed(it)) }.getOrNull() },
        fullscreen = state?.fullscreen == true,
        miniPlayer = state?.miniPlayer == true,
        commands = s.supportedCommands.orEmpty().toSet(),
        named = s.hasCustomDeviceName,
    )
}

/** "a1b2…" (32 hex) → a UUID string. */
private fun dashed(id: String): String =
    if (id.length == 32 && '-' !in id) "${id.substring(0, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}" else id

/** The picker's line under a screen's name: "Android TV · Northbound · S3 E2", "Seedy web in Chrome · Paper Lanterns". */
fun pickerLine(s: RemoteScreen): String = listOfNotNull(s.app.ifEmpty { null }, s.now?.heading?.ifEmpty { null }).joinToString(" · ")

/** The track's name in the remote's lists: its title, else its language and format. */
fun trackName(st: MediaStream): String =
    st.displayTitle?.takeIf { it.isNotBlank() } ?: listOfNotNull(st.language?.uppercase(), st.codec?.uppercase()).joinToString(" · ").ifEmpty { t("Track {n}", "n" to st.index) }
