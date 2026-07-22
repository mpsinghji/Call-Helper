package com.callhandler.service.core

/** Lifecycle states for one incoming-call session. */
enum class CallState {
    IDLE,

    /** Phone is ringing; voice commands are active. */
    RINGING,

    /** User (or a voice command) answered — stop everything immediately. */
    ANSWERED,

    /** Call was rejected, missed, or otherwise ended while ringing. */
    ENDED
}
