package io.github.arkmind.seedy.mobile.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.arkmind.seedy.core.SeedyCore
import io.github.arkmind.seedy.core.data.AppData
import io.github.arkmind.seedy.core.data.SavedServer
import io.github.arkmind.seedy.core.feature.seasonList
import io.github.arkmind.seedy.core.format.episodeTag
import io.github.arkmind.seedy.core.format.uuidOf
import io.github.arkmind.seedy.core.i18n.I18n
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.mediarequests.MediaRequest
import io.github.arkmind.seedy.core.mediarequests.MediaRequestsApi
import io.github.arkmind.seedy.core.notify.Change
import io.github.arkmind.seedy.core.notify.CoreOwner
import io.github.arkmind.seedy.core.notify.RequestWatch
import io.github.arkmind.seedy.core.notify.Stage
import io.github.arkmind.seedy.core.notify.Watched
import io.github.arkmind.seedy.core.requests.RequestsApi
import io.github.arkmind.seedy.core.requests.SeerrRequest
import io.github.arkmind.seedy.core.seedy.SeedyApi
import io.github.arkmind.seedy.core.seedy.SeedyMe
import io.github.arkmind.seedy.mobile.R
import io.github.arkmind.seedy.ui.components.MeduMood
import io.github.arkmind.seedy.ui.components.meduBitmap
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.parseHexColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * "Tell me when a request is ready" (Settings · Profile, this phone): every 15 minutes while online, and when the app
 * comes back, Seedy looks at each signed-in profile's requests (Seerr's, and music and books when the server notifies
 * about those) and says when one is approved or ready (board "Android · Notification shade"). What each request was at
 * the last look is kept per server and profile; the very first look only records it.
 */
object RequestAlerts {
    const val Channel = "requests"
    private const val Tag = "seedy-requests"
    private const val Group = "io.github.arkmind.seedy.REQUESTS"
    private const val Prefs = "seedy.requests"

    /** One look at a time (the periodic check and the app's own may meet). */
    private val lock = Mutex()

    private fun workName(server: String, user: String) = "requests:$server:$user"

    /**
     * Keeps a 15-minute check for each profile signed in on this phone (stay signed in) while the setting is [on];
     * profiles signed out, or the setting off, stop theirs. Turning it off also forgets what was seen, so turning it
     * back on starts with a quiet first look.
     */
    suspend fun schedule(context: Context, data: AppData, on: Boolean) = withContext(Dispatchers.IO) {
        val wm = WorkManager.getInstance(context)
        val accounts = if (on) data.servers.flatMap { s -> s.accounts.filter { it.token != null }.map { s.id to it.userId } } else emptyList()
        for ((server, user) in accounts) {
            val request = PeriodicWorkRequestBuilder<RequestsWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf("server" to server, "user" to user))
                .addTag(Tag)
                .addTag("name:" + workName(server, user))
                .build()
            wm.enqueueUniquePeriodicWork(workName(server, user), ExistingPeriodicWorkPolicy.KEEP, request)
        }
        val keep = accounts.map { workName(it.first, it.second) }.toSet()
        val infos = runCatching { wm.getWorkInfosByTag(Tag).get() }.getOrDefault(emptyList())
        for (info in infos) {
            val name = info.tags.firstOrNull { it.startsWith("name:") }?.removePrefix("name:") ?: continue
            if (name !in keep && !info.state.isFinished) wm.cancelUniqueWork(name)
        }
        if (!on) context.getSharedPreferences(Prefs, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Whether Android lets Seedy post (Android 13 asks the person first). */
    fun allowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Looks at the profile's requests ([api] is signed in as them) and notifies what moved on since the last look. */
    suspend fun check(context: Context, core: SeedyCore, server: SavedServer, userId: String, api: ApiClient) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!core.device.current.notifyRequests) return@withLock
            val me = runCatching { SeedyApi(api).me() }.getOrNull() ?: return@withLock
            val media = me.media?.takeIf { (it.music || it.books) && it.notifyOnReady }
            val seerr = if (me.requests.connected) runCatching { RequestsApi(api).list("mine", take = 100).results }.getOrNull() else emptyList()
            val mediaList = if (media != null) runCatching { MediaRequestsApi(api).list("mine").results }.getOrNull() else emptyList()
            // One of them didn't answer: look again next time rather than forget its requests.
            if (seerr == null || mediaList == null) return@withLock
            val now = seerr.map { Watched(RequestWatch.keyOf(it), RequestWatch.stageOf(it)) } +
                mediaList.map { Watched(RequestWatch.keyOf(it), RequestWatch.stageOf(it)) }
            val prefs = context.getSharedPreferences(Prefs, Context.MODE_PRIVATE)
            val key = "${server.id}:$userId"
            val changes = RequestWatch.diff(RequestWatch.decode(prefs.getString(key, null)), now)
            prefs.edit().putString(key, RequestWatch.encode(RequestWatch.remember(now))).commit()
            if (changes.isEmpty() || !allowed(context)) return@withLock
            val seerrBy = seerr.associateBy(RequestWatch::keyOf)
            val mediaBy = mediaList.associateBy(RequestWatch::keyOf)
            val ctx = Ctx(context, server.id, userId, api, accentOf(me))
            val notes = changes.mapNotNull { c ->
                seerrBy[c.key]?.let { runCatching { ctx.seerrNote(it, c) }.getOrNull() }
                    ?: mediaBy[c.key]?.let { ctx.mediaNote(it, c) }
            }
            post(context, ctx, notes)
        }
    }

    /** The profile's accent (Settings · Display) over the server's, for the notification's tint and Medu. */
    private fun accentOf(me: SeedyMe) =
        parseHexColor((me.prefs.custom["display.accent"] as? JsonPrimitive)?.content) ?: parseHexColor(me.branding.accent) ?: SeedyColors.Accent

    private class Note(
        val id: Int,
        val title: String,
        val text: String,
        val tap: RequestLink,
        val actions: List<Pair<String, RequestLink>> = emptyList(),
        val icon: Bitmap? = null,
        val medu: Boolean = false,
    )

    private class Ctx(val context: Context, val server: String, val user: String, val api: ApiClient, val accent: androidx.compose.ui.graphics.Color) {
        val userId: UUID? = uuidOf(user)

        fun link(action: String, itemId: String? = null, type: String? = null, tmdb: Int? = null) = RequestLink(server, user, action, itemId, type, tmdb)

        fun idOf(change: Change) = "$server:$user:${change.key}".hashCode()

        /** Which library a title is in ("Shows"). */
        suspend fun libraryOf(id: UUID): String? = runCatching {
            api.libraryApi.getAncestors(itemId = id, userId = userId).content.firstOrNull { it.type == BaseItemKind.COLLECTION_FOLDER }?.name
        }.getOrNull()

        suspend fun seerrNote(r: SeerrRequest, c: Change): Note {
            val itemId = uuidOf(r.jellyfinItemId)
            val requests = link(RequestLink.Requests)
            val open = if (itemId != null) link(RequestLink.Open, itemId.toString()) else link(RequestLink.Open, type = r.mediaType, tmdb = r.tmdbId)
            val tv = r.mediaType == "tv"
            val seasons = r.seasons.orEmpty().sorted()
            if (c.to == Stage.Approved) {
                val what = if (tv) seasons.takeIf { it.isNotEmpty() }?.let(::seasonList) ?: t("All seasons") else t("Movie")
                return Note(
                    idOf(c), t("Request approved"),
                    t("{title} · {what}. {service} is looking for it now.", "title" to r.title, "what" to what, "service" to if (tv) "Sonarr" else "Radarr"),
                    requests,
                )
            }
            if (!tv) {
                val actions = buildList {
                    if (itemId != null) add(t("Play") to link(RequestLink.Play, itemId.toString()))
                    add(t("Details") to open)
                }
                return Note(idOf(c), t("{title} is ready", "title" to r.title), t("The movie you asked for is on the server."), open, actions, backdropOf(itemId))
            }
            val library = itemId?.let { libraryOf(it) }
            val text = when {
                library != null && seasons.size == 1 -> t("Season {n}, which you asked for, is in {library} now.", "n" to seasons[0], "library" to library)
                library != null && seasons.size > 1 -> t("{seasons}, which you asked for, are in {library} now.", "seasons" to seasonList(seasons), "library" to library)
                else -> t("The show you asked for is on the server.")
            }
            // "Play S3 E1": the first episode of the first season asked for.
            val first = itemId?.let { id ->
                runCatching {
                    api.showApi.getEpisodes(seriesId = id, userId = userId, season = seasons.firstOrNull(), isMissing = false, limit = 1).content.items.firstOrNull()
                }.getOrNull()
            }
            val actions = buildList {
                if (first != null) add(t("Play {episode}", "episode" to first.episodeTag) to link(RequestLink.Play, first.id.toString()))
                add(t("See request") to requests)
            }
            return Note(idOf(c), t("{title} is ready to watch", "title" to r.title), text, open, actions, medu = true)
        }

        fun mediaNote(r: MediaRequest, c: Change): Note {
            val requests = link(RequestLink.Requests)
            if (c.to == Stage.Approved) {
                val what = when (r.mode) {
                    "artist" -> t("whole discography")
                    "future" -> t("new releases")
                    "albums" -> if (r.albums.size == 1) r.albums[0].title else tn(r.albums.size, "{n} album", "{n} albums")
                    else -> r.subtitle.orEmpty()
                }
                val service = when (r.service) {
                    "lidarr" -> "Lidarr"
                    "shelfarr" -> "Shelfarr"
                    "librarr" -> "Librarr"
                    "readarr" -> "Readarr"
                    else -> r.service.replaceFirstChar { it.uppercase() }
                }
                val text = if (what.isBlank()) t("{title}. {service} is looking for it now.", "title" to r.title, "service" to service)
                else t("{title} · {what}. {service} is looking for it now.", "title" to r.title, "what" to what, "service" to service)
                return Note(idOf(c), t("Request approved"), text, requests)
            }
            val item = uuidOf(r.itemId)?.toString()
            return Note(
                idOf(c), t("{title} is ready", "title" to r.title),
                if (r.music) t("The music you asked for is on the server.") else t("The book you asked for is on the server."),
                if (item != null) link(RequestLink.Open, item) else requests,
                listOf(t("See request") to requests),
                medu = true,
            )
        }

        /** A movie's backdrop for the notification's picture, when the server has one. */
        fun backdropOf(id: UUID?): Bitmap? = id?.let {
            runCatching {
                val url = api.imageApi.getItemImageUrl(itemId = it, imageType = ImageType.BACKDROP, maxWidth = 320)
                (URL(url).openConnection() as HttpURLConnection).run {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    try {
                        if (responseCode == 200) inputStream.use(BitmapFactory::decodeStream) else null
                    } finally {
                        disconnect()
                    }
                }
            }.getOrNull()
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(Channel) == null) {
            nm.createNotificationChannel(NotificationChannel(Channel, t("Requests"), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = t("When something you asked for is approved or arrives")
            })
        }
    }

    /** The group's summary goes once the last of its notifications has ([except]: the one just opened). */
    fun tidyGroup(context: Context, except: Int) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val active = runCatching { nm.activeNotifications.toList() }.getOrDefault(emptyList())
        val left = active.count { it.notification.group == Group && !NotificationCompat.isGroupSummary(it.notification) && it.id != except }
        if (left == 0) nm.cancel(Group.hashCode())
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun post(context: Context, ctx: Ctx, notes: List<Note>) {
        if (notes.isEmpty()) return
        ensureChannel(context)
        val nm = NotificationManagerCompat.from(context)
        val accent = ctx.accent.toArgb()
        // Medu, cheering (no glow), as the board's large icon.
        val medu by lazy { meduBitmap(MeduMood.Yay, 192, 192, ctx.accent).asAndroidBitmap() }
        for (n in notes) {
            val b = NotificationCompat.Builder(context, Channel)
                .setSmallIcon(R.drawable.ic_stat_seedy)
                .setColor(accent)
                .setContentTitle(n.title)
                .setContentText(n.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(n.text))
                .setContentIntent(RequestLinks.pendingIntent(context, n.tap, n.id, n.id))
                .setAutoCancel(true)
                .setGroup(Group)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            (n.icon ?: if (n.medu) medu else null)?.let(b::setLargeIcon)
            n.actions.forEachIndexed { i, (label, link) -> b.addAction(0, label, RequestLinks.pendingIntent(context, link, n.id * 31 + i + 1, n.id)) }
            nm.notify(n.id, b.build())
        }
        if (notes.size > 1) {
            val summary = NotificationCompat.Builder(context, Channel)
                .setSmallIcon(R.drawable.ic_stat_seedy)
                .setColor(accent)
                .setContentTitle(t("Your requests"))
                .setStyle(NotificationCompat.InboxStyle().also { s -> notes.forEach { s.addLine(it.title) } })
                .setGroup(Group)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .build()
            nm.notify(Group.hashCode(), summary)
        }
    }
}

/** The 15-minute check for one profile (the app keeps one per profile signed in on this phone). */
class RequestsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val core = (applicationContext as? CoreOwner)?.core ?: return Result.success()
        val serverId = inputData.getString("server") ?: return Result.success()
        val userId = inputData.getString("user") ?: return Result.success()
        val data = core.store.current()
        val server = data.servers.firstOrNull { it.id == serverId }
        val account = server?.account(userId)
        val token = account?.let { core.sessions.tokenFor(server, it) }
        if (server == null || account == null || token == null) return Result.success()
        // The worker may run without the app: the notification speaks the language the app showed last.
        val lang = I18n.resolve(data.lang)
        if (lang != I18n.lang) I18n.load(applicationContext, lang)
        RequestAlerts.check(applicationContext, core, server, userId, core.sessions.apiFor(server, account.deviceId, token))
        return Result.success()
    }
}
