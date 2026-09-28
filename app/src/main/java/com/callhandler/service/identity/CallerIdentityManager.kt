package com.callhandler.service.identity

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.callhandler.service.core.CallerIdentity
import com.callhandler.service.core.IdentitySource
import com.callhandler.service.debug.CallBugDetector
import com.callhandler.service.debug.CallDebugTracker
import com.callhandler.service.debug.DebugLogStore
import com.callhandler.service.debug.TruecallerAccessibilityService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope

/**
 * Per-session identity state tracking all observations and enforcing strict priority:
 * CONTACT > TRUECALLER > LOCAL_FALLBACK > UNKNOWN
 */
data class SessionIdentityState(
    var number: String? = null,
    var contactName: String? = null,
    var truecallerName: String? = null,
    var fallbackName: String? = null,
    var selectedName: String? = null,
    var selectedSource: IdentitySource = IdentitySource.UNKNOWN,
    var spamStatus: com.callhandler.service.core.SpamStatus = com.callhandler.service.core.SpamStatus.UNKNOWN,
    var announcementLocked: Boolean = false
) {
    val hasStrongIdentity: Boolean
        get() = (!contactName.isNullOrBlank() && selectedSource == IdentitySource.CONTACT) ||
                (!truecallerName.isNullOrBlank() && selectedSource == IdentitySource.TRUECALLER)
}

/**
 * Resolves who is calling according to strict priority:
 *  1. Saved contact name  (instant, from any number source - STOP, never replaced by Truecaller or Unknown)
 *  2. Truecaller overlay  (waits up to [resolveIdentity.waitMs])
 *  3. "Unknown caller"     (fallback)
 *
 * Number sources (in parallel):
 *  - PHONE_STATE broadcast (CallStateReceiver)
 *  - PhoneStateListener (in-process, registered in CallHandlerService)
 *  - Call log ContentObserver (reacts when Android writes the call log entry)
 *
 * Identity is published through a StateFlow for subscribers.
 */
class CallerIdentityManager(private val context: Context) {

    private val _identity = MutableStateFlow<CallerIdentity?>(null)
    val identity: StateFlow<CallerIdentity?> = _identity

    @Volatile
    private var currentNumber: String? = null
    private var state = SessionIdentityState()
    private var pendingResolve: CompletableDeferred<CallerIdentity>? = null
    private var lastTruecallerName: String? = null
    private var callLogObserver: ContentObserver? = null

    /**
     * Resets identity state completely for a new incoming call.
     * Ensures no caller names, numbers, or identities bleed over from prior calls.
     * Does NOT wipe an existing strong identity when called with null / unknown number
     * for the same active call session.
     */
    fun resetForNewCall(number: String? = null) {
        if (state.hasStrongIdentity) {
            val n1 = CallBugDetector.normalizePhoneNumber(state.number)
            val n2 = CallBugDetector.normalizePhoneNumber(number)
            val isCompatible = number.isNullOrBlank() || state.number.isNullOrBlank() || n1 == n2 || n1.endsWith(n2) || n2.endsWith(n1)
            if (isCompatible) {
                Log.d(TAG, "Preserving existing strong identity during resetForNewCall (name=${state.selectedName}, src=${state.selectedSource})")
                if (state.number.isNullOrBlank() && !number.isNullOrBlank()) {
                    state.number = number
                    currentNumber = number
                }
                return
            }
        }

        Log.d(TAG, "Resetting CallerIdentityManager for new call (number=$number)")
        stopCallLogObserver()
        currentNumber = number
        lastTruecallerName = null
        pendingResolve?.cancel()
        pendingResolve = null
        state = SessionIdentityState(
            number = number,
            selectedName = if (number != null) null else "Unknown caller",
            selectedSource = IdentitySource.UNKNOWN,
            announcementLocked = false
        )
        _identity.value = if (number != null) CallerIdentity.unknown(number) else null
        TruecallerAccessibilityService.onCallerInfoDetected = null
        TruecallerAccessibilityService.resetDeduplication()
    }

    fun reset() {
        stopCallLogObserver()
        currentNumber = null
        lastTruecallerName = null
        pendingResolve?.cancel()
        pendingResolve = null
        state = SessionIdentityState()
        _identity.value = null
        TruecallerAccessibilityService.onCallerInfoDetected = null
        TruecallerAccessibilityService.resetDeduplication()
    }

    /**
     * Registers an incoming number delivered by either number source.
     * The first valid contact match (or Truecaller name) completes the
     * pending resolution; subsequent duplicates are ignored.
     */
    fun onIncomingNumber(number: String, source: String) {
        // Idempotent: if we already resolved a strong identity for this exact number, skip
        if (number == currentNumber && state.hasStrongIdentity) {
            Log.d(TAG, "onIncomingNumber: idempotent skip (same number, strong identity exists: ${state.selectedSource} / ${state.selectedName})")
            DebugLogStore.log("CALLER_ID", "DUPLICATE NUMBER EVENT SKIPPED: $source (identity already resolved: ${state.selectedSource} / ${state.selectedName})")
            return
        }
        currentNumber = number
        state.number = number
        Log.d(TAG, "Incoming number source=$source, starting lookup")
        DebugLogStore.log("CALLER_ID", "CALL = RINGING (number=$number, source=$source)")
        CallDebugTracker.onPhoneNumberResolved(number, source)

        // Idempotent: if contact was already looked up for this number, don't repeat
        if (!state.contactName.isNullOrBlank()) {
            Log.d(TAG, "Contact already resolved for this session: ${state.contactName} — skipping duplicate lookup")
            DebugLogStore.log("CALLER_ID", "CONTACT LOOKUP SKIPPED (already resolved: ${state.contactName})")
            return
        }

        val name = runCatching { lookupContactBlocking(number) }.getOrNull()
        CallDebugTracker.onContactLookupResult(number, name)
        if (name != null) {
            Log.d(TAG, "Contacts lookup: MATCH -> $name")
            DebugLogStore.log("CALLER_ID", "CONTACT RESULT = $name")
            DebugLogStore.log("CALLER_ID", "SELECTED SOURCE = CONTACT")
            DebugLogStore.log("CALLER_ID", "FINAL ANNOUNCEMENT = $name")
            CallDebugTracker.onIdentitySelected(number, name, "CONTACT")
            state.contactName = name
            state.selectedName = name
            state.selectedSource = IdentitySource.CONTACT
            publish(CallerIdentity(number, name, IdentitySource.CONTACT))
        } else {
            Log.d(TAG, "Contacts lookup: NO_MATCH (waiting for Truecaller)")
            DebugLogStore.log("CALLER_ID", "CONTACT RESULT = NOT FOUND")
        }
    }

    /**
     * Called when Truecaller identifies the caller (via Accessibility or Notification).
     *
     * @param name           The caller name observed from Truecaller.
     * @param spamStatus     Spam classification from Truecaller.
     * @param truecallerNumber  The phone number Truecaller associated with this name.
     *                          Used to validate that this observation belongs to the
     *                          current GSM call. If null/blank, the observation is only
     *                          accepted when no current number is known (risky but
     *                          necessary for the no-number fallback path).
     */
    fun onTruecallerName(
        name: String,
        spamStatus: com.callhandler.service.core.SpamStatus = com.callhandler.service.core.SpamStatus.UNKNOWN,
        truecallerNumber: String? = null
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == lastTruecallerName) return

        // Reject Truecaller UI placeholder text (not a real caller name)
        if (isTruecallerUIText(trimmed) || TruecallerParser.isStaleOrStatusText(trimmed)) {
            Log.d(TAG, "Truecaller name rejected (UI text): '$trimmed'")
            DebugLogStore.log("CALLER_ID", "TRUECALLER NAME REJECTED (UI text): $trimmed")
            return
        }

        // CRITICAL: If there is no active GSM call, Truecaller is diagnostic only.
        // It must NOT create a session, select identity, lock anything, or prepare an announcement.
        if (!CallDebugTracker.hasActiveGsmCall()) {
            Log.d(TAG, "Truecaller observation with no active GSM call (diagnostic only): '$trimmed'")
            DebugLogStore.log("CALLER_ID", "TRUECALLER DIAGNOSTIC ONLY (no active GSM call): $trimmed (number=${truecallerNumber ?: "Unknown"})")
            // Record in tracker for Developer Details diagnostic, but do NOT modify identity state
            CallDebugTracker.onTruecallerResult(truecallerNumber, trimmed, spamStatus)
            return
        }

        // CRITICAL: Validate Truecaller number against current GSM call number.
        // A stale Truecaller overlay from a previous call MUST NOT contaminate the current call.
        if (!truecallerNumber.isNullOrBlank() && !currentNumber.isNullOrBlank()) {
            val normTc = CallBugDetector.normalizePhoneNumber(truecallerNumber)
            val normGsm = CallBugDetector.normalizePhoneNumber(currentNumber)
            if (normTc.isNotEmpty() && normGsm.isNotEmpty() &&
                normTc != normGsm && !normTc.endsWith(normGsm) && !normGsm.endsWith(normTc)
            ) {
                Log.w(TAG, "TRUECALLER REJECTED: number mismatch current=$currentNumber observed=$truecallerNumber name=$trimmed")
                DebugLogStore.log("CALLER_ID", "TRUECALLER REJECTED: number mismatch current=$currentNumber, observed=$truecallerNumber, name=$trimmed")
                CallDebugTracker.onTruecallerResult(truecallerNumber, trimmed, spamStatus)
                return
            }
        }

        // CRITICAL: If current GSM number is unknown but Truecaller has a known number,
        // do NOT use this result to set identity — it may be a stale observation.
        // Only accept Truecaller when we can verify the number matches, or when both are unknown.
        if (currentNumber.isNullOrBlank() && !truecallerNumber.isNullOrBlank()) {
            Log.d(TAG, "Truecaller observation with unknown GSM number — cannot verify, treating as diagnostic: '$trimmed' (tc=$truecallerNumber)")
            DebugLogStore.log("CALLER_ID", "TRUECALLER DEFERRED (GSM number unknown, cannot verify): name=$trimmed, tcNumber=$truecallerNumber")
            CallDebugTracker.onTruecallerResult(truecallerNumber, trimmed, spamStatus)
            return
        }

        // CRITICAL: If announcement is already locked, do NOT mutate identity.
        // Record the observation for diagnostics but preserve the locked identity.
        if (state.announcementLocked) {
            Log.d(TAG, "Truecaller observation after announcement lock: '$trimmed' — identity preserved")
            DebugLogStore.log("CALLER_ID", "TRUECALLER POST-LOCK OBSERVATION: $trimmed (identity locked at ${state.selectedSource} / ${state.selectedName})")
            // Still record in tracker for diagnostics, but don't update session source
            CallDebugTracker.onTruecallerResult(currentNumber, trimmed, spamStatus)
            return
        }

        lastTruecallerName = trimmed
        Log.d(TAG, "Resolved from Truecaller: $trimmed (spam=$spamStatus)")
        DebugLogStore.log("CALLER_ID", "TRUECALLER NAME = $trimmed (spam=$spamStatus)")
        CallDebugTracker.onTruecallerResult(currentNumber, trimmed, spamStatus)

        // Rule: If contacts matched first, NEVER replace with Truecaller, but attach spam metadata
        if (state.selectedSource == IdentitySource.CONTACT || _identity.value?.source == IdentitySource.CONTACT) {
            DebugLogStore.log("CALLER_ID", "TRUECALLER IGNORED (Contact already resolved)")
            state.truecallerName = trimmed
            if (spamStatus != com.callhandler.service.core.SpamStatus.UNKNOWN) {
                state.spamStatus = spamStatus
                val current = _identity.value
                if (current != null) {
                    _identity.value = current.copy(spamStatus = spamStatus)
                }
            }
            return
        }

        state.truecallerName = trimmed
        state.selectedName = trimmed
        state.selectedSource = IdentitySource.TRUECALLER
        state.spamStatus = spamStatus

        DebugLogStore.log("CALLER_ID", "SELECTED SOURCE = TRUECALLER")
        DebugLogStore.log("CALLER_ID", "FINAL ANNOUNCEMENT = $trimmed")
        CallDebugTracker.onIdentitySelected(currentNumber, trimmed, "TRUECALLER")
        publish(CallerIdentity(currentNumber, trimmed, IdentitySource.TRUECALLER, spamStatus))
    }

    /**
     * Waits up to [waitMs] for the first valid identity from Contacts or
     * Truecaller. Returns Unknown if neither source succeeds.
     *
     * @param number  Incoming number from the first RINGING event (may be null).
     * @param source  "PHONE_STATE" for diagnostics.
     */
    suspend fun resolveIdentity(number: String?, source: String, waitMs: Long): CallerIdentity = coroutineScope {
        // 0. Do NOT wipe or rerun if we already have an authoritative strong identity for this call
        if (state.hasStrongIdentity && _identity.value?.displayName != null) {
            Log.d(TAG, "resolveIdentity: Already resolved strong identity (${_identity.value?.displayName} / ${_identity.value?.source}), returning immediately")
            return@coroutineScope _identity.value!!
        }

        resetForNewCall(number)

        if (number != null && state.number.isNullOrBlank()) {
            state.number = number
            currentNumber = number
        }

        if (currentNumber != null) {
            Log.d(TAG, "Incoming number source=$source, starting lookup")
            DebugLogStore.log("CALLER_ID", "CALL = RINGING (number=$currentNumber, source=$source)")
            CallDebugTracker.onPhoneNumberResolved(currentNumber!!, source)
        }

        val deferred = CompletableDeferred<CallerIdentity>()
        pendingResolve = deferred

        // 1. Check saved contacts FIRST (instant, authoritative)
        currentNumber?.let { number2 ->
            val contactName = lookupContact(number2)
            CallDebugTracker.onContactLookupResult(number2, contactName)
            if (contactName != null) {
                Log.d(TAG, "Contacts lookup: MATCH -> $contactName")
                DebugLogStore.log("CALLER_ID", "CONTACT RESULT = $contactName")
                DebugLogStore.log("CALLER_ID", "SELECTED SOURCE = CONTACT")
                DebugLogStore.log("CALLER_ID", "FINAL ANNOUNCEMENT = $contactName")
                CallDebugTracker.onIdentitySelected(number2, contactName, "CONTACT")
                state.contactName = contactName
                state.selectedName = contactName
                state.selectedSource = IdentitySource.CONTACT
                val id = CallerIdentity(number2, contactName, IdentitySource.CONTACT)
                _identity.value = id
                pendingResolve = null
                return@coroutineScope id
            }
            Log.d(TAG, "Contacts lookup: NO_MATCH")
            DebugLogStore.log("CALLER_ID", "CONTACT RESULT = NOT FOUND (waiting for Truecaller ${waitMs}ms)")
        }

        // 2. If no number yet, start call log observer + wait for number from any source
        if (currentNumber == null && !state.hasStrongIdentity) {
            Log.d(TAG, "No number \u2014 starting call log observer + waiting up to ${NUMBER_WAIT_MS}ms")
            DebugLogStore.log("CALLER_ID", "NO NUMBER \u2014 waiting ${NUMBER_WAIT_MS}ms (observer + broadcast + listener)")

            // Start watching the call log for new entries (reactive)
            startCallLogObserver()

            val waitEnd = System.currentTimeMillis() + NUMBER_WAIT_MS
            while (currentNumber == null && !state.hasStrongIdentity && System.currentTimeMillis() < waitEnd) {
                delay(50)
            }

            // Stop the observer — either we got the number or we timed out
            stopCallLogObserver()

            // Check if strong identity arrived in parallel while waiting
            if (state.hasStrongIdentity && _identity.value?.displayName != null) {
                pendingResolve = null
                return@coroutineScope _identity.value!!
            }

            // Retry contacts lookup if number arrived during the wait
            currentNumber?.let { lateNum ->
                val contactName = lookupContact(lateNum)
                CallDebugTracker.onContactLookupResult(lateNum, contactName)
                if (contactName != null) {
                    Log.d(TAG, "Contacts lookup (late number): MATCH -> $contactName")
                    DebugLogStore.log("CALLER_ID", "CONTACT RESULT = $contactName (late number)")
                    DebugLogStore.log("CALLER_ID", "SELECTED SOURCE = CONTACT")
                    DebugLogStore.log("CALLER_ID", "FINAL ANNOUNCEMENT = $contactName")
                    CallDebugTracker.onIdentitySelected(lateNum, contactName, "CONTACT")
                    state.contactName = contactName
                    state.selectedName = contactName
                    state.selectedSource = IdentitySource.CONTACT
                    val id = CallerIdentity(lateNum, contactName, IdentitySource.CONTACT)
                    _identity.value = id
                    pendingResolve = null
                    return@coroutineScope id
                }
                Log.d(TAG, "Contacts lookup (late number): NO_MATCH")
                DebugLogStore.log("CALLER_ID", "CONTACT RESULT = NOT FOUND (late number)")
            }
        }

        // 2b. If STILL no number, try a one-shot call log query as last resort
        if (currentNumber == null && !state.hasStrongIdentity) {
            Log.d(TAG, "Trying call log query fallback...")
            DebugLogStore.log("CALLER_ID", "CALL_LOG_FALLBACK \u2014 querying latest call")
            val callLogNumber = queryLatestIncomingNumber()
            if (callLogNumber != null) {
                currentNumber = callLogNumber
                state.number = callLogNumber
                Log.d(TAG, "Call log fallback: got number $callLogNumber")
                DebugLogStore.log("CALLER_ID", "CALL_LOG_FALLBACK = $callLogNumber")
                DebugLogStore.log("CALLER_ID", "CALL = RINGING (number=$callLogNumber, source=CALL_LOG)")
                CallDebugTracker.onPhoneNumberResolved(callLogNumber, "CALL_LOG")
                val contactName = lookupContact(callLogNumber)
                CallDebugTracker.onContactLookupResult(callLogNumber, contactName)
                if (contactName != null) {
                    Log.d(TAG, "Contacts lookup (call log number): MATCH -> $contactName")
                    DebugLogStore.log("CALLER_ID", "CONTACT RESULT = $contactName (call log)")
                    DebugLogStore.log("CALLER_ID", "SELECTED SOURCE = CONTACT")
                    DebugLogStore.log("CALLER_ID", "FINAL ANNOUNCEMENT = $contactName")
                    CallDebugTracker.onIdentitySelected(callLogNumber, contactName, "CONTACT")
                    state.contactName = contactName
                    state.selectedName = contactName
                    state.selectedSource = IdentitySource.CONTACT
                    val id = CallerIdentity(callLogNumber, contactName, IdentitySource.CONTACT)
                    _identity.value = id
                    pendingResolve = null
                    return@coroutineScope id
                }
                Log.d(TAG, "Contacts lookup (call log number): NO_MATCH")
                DebugLogStore.log("CALLER_ID", "CONTACT RESULT = NOT FOUND (call log)")
            } else {
                // If a strong identity arrived in parallel, do NOT announce unknown
                if (state.hasStrongIdentity && _identity.value?.displayName != null) {
                    pendingResolve = null
                    return@coroutineScope _identity.value!!
                }

                // No number from ANY source — Truecaller can't help either without a number
                Log.d(TAG, "No number from any source \u2014 announcing Unknown immediately")
                DebugLogStore.log("CALLER_ID", "CALL_LOG_FALLBACK = NO NUMBER")
                DebugLogStore.log("CALLER_ID", "SELECTED SOURCE = UNKNOWN (no number from any source)")
                DebugLogStore.log("CALLER_ID", "FINAL ANNOUNCEMENT = Unknown caller")
                CallDebugTracker.onIdentitySelected(null, "Unknown caller", "UNKNOWN")
                state.selectedName = "Unknown caller"
                state.selectedSource = IdentitySource.UNKNOWN
                val id = CallerIdentity.unknown(null)
                _identity.value = id
                pendingResolve = null
                return@coroutineScope id
            }
        }

        // 3. Check if Truecaller overlay is already visible right now
        val existingA11yInfo = TruecallerAccessibilityService.instance?.inspectCurrentWindowsForCaller()
        if (existingA11yInfo?.announcementName != null && !state.hasStrongIdentity) {
            val tcName = existingA11yInfo.announcementName!!
            val tcNumber = existingA11yInfo.phoneNumber
            val spamStatus = existingA11yInfo.spamStatus

            // Validate Truecaller number against current GSM number before accepting
            val numberOk = isTruecallerNumberCompatible(tcNumber, currentNumber)
            if (!numberOk) {
                Log.w(TAG, "Truecaller instant-check number mismatch: tc=$tcNumber, gsm=$currentNumber — REJECTED '$tcName'")
                DebugLogStore.log("CALLER_ID", "TRUECALLER OVERLAY REJECTED (number mismatch): name='$tcName', tcNumber=$tcNumber, activeNumber=$currentNumber")
            } else {
                Log.i(TAG, "Truecaller window inspected immediately: '$tcName' (spam=$spamStatus, number=$tcNumber)")
                DebugLogStore.log("CALLER_ID", "TRUECALLER OVERLAY INSTANT MATCH = $tcName (spam=$spamStatus, number=$tcNumber)")
                DebugLogStore.log("CALLER_ID", "SELECTED SOURCE = TRUECALLER")
                DebugLogStore.log("CALLER_ID", "FINAL ANNOUNCEMENT = $tcName")
                CallDebugTracker.onTruecallerResult(currentNumber, tcName, spamStatus)
                CallDebugTracker.onIdentitySelected(currentNumber, tcName, "TRUECALLER")
                state.truecallerName = tcName
                state.selectedName = tcName
                state.selectedSource = IdentitySource.TRUECALLER
                state.spamStatus = spamStatus
                val id = CallerIdentity(currentNumber, tcName, IdentitySource.TRUECALLER, spamStatus)
                _identity.value = id
                pendingResolve = null
                return@coroutineScope id
            }
        }

        // Check whether a notification/number event completed resolution
        if (deferred.isCompleted) {
            pendingResolve = null
            val id = deferred.await()
            if (_identity.value == null) _identity.value = id
            return@coroutineScope id
        }

        // 4. Register live callback for accessibility events
        TruecallerAccessibilityService.onCallerInfoDetected = { info ->
            info.announcementName?.let { onTruecallerName(it, info.spamStatus, info.phoneNumber) }
        }

        // 5. Poller job: inspects interactive windows every 250ms during wait period
        val pollerJob = launch(Dispatchers.Default) {
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < waitMs && !deferred.isCompleted && !state.hasStrongIdentity) {
                delay(250)
                val info = TruecallerAccessibilityService.instance?.inspectCurrentWindowsForCaller()
                if (info?.announcementName != null) {
                    // Validate number before accepting polled Truecaller result
                    if (isTruecallerNumberCompatible(info.phoneNumber, currentNumber)) {
                        onTruecallerName(info.announcementName!!, info.spamStatus, info.phoneNumber)
                        break
                    } else {
                        Log.d(TAG, "Truecaller poller: number mismatch (tc=${info.phoneNumber}, gsm=$currentNumber) — skipping '${info.announcementName}'")
                    }
                }
            }
        }

        try {
            val resolved = withTimeoutOrNull(waitMs) { deferred.await() }
            pendingResolve = null

            val id = resolved
                ?: if (state.hasStrongIdentity && _identity.value?.displayName != null) _identity.value!!
                else _identity.value?.takeIf { it.displayName != null && it.source != IdentitySource.UNKNOWN }
                ?: run {
                    Log.d(
                        TAG,
                        "Identity resolution timeout -> Unknown " +
                                "(numberPresent=${currentNumber != null}, " +
                                "contactsChecked=${currentNumber != null}, " +
                                "truecallerReceived=${lastTruecallerName != null})"
                    )
                    DebugLogStore.log("CALLER_ID", "TRUECALLER = TIMEOUT / UNAVAILABLE")
                    DebugLogStore.log("CALLER_ID", "SELECTED SOURCE = UNKNOWN")
                    DebugLogStore.log("CALLER_ID", "FINAL ANNOUNCEMENT = Unknown caller")
                    CallDebugTracker.onIdentitySelected(currentNumber, "Unknown caller", "UNKNOWN")
                    state.selectedName = "Unknown caller"
                    state.selectedSource = IdentitySource.UNKNOWN
                    CallerIdentity.unknown(currentNumber)
                }

            if (_identity.value == null) _identity.value = id
            return@coroutineScope id
        } finally {
            pollerJob.cancel()
            TruecallerAccessibilityService.onCallerInfoDetected = null
            pendingResolve = null
        }
    }

    /**
     * Locks the announcement identity so that later Truecaller observations
     * cannot mutate the source/name that was already used for the announcement.
     * Called after the identity has been finalized and announcement has started.
     *
     * Also performs an announcement-identity mismatch check: if the identity
     * about to be announced differs from the validated session identity, this
     * is flagged as a critical bug.
     */
    fun lockAnnouncementIdentity() {
        // CRITICAL: Detect announcement-identity mismatch before locking.
        // The announced identity must match the current validated identity.
        val currentId = _identity.value
        if (currentId != null && state.selectedName != null &&
            currentId.displayName != null && currentId.displayName != state.selectedName
        ) {
            Log.e(TAG, "CRITICAL: ANNOUNCEMENT/IDENTITY MISMATCH: announcement='${state.selectedName}' validated='${currentId.displayName}'")
            DebugLogStore.log("CALLER_ID", "⚠ CRITICAL BUG: ANNOUNCEMENT_IDENTITY (${state.selectedName}) != CURRENT_VALIDATED_IDENTITY (${currentId.displayName})")
            // Fix: force the announcement to use the validated identity
            state.selectedName = currentId.displayName
            state.selectedSource = currentId.source
        }
        state.announcementLocked = true
        Log.d(TAG, "Announcement identity locked: ${state.selectedSource} / ${state.selectedName}")
        DebugLogStore.log("CALLER_ID", "ANNOUNCEMENT IDENTITY LOCKED: ${state.selectedSource} / ${state.selectedName}")
    }

    /**
     * Checks whether a Truecaller-observed number is compatible with the current GSM number.
     * Returns true ONLY if:
     *   - Both numbers are known AND they match (same digits or suffix match), OR
     *   - BOTH are unknown (no number available from either source).
     * Returns false if Truecaller has a number but GSM does not — this prevents stale
     * observations from contaminating a call whose number hasn't been resolved yet.
     */
    private fun isTruecallerNumberCompatible(tcNumber: String?, gsmNumber: String?): Boolean {
        // If Truecaller has a number but GSM does not, we CANNOT verify — reject to be safe
        if (!tcNumber.isNullOrBlank() && gsmNumber.isNullOrBlank()) return false
        // If both are blank/null, this is a no-number scenario — allow (risky but necessary)
        if (tcNumber.isNullOrBlank() && gsmNumber.isNullOrBlank()) return true
        // If GSM has a number but Truecaller does not, allow (Truecaller just didn't report one)
        if (tcNumber.isNullOrBlank()) return true
        val normTc = CallBugDetector.normalizePhoneNumber(tcNumber)
        val normGsm = CallBugDetector.normalizePhoneNumber(gsmNumber)
        if (normTc.isEmpty() || normGsm.isEmpty()) return true
        return normTc == normGsm || normTc.endsWith(normGsm) || normGsm.endsWith(normTc)
    }

    private fun publish(id: CallerIdentity) {
        _identity.value = id
        pendingResolve?.let { if (!it.isCompleted) it.complete(id) }
    }

    // -------------------------------------------------- call log ContentObserver

    /**
     * Watches the call log for new entries. When Android writes the call log
     * entry (which may happen during ringing or after call ends), this observer
     * fires immediately and reads the number. This is reactive — no polling needed.
     */
    private fun startCallLogObserver() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                onChange(selfChange, null)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (currentNumber != null) return // already have a number

                Log.d(TAG, "Call log observer: change detected, querying...")
                DebugLogStore.log("CALLER_ID", "CALL_LOG_OBSERVER \u2014 change detected")

                val number = queryLatestIncomingNumberBlocking()
                if (number != null && currentNumber == null) {
                    Log.d(TAG, "Call log observer: found number=$number")
                    DebugLogStore.log("CALLER_ID", "CALL_LOG_OBSERVER = $number")
                    onIncomingNumber(number, "CALL_LOG_OBSERVER")
                }
            }
        }

        runCatching {
            context.contentResolver.registerContentObserver(
                CallLog.Calls.CONTENT_URI, true, observer
            )
            callLogObserver = observer
            Log.d(TAG, "Call log ContentObserver registered")
        }.onFailure {
            Log.w(TAG, "Failed to register call log observer: ${it.message}")
        }
    }

    private fun stopCallLogObserver() {
        callLogObserver?.let { observer ->
            runCatching {
                context.contentResolver.unregisterContentObserver(observer)
            }
            callLogObserver = null
        }
    }

    // -------------------------------------------------- utility methods

    /**
     * Returns true if the text is Truecaller UI placeholder text
     * (e.g. search bar hint, feature descriptions) rather than a caller name.
     */
    private fun isTruecallerUIText(text: String): Boolean {
        val clean = text.lowercase()
            .removePrefix("spam call from ")
            .removePrefix("spam call")
            .trim()
        return clean.contains("search numbers") ||
               clean.contains("names & more") ||
               clean.contains("get truecaller") ||
               clean.contains("identify unknown") ||
               clean.contains("block spam") ||
               clean.contains("who called") ||
               clean.contains("get started") ||
               clean.contains("protect your family") ||
               clean.contains("start free trial") ||
               clean.contains("set as default") ||
               clean.contains("terms of service") ||
               clean.contains("privacy policy") ||
               clean == "skip" ||
               clean == "truecaller" ||
               clean == "calls" ||
               clean == "messages" ||
               clean == "contacts" ||
               clean == "premium" ||
               clean == "assistant"
    }

    private suspend fun lookupContact(number: String): String? =
        withContext(Dispatchers.IO) { lookupContactBlocking(number) }

    private fun lookupContactBlocking(number: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return null

        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number)
        )
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    /**
     * Queries the call log for the most recent call number.
     * Used both by the ContentObserver and as a one-shot fallback.
     */
    private suspend fun queryLatestIncomingNumber(): String? =
        withContext(Dispatchers.IO) { queryLatestIncomingNumberBlocking() }

    private fun queryLatestIncomingNumberBlocking(): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Call log fallback: READ_CALL_LOG permission not granted")
            return null
        }

        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val number = cursor.getString(0)
                    val date = cursor.getLong(2)
                    // Only use if the call log entry is very recent (within last 10 seconds)
                    val ageMs = System.currentTimeMillis() - date
                    if (ageMs in 0..10000 && !number.isNullOrBlank()) {
                        Log.d(TAG, "Call log query: found number=$number (age=${ageMs}ms)")
                        number
                    } else {
                        Log.d(TAG, "Call log query: entry too old (age=${ageMs}ms) or blank")
                        null
                    }
                } else null
            }
        }.onFailure {
            Log.w(TAG, "Call log query failed: ${it.message}")
        }.getOrNull()
    }

    companion object {
        private const val TAG = "CallerIdentityMgr"
        /** Max time to wait for the phone number when not immediately available. */
        private const val NUMBER_WAIT_MS = 1500L
    }
}
