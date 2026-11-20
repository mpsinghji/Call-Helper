package com.callhandler.service.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Listener interface for call session lifecycle and immediate cancellation events.
 */
interface CallSessionListener {
    fun onSessionStarted(session: CallSession) {}
    fun onSessionStateChanged(session: CallSession, newState: UnifiedSessionState) {}
    /**
     * Triggered immediately when an ongoing call is declined, rejected, or answered.
     * Components performing audio playback (TTS) or speech recognition MUST stop immediately.
     */
    fun onAnnouncementStopRequested(session: CallSession, reason: String) {}
    fun onSessionTerminated(session: CallSession) {}
}

/**
 * Central event-driven orchestrator managing incoming call sessions, identity coordination,
 * and immediate session-specific cancellation for both GSM and VoIP calls.
 */
object CallSessionManager {

    private const val TAG = "CallSessionManager"

    private val sessionIdGenerator = AtomicLong(1000L)

    private val _activeSession = MutableStateFlow<CallSession?>(null)
    val activeSession: StateFlow<CallSession?> = _activeSession.asStateFlow()

    private val listeners = CopyOnWriteArrayList<CallSessionListener>()

    fun addListener(listener: CallSessionListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: CallSessionListener) {
        listeners.remove(listener)
    }

    val currentSession: CallSession?
        get() = _activeSession.value

    val isCallActive: Boolean
        get() = _activeSession.value?.isActive == true

    /**
     * Process an incoming [CallEvent] across telephony, notification listener, or voice modules.
     * Thread-safe and synchronous state transition guarantees.
     */
    @Synchronized
    fun processEvent(event: CallEvent) {
        Log.d(TAG, "processEvent: $event")
        when (event) {
            is CallEvent.GsmRinging -> handleGsmRinging(event)
            is CallEvent.GsmAnswered -> handleGsmAnswered(event)
            is CallEvent.GsmDeclinedOrEnded -> handleGsmDeclinedOrEnded(event)
            is CallEvent.VoipIncoming -> handleVoipIncoming(event)
            is CallEvent.VoipTerminal -> handleVoipTerminal(event)
            is CallEvent.IdentityResolved -> handleIdentityResolved(event)
            is CallEvent.VoiceCommandRecognized -> handleVoiceCommand(event)
        }
    }

    private fun handleGsmRinging(event: CallEvent.GsmRinging) {
        val current = _activeSession.value
        if (current != null && current.isActive) {
            if (current.callType == CallType.GSM) {
                // Same GSM call duplicate broadcast or number updated
                if (current.initialNumber.isNullOrBlank() && !event.number.isNullOrBlank()) {
                    Log.d(TAG, "Updating GSM session number: ${event.number}")
                }
                return
            } else {
                // Cellular call takes priority over ongoing VoIP notification
                Log.i(TAG, "Incoming GSM call superseding active VoIP session: ${current.sessionId}")
                terminateActiveSession(current, reason = "Superseded by GSM call", isDeclined = true)
            }
        }

        val sid = sessionIdGenerator.incrementAndGet()
        val session = CallSession(
            sessionId = sid,
            callType = CallType.GSM,
            initialNumber = event.number,
            createdAtMs = event.timestampMs
        )
        _activeSession.value = session
        Log.i(TAG, "New GSM session started: sid=$sid, number=${event.number}")
        notifySessionStarted(session)
    }

    private fun handleGsmAnswered(event: CallEvent.GsmAnswered) {
        val current = _activeSession.value ?: return
        if (current.callType != CallType.GSM) return

        Log.i(TAG, "GSM call answered: sid=${current.sessionId} — stopping announcements")
        current.completeSession(UnifiedSessionState.ANSWERED)
        notifyAnnouncementStop(current, "Answered")
        _activeSession.value = null
        notifySessionTerminated(current)
    }

    private fun handleGsmDeclinedOrEnded(event: CallEvent.GsmDeclinedOrEnded) {
        val current = _activeSession.value ?: return
        if (current.callType != CallType.GSM) return

        val reason = if (event.isDeclined) "Declined" else "Ended"
        Log.i(TAG, "GSM call $reason: sid=${current.sessionId} — stopping announcements immediately")
        terminateActiveSession(current, reason = reason, isDeclined = event.isDeclined)
    }

    private fun handleVoipIncoming(event: CallEvent.VoipIncoming) {
        val current = _activeSession.value
        if (current != null && current.isActive) {
            if (current.callType == CallType.GSM) {
                Log.d(TAG, "VoIP call ignored: active GSM call in progress (${event.packageName})")
                return
            }
            if (current.voipKey == event.key) {
                Log.d(TAG, "VoIP call already active for key: ${event.key}")
                return
            }
            // Another VoIP call arrived; terminate previous VoIP session
            terminateActiveSession(current, reason = "New VoIP call arrived", isDeclined = false)
        }

        val sid = sessionIdGenerator.incrementAndGet()
        val callType = if (event.packageName.contains("whatsapp", ignoreCase = true)) {
            CallType.WHATSAPP
        } else {
            CallType.VOIP
        }

        val session = CallSession(
            sessionId = sid,
            callType = callType,
            voipPackage = event.packageName,
            voipKey = event.key,
            createdAtMs = event.timestampMs
        ).apply {
            callerIdentity = CallerIdentity(
                number = null,
                displayName = event.callerName,
                source = IdentitySource.CONTACT
            )
        }

        _activeSession.value = session
        Log.i(TAG, "New VoIP session started: sid=$sid, type=$callType, caller='${event.callerName}'")
        notifySessionStarted(session)
    }

    private fun handleVoipTerminal(event: CallEvent.VoipTerminal) {
        val current = _activeSession.value ?: return
        if (current.voipKey != event.key && current.callType == CallType.GSM) {
            return
        }

        val reason = if (event.isDeclined) "VoIP Declined" else "VoIP Ended/Dismissed"
        Log.i(TAG, "$reason: sid=${current.sessionId}, key=${event.key} — stopping announcements immediately")
        terminateActiveSession(current, reason = reason, isDeclined = event.isDeclined)
    }

    private fun handleIdentityResolved(event: CallEvent.IdentityResolved) {
        val current = _activeSession.value ?: return
        if (current.sessionId != event.sessionId || !current.isActive) {
            Log.d(TAG, "Identity resolution dropped: session inactive or sid mismatch (current=${current.sessionId}, event=${event.sessionId})")
            return
        }

        current.callerIdentity = event.identity
        current.updateState(UnifiedSessionState.ANNOUNCING)
        Log.i(TAG, "Identity resolved for sid=${current.sessionId}: '${event.identity.displayName}' (${event.identity.source})")
        notifyStateChanged(current, UnifiedSessionState.ANNOUNCING)
    }

    private fun handleVoiceCommand(event: CallEvent.VoiceCommandRecognized) {
        val current = _activeSession.value ?: return
        if (current.sessionId != event.sessionId || !current.isActive) {
            Log.d(TAG, "Voice command dropped: session inactive or sid mismatch")
            return
        }
        Log.i(TAG, "Voice command recognized for sid=${current.sessionId}: ${event.command}")
    }

    private fun terminateActiveSession(session: CallSession, reason: String, isDeclined: Boolean) {
        session.cancelSession(reason, isDeclined = isDeclined)
        notifyAnnouncementStop(session, reason)
        if (_activeSession.value?.sessionId == session.sessionId) {
            _activeSession.value = null
        }
        notifySessionTerminated(session)
    }

    /** Reset state completely (for testing or service teardown). */
    @Synchronized
    fun reset() {
        val current = _activeSession.value
        if (current != null) {
            current.cancelSession("CallSessionManager reset", isDeclined = false)
            notifyAnnouncementStop(current, "Reset")
            _activeSession.value = null
            notifySessionTerminated(current)
        }
    }

    private fun notifySessionStarted(session: CallSession) {
        for (l in listeners) {
            runCatching { l.onSessionStarted(session) }
                .onFailure { Log.e(TAG, "Listener error in onSessionStarted", it) }
        }
    }

    private fun notifyStateChanged(session: CallSession, state: UnifiedSessionState) {
        for (l in listeners) {
            runCatching { l.onSessionStateChanged(session, state) }
                .onFailure { Log.e(TAG, "Listener error in onSessionStateChanged", it) }
        }
    }

    private fun notifyAnnouncementStop(session: CallSession, reason: String) {
        for (l in listeners) {
            runCatching { l.onAnnouncementStopRequested(session, reason) }
                .onFailure { Log.e(TAG, "Listener error in onAnnouncementStopRequested", it) }
        }
    }

    private fun notifySessionTerminated(session: CallSession) {
        for (l in listeners) {
            runCatching { l.onSessionTerminated(session) }
                .onFailure { Log.e(TAG, "Listener error in onSessionTerminated", it) }
        }
    }
}
