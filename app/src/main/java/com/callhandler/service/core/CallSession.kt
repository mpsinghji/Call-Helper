package com.callhandler.service.core

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Call category distinguishing cellular vs internet-based calling apps.
 */
enum class CallType {
    GSM,
    WHATSAPP,
    VOIP
}

/**
 * High-level lifecycle states of an active call session.
 */
enum class UnifiedSessionState {
    IDLE,
    INCOMING,
    RESOLVING_IDENTITY,
    ANNOUNCING,
    LISTENING_COMMANDS,
    ANSWERED,
    DECLINED,
    DISCONNECTED
}

/**
 * Represents a single unified call session (GSM or VoIP).
 *
 * Each session owns a unique [sessionJob] that serves as its session-specific
 * cancellation token. When a call is declined, rejected, or disconnected,
 * [sessionJob] is cancelled immediately, aborting any in-flight or queued
 * announcements, coroutines, or voice recognizer tasks bound to this call.
 */
class CallSession(
    val sessionId: Long,
    val callType: CallType,
    val initialNumber: String? = null,
    val voipPackage: String? = null,
    val voipKey: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val sessionJob: CompletableJob = SupervisorJob()
) {
    @Volatile
    var state: UnifiedSessionState = UnifiedSessionState.INCOMING
        private set

    @Volatile
    var callerIdentity: CallerIdentity? = null

    @Volatile
    var isAnnouncementLocked: Boolean = false

    private val isCancelledOrCompleted = AtomicBoolean(false)

    val isActive: Boolean
        get() = sessionJob.isActive && !isCancelledOrCompleted.get()

    /**
     * Transition the session state to [newState] if legal.
     */
    @Synchronized
    fun updateState(newState: UnifiedSessionState) {
        if (!isActive && newState != UnifiedSessionState.DECLINED && newState != UnifiedSessionState.DISCONNECTED) {
            return
        }
        state = newState
    }

    /**
     * Cancel this session and all child jobs/announcements immediately.
     * Idempotent: safe to call repeatedly.
     */
    fun cancelSession(reason: String, isDeclined: Boolean = true) {
        if (isCancelledOrCompleted.compareAndSet(false, true)) {
            state = if (isDeclined) UnifiedSessionState.DECLINED else UnifiedSessionState.DISCONNECTED
            sessionJob.cancel()
        }
    }

    /**
     * Complete the session normally (e.g. after successful answer).
     */
    fun completeSession(newState: UnifiedSessionState = UnifiedSessionState.ANSWERED) {
        if (isCancelledOrCompleted.compareAndSet(false, true)) {
            state = newState
            sessionJob.complete()
        }
    }
}

/**
 * Events fed into [CallSessionManager] from telephony broadcasts,
 * notification listeners, identity resolvers, and voice commands.
 */
sealed interface CallEvent {
    /** Incoming GSM cellular call ringing event. */
    data class GsmRinging(
        val number: String?,
        val timestampMs: Long = System.currentTimeMillis()
    ) : CallEvent

    /** Incoming GSM call answered (off-hook). */
    data class GsmAnswered(
        val timestampMs: Long = System.currentTimeMillis()
    ) : CallEvent

    /** Incoming GSM call declined, missed, or disconnected. */
    data class GsmDeclinedOrEnded(
        val isDeclined: Boolean = true,
        val timestampMs: Long = System.currentTimeMillis()
    ) : CallEvent

    /** Incoming VoIP call detected (WhatsApp, Instagram, etc.). */
    data class VoipIncoming(
        val key: String,
        val packageName: String,
        val callerName: String,
        val timestampMs: Long = System.currentTimeMillis()
    ) : CallEvent

    /** VoIP call notification updated to terminal state (declined, missed, answered, or dismissed). */
    data class VoipTerminal(
        val key: String,
        val isDeclined: Boolean = true,
        val timestampMs: Long = System.currentTimeMillis()
    ) : CallEvent

    /** Caller identity resolved from Contacts, Truecaller notification, or fallback. */
    data class IdentityResolved(
        val sessionId: Long,
        val identity: CallerIdentity,
        val timestampMs: Long = System.currentTimeMillis()
    ) : CallEvent

    /** Voice command recognized by speech recognizer during ringing state. */
    data class VoiceCommandRecognized(
        val sessionId: Long,
        val command: com.callhandler.service.voice.VoiceCommand,
        val timestampMs: Long = System.currentTimeMillis()
    ) : CallEvent
}
