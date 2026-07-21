package com.callhandler.service.core

/** Where the incoming call originates. */
enum class CallSource {
    /** Regular cellular (Telecom-managed) call — voice commands supported. */
    CELLULAR,

    /** WhatsApp voice call — announce only, no voice commands (no public answer API). */
    WHATSAPP_VOICE,

    /** WhatsApp video call — announce only, no voice commands. */
    WHATSAPP_VIDEO
}

/** How the caller was identified, in priority order. */
enum class IdentitySource { CONTACT, TRUECALLER, UNKNOWN }

/**
 * Immutable snapshot of what we currently know about the ringing call.
 *
 * [number] may be null for WhatsApp calls (the notification usually only
 * carries a display name).
 */
data class CallerIdentity(
    val number: String?,
    val displayName: String?,
    val source: IdentitySource
) {
    val isUnknown: Boolean get() = source == IdentitySource.UNKNOWN

    companion object {
        fun unknown(number: String?) =
            CallerIdentity(number, null, IdentitySource.UNKNOWN)
    }
}

/** Lifecycle states for one incoming-call session. */
enum class CallState {
    IDLE,

    /** Phone is ringing; we may still be waiting for Truecaller identification. */
    RINGING,

    /** User (or a voice command) answered — stop everything immediately. */
    ANSWERED,

    /** Call was rejected, missed, or otherwise ended while ringing. */
    ENDED
}
