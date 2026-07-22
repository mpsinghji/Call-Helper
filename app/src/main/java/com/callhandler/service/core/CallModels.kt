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

/** How the caller was identified, in priority order. */
enum class IdentitySource { CONTACT, TRUECALLER, UNKNOWN }

/**
 * Immutable snapshot of what we currently know about the ringing call.
 */
data class CallerIdentity(
    val number: String?,
    val displayName: String?,
    val source: IdentitySource
) {
    companion object {
        fun unknown(number: String?) =
            CallerIdentity(number, null, IdentitySource.UNKNOWN)
    }
}
