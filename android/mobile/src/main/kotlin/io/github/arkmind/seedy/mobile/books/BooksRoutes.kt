package io.github.arkmind.seedy.mobile.books

import androidx.compose.runtime.Composable
import io.github.arkmind.seedy.ui.components.NotYet
import java.util.UUID

/** Board "Android · Books": a books library's All, Ebooks, Audiobooks, Comics and Series. */
@Composable
fun BooksRoute(id: UUID) = NotYet("Books")

/** Board "Android · Book details": a book or an audiobook. */
@Composable
fun BookRoute(id: UUID) = NotYet("Book")

/** Board "Android · Audiobook": the full-screen audiobook player. */
@Composable
fun AudiobookPlayerRoute() = NotYet("Audiobook")
