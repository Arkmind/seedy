package io.github.arkmind.seedy.ui.icons

/**
 * The icons a release's "What's Changed" line can ask for with `<!-- icon:key -->` (board "Android · Update ready").
 * Keys are snake_case and name the picture, not the feature. scripts/release-notes.py reads the keys from this file to
 * check a release's text, so keep one `"key" to Icon,` entry per line.
 */
object ReleaseIcons {
    /** The board's: a big four-point star and a small one. */
    val Sparkles = IconDef(2.2f, "M12 3l1.8 4.6L18.5 9l-4.7 1.6L12 15l-1.8-4.4L5.5 9l4.7-1.4z", "M19 15l.8 2 2 .8-2 .8-.8 2-.8-2-2-.8 2-.8z")
    private val StarOutline = IconDef(2.2f, "M12 3.5l2.6 5.3 5.8.8-4.2 4.1 1 5.8L12 16.8l-5.2 2.7 1-5.8-4.2-4.1 5.8-.8z")

    val all: Map<String, IconDef> = mapOf(
        "audio" to WebIcons.Audio,
        "book" to WebIcons.Book,
        "cast" to WebIcons.Cast,
        "check" to WebIcons.Check,
        "clock" to WebIcons.Clock,
        "collection" to WebIcons.Collection,
        "download" to WebIcons.Download,
        "eye" to WebIcons.Eye,
        "film" to WebIcons.Film,
        "folder" to WebIcons.Folder,
        "globe" to WebIcons.Globe,
        "headphones" to WebIcons.Headphones,
        "heart" to WebIcons.Heart,
        "history" to WebIcons.History,
        "home" to WebIcons.Home,
        "image" to WebIcons.Image,
        "inbox" to WebIcons.Inbox,
        "info" to WebIcons.Info,
        "key" to WebIcons.Key,
        "laptop" to WebIcons.DeviceLaptop,
        "link" to WebIcons.Link,
        "live_tv" to AppIcons.LiveTv,
        "lock" to WebIcons.Lock,
        "mobile" to WebIcons.DevicePhone,
        "music" to WebIcons.Music,
        "pencil" to WebIcons.Pencil,
        "people" to WebIcons.Users,
        "person" to WebIcons.User,
        "pip" to WebIcons.Pip,
        "play_button" to SettingsIcons.Play,
        "playlist" to WebIcons.PlaylistAdd,
        "refresh" to WebIcons.Refresh,
        "request" to WebIcons.Request,
        "search" to WebIcons.Search,
        "server" to AppIcons.Server,
        "shield" to WebIcons.Shield,
        "shuffle" to WebIcons.Shuffle,
        "sliders" to WebIcons.Sliders,
        "sparkles" to Sparkles,
        "speed" to WebIcons.Speed,
        "star" to StarOutline,
        "subtitles" to WebIcons.Subtitles,
        "tablet" to WebIcons.DeviceTablet,
        "trash" to WebIcons.Trash,
        "tv" to WebIcons.DeviceTv,
        "volume" to WebIcons.Volume,
        "warning" to WebIcons.Warn,
        "watch_together" to WebIcons.WatchTogether,
    )

    /** The icon for [key]; sparkles for a key this version doesn't know. */
    operator fun get(key: String): IconDef = all[key] ?: Sparkles
}
