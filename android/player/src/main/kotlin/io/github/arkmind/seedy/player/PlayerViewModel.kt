package io.github.arkmind.seedy.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.session.UserSession
import java.util.UUID

/**
 * Keeps one playback alive across configuration changes; releasing it reports the stop to the server. [startTicks] starts
 * there instead of where the person left off (0: from the beginning); [maxBitrate] stands for Auto quality this time.
 */
class PlayerViewModel(context: Context, session: UserSession, id: UUID, startTicks: Long? = null, maxBitrate: Int? = null) : ViewModel() {
    val controller = PlayerController(context.applicationContext, session, viewModelScope)

    init {
        controller.open(id, startTicks, maxBitrate)
    }

    override fun onCleared() {
        controller.release()
    }
}
