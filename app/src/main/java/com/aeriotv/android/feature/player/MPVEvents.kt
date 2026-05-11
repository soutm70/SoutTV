package com.aeriotv.android.feature.player

/**
 * libmpv event codes from client.h. Stable across libmpv versions.
 * Used to switch on the int passed to MPV.EventObserver.event(eventId, node).
 */
object MPVEvents {
    const val NONE = 0
    const val SHUTDOWN = 1
    const val LOG_MESSAGE = 2
    const val GET_PROPERTY_REPLY = 3
    const val SET_PROPERTY_REPLY = 4
    const val COMMAND_REPLY = 5
    const val START_FILE = 6
    const val END_FILE = 7
    const val FILE_LOADED = 8
    const val CLIENT_MESSAGE = 16
    const val VIDEO_RECONFIG = 17
    const val AUDIO_RECONFIG = 18
    const val SEEK = 20
    const val PLAYBACK_RESTART = 21
    const val PROPERTY_CHANGE = 22
    const val QUEUE_OVERFLOW = 24
    const val HOOK = 25
}
