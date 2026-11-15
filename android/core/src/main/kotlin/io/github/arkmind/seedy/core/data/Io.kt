package io.github.arkmind.seedy.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Runs [block] on the IO dispatcher (network and disk). */
suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

/** Runs [block] on the IO dispatcher; null when it fails. */
suspend fun <T> ioOrNull(block: suspend () -> T): T? = withContext(Dispatchers.IO) { runCatching { block() }.getOrNull() }
