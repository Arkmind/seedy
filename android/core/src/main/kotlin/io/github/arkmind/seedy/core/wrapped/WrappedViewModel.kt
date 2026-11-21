package io.github.arkmind.seedy.core.wrapped

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One year's Wrapped for the viewer: null while loading, [failed] when the server had none to give. */
class WrappedViewModel(private val session: UserSession, private val year: Int) : ViewModel() {
    data class State(val wrapped: Wrapped? = null, val failed: Boolean = false)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        val api = session.seedyApi
        if (api == null) {
            _state.value = State(failed = true)
            return
        }
        _state.value = State()
        viewModelScope.launch {
            val w = withContext(Dispatchers.IO) { runCatching { api.wrapped(year, deviceZone()) }.getOrNull() }
            _state.value = if (w != null && storiesOf(w).isNotEmpty()) State(w) else State(failed = true)
        }
    }
}

/** The Wrapped years the person has, and this year's teaser while it's December; null without Seedy or on failure. */
suspend fun wrappedStatus(session: UserSession): WrappedStatus? {
    val api = session.seedyApi ?: return null
    return withContext(Dispatchers.IO) { runCatching { api.wrappedStatus(deviceZone()) }.getOrNull() }
}
