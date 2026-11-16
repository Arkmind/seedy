package io.github.arkmind.seedy.mobile.update

import androidx.compose.runtime.Composable
import io.github.arkmind.seedy.ui.update.AppUpdates
import io.github.arkmind.seedy.ui.update.rememberUpdateActions

/**
 * The update screen over the phone's tabs while there's an update to offer ([visible]: only at the top of the tabs,
 * never over the player or a book).
 */
@Composable
fun UpdatePrompt(visible: Boolean) {
    val offer = AppUpdates.offer ?: return
    if (!visible) return
    val actions = rememberUpdateActions(offer)
    UpdateScreen(offer, AppUpdates.installed, actions.onUpdate, actions.onLater, actions.onChanges, actions.install)
}
