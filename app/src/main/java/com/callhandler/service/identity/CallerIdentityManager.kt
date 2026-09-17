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
 * Resolves who is calling according to strict priority:
 *  1. Saved contact name  (instant, from any number source - STOP, never replaced by Truecaller)
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
    private var pendingResolve: CompletableDeferred<CallerIdentity>? = null
    private var lastTruecallerName: String? = null
    private var callLogObserver: ContentObserver? = null

    /**
     * Registers an incoming number delivered by either number source.
     * The first valid contact match (or Truecaller name) completes the
     * pending resolution; subsequent duplicates are ignored.
     */
    fun onIncomingNumber(number: String, source: String) {
        if (number == currentNumber) return
        currentNumber = number
        Log.d(TAG, "Incoming number source=$source, starting lookup")
        DebugLogStore.log("CALLER_ID", "CALL = RINGING (number=$number, source=$source)")

        val name = runCatching { lookupContactBlocking(number) }.getOrNull()
        if (name != null) {
            Log.d(TAG, "Contacts lookup: MATCH -> $name")
            DebugLogStore.log("CALLER_ID", "CONTACT RESULT = $name")
            DebugLogStore.log("CALLER_ID", "SELECTED SOURCE = CONTACT")
            DebugLogStore.log("CALLER_ID", "FINAL ANNOUNCEMENT = $name")
            publish(CallerIdentity(number, name, IdentitySource.CONTACT))
        } else {
            Log.d(TAG, "Contacts lookup: NO_MATCH (waiting for Truecaller)")
            DebugLogStore.log("CALLER_ID", "CONTACT RESULT = NOT FOUND")
        }
    }

    /** Called when Truecaller identifies the caller (via Accessibility or Notification). */
    fun onTruecallerName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == lastTruecallerName) return

        // Reject Truecaller UI placeholder text (not a real caller name)
        if (isTruecallerUIText(trimmed)) {
            Log.d(TAG, "Truecaller name rejected (UI text): '$trimmed'")
            DebugLogStore.log("CALLER_ID", "TRUECALLER NAME REJECTED (UI text): $trimmed")
            return
        }

        lastTruecallerName = trimmed
        Log.d(TAG, "Resolved from Truecaller: $trimmed")
        DebugLogStore.log("CALLER_ID", "TRUECALLER NAME = $trimmed")

        // Rule: If contacts matched first, never replace with Truecaller
        if (_identity.value?.source == IdentitySource.CONTACT) {
            DebugLogStore.log("CALLER_ID", "TRUECALLER IGNORED (Contact already resolved)")
            return
        }

        DebugLogStore.log("CALLER_ID", "SELECTED SOURCE = TRUECALLER")
        DebugLogStore.log("CALLER_ID", "FINAL ANNOUNCEMENT = $trimmed")
        publish(CallerIdentity(currentNumber, trimmed, IdentitySource.TRUECALLER))
    }

    /**
     * Waits up to [waitMs] for the first valid identity from Contacts or
     * Truecaller. Returns Unknown if neither source succeeds.
     *
     * @param number  Incoming number from the first RINGING event (may be null).
     * @param source  "CALL_SCREENING" or "PHONE_STATE" for diagnostics.
     */
    suspend fun resolveIdentity(number: String?, source: String, waitMs: Long): CallerIdentity = coroutineScope {
        // Register the first event's number before creating the pending result.
        if (number != null && number != currentNumber) {
            currentNumber = number
            Log.d(TAG, "Incoming number source=$source, starting lookup")
            DebugLogStore.log("CALLER_ID", "CALL = RINGING (number=$number, source=$source)")
        }

        val deferred = CompletableDeferred<CallerIdentity>()
        pendingResolve = deferred

        // 1. Check saved contacts FIRST (instant, authoritative)
        currentNumber?.let { number2 ->
            val contactName = lookupContact(number2)
            if (contactName != null) {
                Log.d(TAG, "Contacts lookup: MATCH -> $contactName")
                DebugLogStore.log("CALLER_ID", "CONTACT RESULT = $contactName")
                DebugLogStore.log("CALLER_ID", "SELECTED SOURCE = CONTACT")
                DebugLogStore.log("CALLER_ID", "FINAL ANNOUNCEMENT = $contactName")
                val id = CallerIdentity(number2, contactName, IdentitySource.CONTACT)
                _identity.value = id
                pendingResolve = null
                return@coroutineScope id
            }
            Log.d(TAG, "Contacts lookup: NO_MATCH")
            DebugLogStore.log("CALLER_ID", "CONTACT RESULT = NOT FOUND (waiting for Truecaller ${waitMs}ms)")
        }

        // 2. If no number yet, start call log observer + wait for number from any source
        if (currentNumber == null) {
            Log.d(TAG, "No number \u2014 starting call log observer + waiting up to ${NUMBER_WAIT_MS}ms")
            DebugLogStore.log("CALLER_ID", "NO NUMBER \u2014 waiting ${NUMBER_WAIT_MS}ms (observer + broadcast + listener)")

            // Start watching the call log for new entries (reactive)
            startCallLogObserver()

            val waitEnd = System.currentTimeMillis() + NUMBER_WAIT_MS
            while (currentNumber == null && System.currentTimeMillis() < waitEnd) {
                delay(50)
            }

            // Stop the observer — either we got the number or we timed out
            stopCallLogObserver()

            // Retry contacts lookup if number arrived during the wait
            currentNumber?.let { lateNum ->
                val contactName = lookupContact(lateNum)
                if (contactName != null) {
                    Log.d(TAG, "Contacts lookup (late number): MATCH -> $contactName")
                    DebugLogStore.log("CALLER_ID", "CONTACT RESULT = $contactName (late number)")
                    DebugLogStore.log("CALLER_ID", "SELECTED SOURCE = CONTACT")
                    DebugLogStore.log("CALLER_ID", "FINAL ANNOUNCEMENT = $contactName")
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
        if (currentNumber == null) {
            Log.d(TAG, "Trying call log query fallback...")
            DebugLogStore.log("CALLER_ID", "CALL_LOG_FALLBACK \u2014 querying latest call")
            val callLogNumber = queryLatestIncomingNumber()
            if (callLogNumber != null) {
                currentNumber = callLogNumber
                Log.d(TAG, "Call log fallback: got number $callLogNumber")
                DebugLogStore.log("CALLER_ID", "CALL_LOG_FALLBACK = $callLogNumber")
                DebugLogStore.log("CALLER_ID", "CALL = RINGING (number=$callLogNumber, source=CALL_LOG)")
                val contactName = lookupContact(callLogNumber)
                if (contactName != null) {
                    Log.d(TAG, "Contacts lookup (call log number): MATCH -> $contactName")
                    DebugLogStore.log("CALLER_ID", "CONTACT RESULT = $contactName (call log)")
                    DebugLogStore.log("CALLER_ID", "SELECTED SOURCE = CONTACT")
                    DebugLogStore.log("CALLER_ID", "FINAL ANNOUNCEMENT = $contactName")
                    val id = CallerIdentity(callLogNumber, contactName, IdentitySource.CONTACT)
                    _identity.value = id
                    pendingResolve = null
                    return@coroutineScope id
                }
                Log.d(TAG, "Contacts lookup (call log number): NO_MATCH")
                DebugLogStore.log("CALLER_ID", "CONTACT RESULT = NOT FOUND (call log)")
            } else {
                // No number from ANY source — Truecaller can't help either without a number
                Log.d(TAG, "No number from any source \u2014 announcing Unknown immediately")
                DebugLogStore.log("CALLER_ID", "CALL_LOG_FALLBACK = NO NUMBER")
                DebugLogStore.log("CALLER_ID", "SELECTED SOURCE = UNKNOWN (no number from any source)")
                DebugLogStore.log("CALLER_ID", "FINAL ANNOUNCEMENT = Unknown caller")
                val id = CallerIdentity.unknown(null)
                _identity.value = id
                pendingResolve = null
                return@coroutineScope id
            }
        }

        // 3. Check if Truecaller overlay is already visible right now
        val existingA11yInfo = TruecallerAccessibilityService.instance?.inspectCurrentWindowsForCaller()
        if (existingA11yInfo?.announcementName != null) {
            val tcName = existingA11yInfo.announcementName!!
            Log.i(TAG, "Truecaller window inspected immediately: '$tcName'")
            DebugLogStore.log("CALLER_ID", "TRUECALLER OVERLAY INSTANT MATCH = $tcName")
            DebugLogStore.log("CALLER_ID", "SELECTED SOURCE = TRUECALLER")
            DebugLogStore.log("CALLER_ID", "FINAL ANNOUNCEMENT = $tcName")
            val id = CallerIdentity(currentNumber, tcName, IdentitySource.TRUECALLER)
            _identity.value = id
            pendingResolve = null
            return@coroutineScope id
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
            info.announcementName?.let { onTruecallerName(it) }
        }

        // 5. Poller job: inspects interactive windows every 250ms during wait period
        val pollerJob = launch(Dispatchers.Default) {
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < waitMs && !deferred.isCompleted) {
                delay(250)
                val info = TruecallerAccessibilityService.instance?.inspectCurrentWindowsForCaller()
                if (info?.announcementName != null) {
                    onTruecallerName(info.announcementName!!)
                    break
                }
            }
        }

        try {
            val resolved = withTimeoutOrNull(waitMs) { deferred.await() }
            pendingResolve = null

            val id = resolved
                ?: _identity.value?.takeIf { it.displayName != null }
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

    private fun publish(id: CallerIdentity) {
        _identity.value = id
        pendingResolve?.let { if (!it.isCompleted) it.complete(id) }
    }

    fun reset() {
        stopCallLogObserver()
        currentNumber = null
        lastTruecallerName = null
        pendingResolve?.cancel()
        pendingResolve = null
        _identity.value = null
        TruecallerAccessibilityService.onCallerInfoDetected = null
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
        val lower = text.lowercase()
        return lower.contains("search numbers") ||
               lower.contains("names & more") ||
               lower.contains("get truecaller") ||
               lower.contains("identify unknown") ||
               lower.contains("block spam") ||
               lower.contains("who called")
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
