package io.github.arkmind.seedy.core.books

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/*
 * Reading settings (Book reader board, web/src/screens/books/reader/settings.ts): saved with the place in each book, so
 * they follow the person to every device. The JSON is the web's, key for key; keys this app doesn't use (the web's
 * "two pages") are kept as they were. A book opened for the first time starts from the settings last used on this phone.
 */

/** The page colour, and the chrome's with it. */
enum class PageTheme(val key: String, val label: String) {
    Dark("dark", "Dark"), Black("black", "Black"), Sepia("sepia", "Sepia"), Light("light", "Light");

    /** Dark ink on a pale page. */
    val pale: Boolean get() = this == Sepia || this == Light
}

enum class FontChoice(val key: String) { Serif("serif"), Sans("sans"), Book("book") }
enum class Spacing(val key: String) { Tight("tight"), Normal("normal"), Airy("airy") }
enum class Margins(val key: String) { Narrow("narrow"), Normal("normal"), Wide("wide") }

/** "two" is the web's two pages side by side; a phone shows one. */
enum class ReaderLayout(val key: String) { One("one"), Two("two"), Scroll("scroll") }
enum class Fit(val key: String) { Screen("screen"), Width("width") }
enum class Direction(val key: String) { Ltr("ltr"), Rtl("rtl") }


data class EpubSettings(
    val theme: PageTheme = PageTheme.Dark,
    val font: FontChoice = FontChoice.Serif,
    /** Text size in CSS pixels (dp here). */
    val size: Int = DEFAULT_SIZE,
    val spacing: Spacing = Spacing.Normal,
    val margins: Margins = Margins.Normal,
    val layout: ReaderLayout = ReaderLayout.One,
    val bookmarks: List<Bookmark> = emptyList(),
) {
    val scroll: Boolean get() = layout == ReaderLayout.Scroll

    companion object {
        const val SIZE_MIN = 14
        const val SIZE_MAX = 30

        /** The Android · Reader board's 17 px text (the web's desktop column starts at 19). */
        const val DEFAULT_SIZE = 17
    }
}

data class ComicSettings(
    val theme: PageTheme = PageTheme.Black,
    val layout: ReaderLayout = ReaderLayout.One,
    val fit: Fit = Fit.Screen,
    val direction: Direction = Direction.Ltr,
) {
    val rtl: Boolean get() = direction == Direction.Rtl
    val scroll: Boolean get() = layout == ReaderLayout.Scroll
}

private inline fun <reified E : Enum<E>> pick(v: JsonElement?, key: (E) -> String, default: E): E {
    val s = (v as? JsonPrimitive)?.contentOrNull ?: return default
    return enumValues<E>().firstOrNull { key(it) == s } ?: default
}

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The web's readEpubSettings: this phone's last settings, then the book's own (bookmarks only ever come from the book). */
fun readEpubSettings(saved: JsonObject?, device: JsonObject?): EpubSettings {
    val src = JsonObject((device ?: JsonObject(emptyMap())) + (saved ?: JsonObject(emptyMap())))
    val size = (src["size"] as? JsonPrimitive)?.doubleOrNull?.toInt()?.takeIf { it > 0 } ?: EpubSettings.DEFAULT_SIZE
    val marks = (saved?.get("bookmarks") as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val cfi = o.str("cfi") ?: return@mapNotNull null
        Bookmark(
            cfi = cfi,
            page = (o["page"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0,
            label = o.str("label").orEmpty(),
            at = (o["at"] as? JsonPrimitive)?.longOrNull ?: 0L,
        )
    }
    return EpubSettings(
        theme = pick(src["theme"], PageTheme::key, PageTheme.Dark),
        font = pick(src["font"], FontChoice::key, FontChoice.Serif),
        size = size.coerceIn(EpubSettings.SIZE_MIN, EpubSettings.SIZE_MAX),
        spacing = pick(src["spacing"], Spacing::key, Spacing.Normal),
        margins = pick(src["margins"], Margins::key, Margins.Normal),
        layout = pick(src["layout"], ReaderLayout::key, ReaderLayout.One),
        bookmarks = marks,
    )
}

/** The JSON saved with the place: the web's keys over whatever else the book's settings held. */
fun EpubSettings.toJson(base: JsonObject? = null): JsonObject = JsonObject(
    (base ?: JsonObject(emptyMap())) + buildJsonObject {
        put("theme", theme.key)
        put("font", font.key)
        put("size", size)
        put("spacing", spacing.key)
        put("margins", margins.key)
        put("layout", layout.key)
        put("bookmarks", buildJsonArray {
            bookmarks.forEach { b ->
                add(buildJsonObject { put("cfi", b.cfi); put("page", b.page); put("label", b.label); put("at", b.at) })
            }
        })
    },
)

fun readComicSettings(saved: JsonObject?, device: JsonObject?): ComicSettings {
    val src = JsonObject((device ?: JsonObject(emptyMap())) + (saved ?: JsonObject(emptyMap())))
    return ComicSettings(
        theme = pick(src["theme"], PageTheme::key, PageTheme.Black),
        layout = pick(src["layout"], ReaderLayout::key, ReaderLayout.One),
        fit = pick(src["fit"], Fit::key, Fit.Screen),
        direction = pick(src["direction"], Direction::key, Direction.Ltr),
    )
}

fun ComicSettings.toJson(base: JsonObject? = null): JsonObject = JsonObject(
    (base ?: JsonObject(emptyMap())) + buildJsonObject {
        put("theme", theme.key)
        put("layout", layout.key)
        put("fit", fit.key)
        put("direction", direction.key)
    },
)

/** The settings last used on this phone (the web keeps them in the browser as seedy.reader.epub / .comic). */
class ReaderPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("seedy.reader", Context.MODE_PRIVATE)

    fun epub(): JsonObject? = read("epub")
    fun comic(): JsonObject? = read("comic")

    fun rememberEpub(s: EpubSettings) = write("epub", JsonObject(s.toJson() - "bookmarks"))
    fun rememberComic(s: ComicSettings) = write("comic", s.toJson())

    private fun read(key: String): JsonObject? =
        prefs.getString(key, null)?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }

    private fun write(key: String, value: JsonObject) {
        prefs.edit().putString(key, value.toString()).apply()
    }
}
