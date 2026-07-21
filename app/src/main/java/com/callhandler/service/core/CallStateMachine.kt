package com.callhandler.service.core

import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe state machine for a single incoming-call session.
 *
 * Legal transitions:
 *   IDLE     -> RINGING
 *   RINGING  -> ANSWERED | ENDED
 *   ANSWERED -> ENDED
 *   any      -> IDLE (reset, after cleanup)
 *
 * Everything else is rejected, which naturally absorbs edge cases such as
 * stale Truecaller notifications arriving after the call has ended or
 * duplicate PHONE_STATE broadcasts.
 */
class CallStateMachine(
    private val onTransition: (from: CallState, to: CallState) -> Unit
) {
    private val state = AtomicReference(CallState.IDLE)

    val current: CallState get() = state.get()

    val isRinging: Boolean get() = current == CallState.RINGING

    /** Attempts a transition; returns true if it was legal and applied. */
    fun transitionTo(target: CallState): Boolean {
        while (true) {
            val from = state.get()
            if (!isLegal(from, target)) return false
            if (state.compareAndSet(from, target)) {
                onTransition(from, target)
                return true
            }
            // CAS lost a race — re-read and re-validate.
        }
    }

    fun reset() {
        val from = state.getAndSet(CallState.IDLE)
        if (from != CallState.IDLE) onTransition(from, CallState.IDLE)
    }

    private fun isLegal(from: CallState, to: CallState): Boolean = when (from) {
        CallState.IDLE -> to == CallState.RINGING
        CallState.RINGING -> to == CallState.ANSWERED || to == CallState.ENDED
        CallState.ANSWERED -> to == CallState.ENDED
        CallState.ENDED -> false // only reset() leaves ENDED
    }
}
