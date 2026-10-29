package io.github.arkmind.seedy.core.books

import io.github.arkmind.seedy.core.audio.Reading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Keeps this profile's place in a book on the server (web/src/screens/books/reader/saver.ts): a save a moment after the
 * page stops moving, the settings with it when they changed, and a last save when the reader closes or the app goes
 * to the background. Called on the main thread.
 */
class ReadingSaver(private val scope: CoroutineScope, private val save: suspend (Reading) -> Unit) {
    private var pending: Reading? = null
    private var settings: JsonObject? = null
    private var timer: Job? = null
    private var last = ""

    private fun body(): Reading? {
        val p = pending ?: return null
        return settings?.let { p.copy(settings = it) } ?: p
    }

    /** The place moved. */
    fun place(p: Reading, delayMs: Long = 1500) {
        pending = p.copy(settings = null)
        schedule(delayMs)
    }

    /** The settings changed: saved with the place they were changed at (the server needs both). */
    fun settings(s: JsonObject, place: Reading?) {
        settings = s
        if (pending == null && place != null) pending = place.copy(settings = null)
        schedule(800)
    }

    /** Saves now; [exit] when the reader is going away (the save then outlives it). */
    fun flush(exit: Boolean = false) {
        timer?.cancel()
        val b = body() ?: return
        val key = b.toString()
        if (key == last) return
        last = key
        settings = null
        (if (exit) Exit else scope).launch {
            runCatching { save(b) }.onFailure { if (!exit) last = "" }
        }
    }

    private fun schedule(ms: Long) {
        timer?.cancel()
        timer = scope.launch {
            delay(ms)
            flush()
        }
    }

    private companion object {
        /** Last saves run here, past the reader's own scope (the web's keepalive fetch). */
        val Exit = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
