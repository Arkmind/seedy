package io.github.arkmind.seedy.core.remote

/**
 * The phone's volume keys while a TV remote is open ("Your phone’s volume buttons control the TV while this is
 * open"): the activity hands them to [handler] first; true when they went to the TV.
 */
object VolumeKeys {
    @Volatile
    var handler: ((up: Boolean) -> Boolean)? = null
}
