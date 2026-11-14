package io.github.arkmind.seedy.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Trying again in 12 s": counts down from [every] seconds and runs [run] at zero, over and over. [now] runs it
 * straight away (Try again) and starts the count again.
 */
class RetryClock internal constructor(private val every: Int, private val scope: CoroutineScope, private val run: suspend () -> Unit) {
    /** Seconds until the next try. */
    var left by mutableIntStateOf(every)
        private set

    /** A try is under way. */
    var running by mutableStateOf(false)
        private set

    fun now() {
        if (running) return
        scope.launch {
            running = true
            try {
                run()
            } finally {
                running = false
                left = every
            }
        }
    }

    internal suspend fun tick() {
        while (true) {
            delay(1000)
            if (!running && --left <= 0) now()
        }
    }
}

/** A [RetryClock] that ticks while this is on screen. */
@Composable
fun rememberRetryClock(every: Int = 15, run: suspend () -> Unit): RetryClock {
    val scope = rememberCoroutineScope()
    val clock = remember { RetryClock(every, scope, run) }
    LaunchedEffect(clock) { clock.tick() }
    return clock
}
