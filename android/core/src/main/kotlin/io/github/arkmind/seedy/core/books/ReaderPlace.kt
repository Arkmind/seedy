package io.github.arkmind.seedy.core.books

import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Where the reader is in a book, and how the reader's chrome says it (web/src/screens/books/reader/epub.tsx and
 * comic.tsx): "Page 190 of 412 · 46%", "6 pages left in chapter", "Chapter 12 · Frost".
 */

/** A chapter of an EPUB: the server's list (same pages as the details page), else the book's own contents. */
data class TocEntry(val title: String, val href: String, val page: Int)

/** Where epub.js shows the book: the first visible place as a CFI, its spine index and page (from 1). */
data class EpubPos(val cfi: String, val index: Int, val page: Int, val atStart: Boolean, val atEnd: Boolean)

data class EpubWhere(
    val page: Int,
    val total: Int,
    /** In [toc], or -1 before the first chapter. */
    val chapterIndex: Int,
    val chapter: TocEntry?,
    val nextChapterPage: Int,
    val fraction: Double,
    /** One decimal, 100 at the end. */
    val percent: Double,
)

fun epubWhere(pos: EpubPos?, total: Int, toc: List<TocEntry>): EpubWhere {
    val page = pos?.page ?: 0
    val ci = toc.indexOfLast { it.page <= page }
    val chapter = toc.getOrNull(ci)
    val next = if (ci + 1 < toc.size) toc[ci + 1].page else total + 1
    val fraction = if (total > 0) min(1.0, page.toDouble() / total) else 0.0
    val percent = if (pos?.atEnd == true) 100.0 else (fraction * 1000).roundToInt() / 10.0
    return EpubWhere(page, total, ci, chapter, next, fraction, percent)
}

private val Numbered = Regex("^(chapter|chapitre|part|partie|book|livre|prologue|epilogue|épilogue)\\b|^\\d", RegexOption.IGNORE_CASE)

/** "Chapter 12 · Frost" — or the title alone when it already says it ("Chapter 3", "Prologue", "12. Frost"). */
fun chapterName(c: TocEntry, index: Int): String =
    if (Numbered.containsMatchIn(c.title)) c.title else t("Chapter {n} · {title}", "n" to index + 1, "title" to c.title)

/** The footer's two texts for an EPUB: the page and percent, and what's left of the chapter (or the book). */
fun epubStatus(w: EpubWhere, atEnd: Boolean, minutes: Int): Pair<String, String> {
    if (w.page == 0 || w.total == 0) return "" to ""
    val left = t("Page {n} of {total} · {percent}%", "n" to w.page, "total" to w.total, "percent" to (w.fraction * 100).roundToInt())
    val right = when {
        w.chapter != null -> {
            val n = max(1, w.nextChapterPage - w.page)
            if (n <= 1) t("Last page in chapter") else tn(n, "{n} page left in chapter", "{n} pages left in chapter")
        }
        minutes > 0 -> if (atEnd) t("The end of the book") else leftInBook(minutes * (1 - (w.page - 1).toDouble() / w.total))
        else -> ""
    }
    return left to right
}

private fun leftInBook(minutes: Double): String =
    if (minutes < 5) t("A few minutes left in the book") else t("About {time} left in the book", "time" to tenMinutes(minutes))

/** Rounded to ten minutes: "8 h 10 min", "8 h", "40 min" (the web's tenMinutes). */
private fun tenMinutes(minutes: Double): String {
    val m = max(0, (minutes / 10).roundToInt() * 10)
    if (m == 0) return t("a few minutes")
    val h = m / 60
    val rest = m % 60
    return when {
        h == 0 -> "$rest min"
        rest == 0 -> "$h h"
        else -> "$h h ${rest.toString().padStart(2, '0')} min"
    }
}

/** The footer's texts for comics and PDFs (one page at a time on a phone). */
fun pagedStatus(index: Int, count: Int): Pair<String, String> {
    if (count <= 0) return "" to ""
    val n = index + 1
    val left = t("Page {n} of {total} · {percent}%", "n" to n, "total" to count, "percent" to (n * 100.0 / count).roundToInt())
    val rest = count - n
    return left to (if (rest > 0) tn(rest, "{n} page left", "{n} pages left") else t("Last page"))
}

/** The same work: an audiobook and the ebook it was read from (the web's workKey: title and authors, loosely). */
fun sameWork(nameA: String?, authorA: String?, nameB: String?, authorB: String?): Boolean =
    normalizeTitle(nameA).isNotEmpty() && normalizeTitle(nameA) == normalizeTitle(nameB) && normalizeTitle(authorA) == normalizeTitle(authorB)

private fun normalizeTitle(s: String?): String =
    s.orEmpty().lowercase().replace(Regex("^(the|a|an)\\s+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), "")
