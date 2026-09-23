package com.callhandler.service.core

import com.callhandler.service.debug.DebugCallSource

/** Lifecycle states for one incoming-call session in the telephone state machine. */
enum class CallState {
    IDLE,

    /** Phone is ringing; voice commands are active. */
    RINGING,

    /** User (or a voice command) answered — stop everything immediately. */
    ANSWERED,

    /** Call was rejected, missed, or otherwise ended while ringing. */
    ENDED
}

/** Lifecycle states for a call debug session. */
enum class CallSessionState {
    DETECTED,
    IDENTIFYING,
    IDENTIFIED,
    ANNOUNCED,
    ACTIVE,
    ENDED,
    BLOCKED
}

/** Spam classification states. */
enum class SpamStatus {
    NORMAL,
    POSSIBLE_SPAM,
    SPAM,
    UNKNOWN
}

/** How the caller was identified, in priority order. */
enum class IdentitySource { CONTACT, TRUECALLER, UNKNOWN }

/**
 * Immutable snapshot of what we currently know about the ringing call.
 */
data class CallerIdentity(
    val number: String?,
    val displayName: String?,
    val source: IdentitySource,
    val spamStatus: SpamStatus = SpamStatus.UNKNOWN
) {
    companion object {
        fun unknown(number: String?, spamStatus: SpamStatus = SpamStatus.UNKNOWN) =
            CallerIdentity(number, null, IdentitySource.UNKNOWN, spamStatus)
    }
}

/**
 * Immutable historical record of a completed or in-progress call session.
 * Once created and added to history, subsequent changes to active sessions
 * must never mutate this entry.
 */
data class CallHistoryEntry(
    val sessionId: Long,
    val startTimeMs: Long,
    val endTimeMs: Long = 0L,
    val formattedTime: String,
    val callSource: DebugCallSource = DebugCallSource.GSM,
    val direction: String,
    val number: String?,
    val contactName: String?,
    val truecallerName: String?,
    val announcedName: String?,
    val announcementSource: String?,
    val ttsText: String?,
    val status: String,
    val spamStatus: SpamStatus = SpamStatus.UNKNOWN,
    val blockReason: String? = null,
    val bugs: List<String> = emptyList(),
    val events: List<String> = emptyList()
)
