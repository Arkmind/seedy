package io.github.arkmind.seedy.mobile.books

import androidx.compose.runtime.Composable
import io.github.arkmind.seedy.ui.components.NotYet
import java.util.UUID

/** Board "Android · Books": a books library's All, Ebooks, Audiobooks, Comics and Series. */
@Composable
fun BooksRoute(id: UUID, view: String? = null, focus: String? = null) = BooksLibraryRoute(id, view, focus)

/** Board "Android · Book details": a book or an audiobook. */
@Composable
fun BookRoute(id: UUID) = BookDetailsRoute(id)

/** Board "Android · Reader": EPUB, comics and PDF. */
@Composable
fun ReaderRoute(id: UUID) = NotYet("Reader")

/** Board "Android · Audiobook": the full-screen audiobook player. */
@Composable
fun AudiobookPlayerRoute() = AudiobookRoute()
