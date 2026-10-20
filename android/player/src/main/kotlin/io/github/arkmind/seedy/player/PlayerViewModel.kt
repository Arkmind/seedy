package io.github.arkmind.seedy.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.session.UserSession
import java.util.UUID

/** Keeps one playback alive across configuration changes; releasing it reports the stop to the server. */
class PlayerViewModel(context: Context, session: UserSession, id: UUID) : ViewModel() {
    val controller = PlayerController(context.applicationContext, session, viewModelScope)

    init {
        controller.open(id)
    }

    override fun onCleared() {
        controller.release()
    }
}
