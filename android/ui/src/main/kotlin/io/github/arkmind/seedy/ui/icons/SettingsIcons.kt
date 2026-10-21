package io.github.arkmind.seedy.ui.icons

/** Icons of the settings boards (phone and TV), drawn on the boards' 24 px grid. */
object SettingsIcons {
    val User = IconDef(2.1f, "M12 4a4 4 0 1 1 0 8a4 4 0 1 1 0-8z", "M4 21a8 8 0 0 1 16 0")
    val Home = IconDef(2.1f, "M4 11l8-7 8 7v9a1 1 0 0 1-1 1h-5v-6h-4v6H5a1 1 0 0 1-1-1z")
    val Play = IconDef(2.1f, "M7 4.5v15l12-7.5z")
    val Subtitles = IconDef(2.1f, "M6 5h12a3 3 0 0 1 3 3v8a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V8a3 3 0 0 1 3-3z", "M7 11h3M13 11h4", "M7 15h6M15.5 15H17")
    val Display = IconDef(2.1f, "M5 4h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M8 20h8", "M12 16v4")
    val Gesture = IconDef(
        2.1f, "M9 11V5.5a1.5 1.5 0 0 1 3 0V11", "M12 10V8.5a1.5 1.5 0 0 1 3 0V11",
        "M15 10.5a1.5 1.5 0 0 1 3 0V15a6 6 0 0 1-6 6h-1a6 6 0 0 1-4.7-2.3L4 15.5a1.6 1.6 0 0 1 2.4-2L9 16",
    )
    val History = IconDef(2.1f, "M12 3.5a8.5 8.5 0 1 1 0 17a8.5 8.5 0 1 1 0-17z", "M12 7.5V12l3 2")
    val Requests = IconDef(2.1f, "M12 3v10", "M8 9l4 4 4-4", "M4 14v4a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-4")
    val Admin = IconDef(2.1f, "M12 3l7 3v5c0 4.5-3 8.5-7 10-4-1.5-7-5.5-7-10V6z", "M9 12l2 2 4-4")
    val Info = IconDef(2.1f, "M12 3.5a8.5 8.5 0 1 1 0 17a8.5 8.5 0 1 1 0-17z", "M12 11v5", "M12 8h.01")
    val Remote = IconDef(2f, "M10 2.5h4a3 3 0 0 1 3 3v13a3 3 0 0 1-3 3h-4a3 3 0 0 1-3-3v-13a3 3 0 0 1 3-3z", "M12 7.5h.01", "M12 12h.01M12 15h.01")
    val Switch = IconDef(2.4f, "M7 7h12l-3-3", "M17 17H5l3 3")
    val Phone = IconDef(2.2f, "M9.5 2.5h5a2.5 2.5 0 0 1 2.5 2.5v14a2.5 2.5 0 0 1-2.5 2.5h-5a2.5 2.5 0 0 1-2.5-2.5v-14a2.5 2.5 0 0 1 2.5-2.5Z", "M11 18h2")
    val SignOut = IconDef(2.2f, "M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3", "M10 17l-5-5 5-5", "M5 12h11")
    /** Home screen: a row's grip (two lines). */
    val Grip = IconDef(2.2f, "M5 9h14", "M5 15h14")
    /** Home screen: a pinned row. */
    val Pin = IconDef(1.6f, listOf(IconPart("M9 3.5h6l-1 6 3 3v2H7v-2l3-3z", filled = true), IconPart("M12 14.5v6")))
    /** TV Home screen: the row being moved. */
    val Move = IconDef(2.4f, "M12 4v16", "M7 9l5-5 5 5", "M7 15l5 5 5-5")
    val ArrowLeft = IconDef(2.6f, "M15 6l-6 6 6 6")
    val ArrowRight = IconDef(2.6f, "M9 6l6 6-6 6")
}
