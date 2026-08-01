package com.callhandler.service.identity

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.callhandler.service.core.CallerIdentity
import com.callhandler.service.core.IdentitySource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Resolves who is calling:
 *  1. Saved contact name  (instant, from any number source)
 *  2. Truecaller           (waits up to [resolveIdentity.waitMs])
 *  3. "Unknown caller"     (fallback)
 *
 * The incoming number is delivered by either Android's call-screening
 * service (reliable) or the PHONE_STATE broadcast (best effort). Both
 * sources funnel into the same event-driven pipeline; the first valid
 * identity completes resolution, and saved Contacts remain authoritative.
 *
 * Identity is published through a StateFlow for subscribers.
 */
class CallerIdentityManager(private val context: Context) {

    private val _identity = MutableStateFlow<CallerIdentity?>(null)
    val identity: StateFlow<CallerIdentity?> = _identity

    private var currentNumber: String? = null
    private var pendingResolve: CompletableDeferred<CallerIdentity>? = null
    private var lastTruecallerName: String? = null

    /**
     * Registers an incoming number delivered by either number source.
     * The first valid contact match (or Truecaller name) completes the
     * pending resolution; subsequent duplicates are ignored.
     *
     * @param number   Phone number, already known non-blank.
     * @param source   "CALL_SCREENING" or "PHONE_STATE" for diagnostics.
     */
    fun onIncomingNumber(number: String, source: String) {
        if (number == currentNumber) return
        currentNumber = number
        Log.d(TAG, "Incoming number source=$source, starting lookup")

        val name = runCatching { lookupContactBlocking(number) }.getOrNull()
        if (name != null) {
            Log.d(TAG, "Contacts lookup: MATCH -> $name")
            publish(CallerIdentity(number, name, IdentitySource.CONTACT))
        } else {
            Log.d(TAG, "Contacts lookup: NO_MATCH (waiting for Truecaller)")
        }
    }

    /** Called by the notification listener when Truecaller identifies the caller. */
    fun onTruecallerName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == lastTruecallerName) return
        lastTruecallerName = trimmed
        Log.d(TAG, "Resolved from Truecaller: $trimmed")
        if (_identity.value?.source == IdentitySource.CONTACT) return
        publish(CallerIdentity(currentNumber, trimmed, IdentitySource.TRUECALLER))
    }

    /**
     * Waits up to [waitMs] for the first valid identity from Contacts or
     * Truecaller. Returns Unknown if neither source succeeds.
     *
     * @param number  Incoming number from the first RINGING event (may be null).
     * @param source  "CALL_SCREENING" or "PHONE_STATE" for diagnostics.
     */
    suspend fun resolveIdentity(number: String?, source: String, waitMs: Long): CallerIdentity {
        // Register the first event's number before creating the pending result.
        if (number != null && number != currentNumber) {
            currentNumber = number
            Log.d(TAG, "Incoming number source=$source, starting lookup")
        }

        val deferred = CompletableDeferred<CallerIdentity>()
        pendingResolve = deferred

        // If a number already arrived (screening or PHONE_STATE), look it
        // up immediately before waiting for late deliveries.
        currentNumber?.let { number2 ->
            val contactName = lookupContact(number2)
            if (contactName != null) {
                Log.d(TAG, "Contacts lookup: MATCH -> $contactName")
                val id = CallerIdentity(number2, contactName, IdentitySource.CONTACT)
                _identity.value = id
                pendingResolve = null
                return id
            }
            Log.d(TAG, "Contacts lookup: NO_MATCH")
        } ?: Log.d(TAG, "Contacts lookup: SKIPPED_NO_NUMBER")

        // Check whether a number/Truecaller event completed resolution
        // while the contact lookup above was suspended on IO.
        if (deferred.isCompleted) {
            pendingResolve = null
            val id = deferred.await()
            if (_identity.value == null) _identity.value = id
            return id
        }

        // Wait for Truecaller or a late number delivery.
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
                CallerIdentity.unknown(currentNumber)
            }
        if (_identity.value == null) _identity.value = id
        return id
    }

    private fun publish(id: CallerIdentity) {
        _identity.value = id
        pendingResolve?.let { if (!it.isCompleted) it.complete(id) }
    }

    fun reset() {
        currentNumber = null
        lastTruecallerName = null
        pendingResolve?.cancel()
        pendingResolve = null
        _identity.value = null
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

    companion object {
        private const val TAG = "CallerIdentityMgr"
    }
}
