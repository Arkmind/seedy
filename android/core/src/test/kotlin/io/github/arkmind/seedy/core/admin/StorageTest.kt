package io.github.arkmind.seedy.core.admin

import io.github.arkmind.seedy.core.manage.DeleteGroup
import io.github.arkmind.seedy.core.manage.DeleteItem
import io.github.arkmind.seedy.core.manage.DeletePreview
import io.github.arkmind.seedy.core.manage.DeleteViewModel
import io.github.arkmind.seedy.core.manage.SeerrMedia
import org.junit.Assert.assertEquals
import org.junit.Test

/** The hub's disks named and grouped as the web's Server · Overview does them, and the delete sheet's wording. */
class StorageTest {
    private val tib = 1_099_511_627_776L
    private val gib = 1_073_741_824L

    private fun f(path: String, mount: String, used: Long, total: Long, type: String = "Fixed") = FolderStorage(path, total - used, used, type, mount)

    /** The dev server's answer: config and cache on their own mounts, libraries under /media. */
    private val dev = run {
        val config = { p: String -> f(p, "/config", 400 * gib, 1007 * gib) }
        val cache = { p: String -> f(p, "/cache", 400 * gib, 1007 * gib) }
        SystemStorage(
            programDataFolder = config("/config"), internalMetadataFolder = config("/config/metadata"), logFolder = config("/config/log"),
            cacheFolder = cache("/cache"), imageCacheFolder = cache("/cache/images"), transcodingTempFolder = cache("/cache/transcodes"),
            webFolder = f("/jellyfin/jellyfin-web", "/", 181 * gib, 1007 * gib),
            libraries = listOf(
                LibraryStorage("r", "Recordings", listOf(config("/config/data/livetv/recordings"))),
                LibraryStorage("m", "Movies", listOf(f("/media/Movies", "/media", 400 * gib, 1007 * gib))),
                LibraryStorage("s", "Shows", listOf(f("/media/Shows", "/media", 400 * gib, 1007 * gib))),
            ),
        )
    }

    @Test
    fun devServer() {
        val d = disks(dev)
        assertEquals(listOf("Server data", "Media libraries", "Cache", "Web client"), d.map { it.name })
        assertEquals("Program data, Metadata, Logs, Recordings", d[0].holds)
        assertEquals("Movies, Shows", d[1].holds)
        // The cache holds transcodes too: still "Cache", and every folder shows, the mount's own one first.
        assertEquals("Cache, Image cache, Transcodes", d[2].holds)
        assertEquals("Web files", d[3].holds)
        assertEquals(40, d[0].pct)
    }

    @Test
    fun boardDisks() {
        val config = { p: String -> f(p, "/config", (10.9 * tib).toLong(), (12.5 * tib).toLong()) }
        val s = SystemStorage(
            programDataFolder = config("/config/data"), internalMetadataFolder = config("/config/data/metadata"), cacheFolder = config("/config/cache"),
            imageCacheFolder = config("/config/cache/images"), logFolder = config("/config/log"),
            transcodingTempFolder = f("/config/cache/transcodes", "/config/cache/transcodes", (2.6 * gib).toLong() + 1, 8 * gib, "Ram"),
            webFolder = f("/usr/share/jellyfin/web", "/", 242 * gib, (1.9 * tib).toLong()),
            libraries = listOf("Movies", "Shows").map { LibraryStorage(it, it, listOf(f("/media/" + it.lowercase(), "/media", (25.6 * tib).toLong(), 40 * tib))) },
        )
        val d = disks(s)
        assertEquals(listOf("Server data", "Media libraries", "Transcodes", "Web client"), d.map { it.name })
        assertEquals(listOf(87, 64, 33, 12), d.map { it.pct })
        // A RAM disk that's just the transcodes folder: its tag, no folders.
        assertEquals("RAM disk, emptied on restart", d[2].holds)
        assertEquals(true, d[2].ram)
        assertEquals(listOf("1.6 TiB", "10.9 TiB", "12.5 TiB"), listOf(diskSize(d[0].free), diskSize(d[0].used), diskSize(d[0].total)))
        assertEquals("242 GiB", diskSize(d[3].used))
        assertEquals(Tone.Pending, d[0].level)
        assertEquals("1.6 TiB left on /config, where metadata and the cache live", diskNotice(d[0]))
        assertEquals("14.4 TiB left on /media, where Movies and Shows live", diskNotice(d[1]))
    }

    @Test
    fun oneLibraryDiskIsNamedAfterIt() {
        val s = SystemStorage(
            programDataFolder = f("/config", "/config", 1, 10),
            libraries = listOf(LibraryStorage("m", "Movies", listOf(f("/mnt/a", "/mnt/a", 96, 100), f("/mnt/a/more", "/mnt/a", 96, 100)))),
        )
        val d = disks(s)
        assertEquals(listOf("Server data", "Movies"), d.map { it.name })
        assertEquals("Movies", d[1].holds)
        assertEquals(Tone.Danger, d[1].level)
        assertEquals("4 B left on /mnt/a, where Movies live", diskNotice(d[1]))
    }

    @Test
    fun deleteWording() {
        val p = DeletePreview(
            listOf(
                DeleteItem(
                    "n", "Northbound", "Series", true, 252_200_000_000, listOf("/media/shows/Northbound"),
                    listOf(
                        DeleteGroup("season", "1", "Season 1", 1, 10, 10, 214_000_000_000, 2160, "hevc"),
                        DeleteGroup("season", "3", "Season 3", 3, 2, 2, 38_000_000_000, 2160, "hevc"),
                        DeleteGroup("extras", files = 46, bytes = 200_000_000),
                    ),
                    SeerrMedia("tv", 1, "Sonarr", true),
                ),
            ),
            252_200_000_000,
        )
        val s = DeleteViewModel.describe(p, continuing = true)
        assertEquals("Delete Northbound?", s.title)
        assertEquals("2 seasons · 252 GB", s.sub)
        assertEquals(listOf("10 episodes · 2160p HEVC", "2 episodes so far · 2160p HEVC", "46 files"), s.lines.map { it.sub })
        assertEquals(listOf("214 GB", "38 GB", "0.2 GB"), s.lines.map { it.size })
        assertEquals("Delete 252 GB", s.button)
        assertEquals(true, s.seerr)
        assertEquals("Sonarr still monitors Northbound, so it may download it again. Unmonitor it in Sonarr if you want it gone for good.", s.warning)
    }
}
