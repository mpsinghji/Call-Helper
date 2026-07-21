package com.callhandler.service.identity

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
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
 * Resolves who is calling, in priority order:
 *  1. Saved contact name
 *  2. Truecaller identification
 *  3. "Unknown caller"
 *
 * Identity is published through a StateFlow, so subscribers always see the
 * latest value no matter when they subscribe (the previous SharedFlow
 * dropped updates that arrived before the collector started — which is why
 * saved contacts could be announced as "Unknown": on Android 10+ the number
 * only arrives in the SECOND ringing broadcast, and its lookup result was
 * emitted before anyone was listening).
 *
 * A contact or Truecaller name arriving during the pre-announcement wait
 * completes the wait immediately, so the first announcement uses it.
 */
class CallerIdentityManager(private val context: Context) {

    private val _identity = MutableStateFlow<CallerIdentity?>(null)
    val identity: StateFlow<CallerIdentity?> = _identity

    private var currentNumber: String? = null
    private var pendingResolve: CompletableDeferred<CallerIdentity>? = null
    private var lastTruecallerName: String? = null

    /**
     * Contact lookup first; if that fails, wait up to [waitMs] for either a
     * late number broadcast resolving to a contact, or a Truecaller name.
     */
    suspend fun resolveIdentity(number: String?, waitMs: Long): CallerIdentity {
        currentNumber = number
        lastTruecallerName = null

        if (number != null) {
            val contactName = lookupContact(number)
            if (contactName != null) {
                val id = CallerIdentity(number, contactName, IdentitySource.CONTACT)
                _identity.value = id
                return id
            }
        }

        val deferred = CompletableDeferred<CallerIdentity>()
        pendingResolve = deferred
        val resolved = withTimeoutOrNull(waitMs) { deferred.await() }
        pendingResolve = null

        val id = resolved ?: CallerIdentity.unknown(currentNumber)
        if (_identity.value == null) _identity.value = id
        return id
    }

    /**
     * Called when a later PHONE_STATE broadcast supplies the number the
     * first broadcast omitted. Runs the contact lookup and publishes a hit.
     * Call from a background dispatcher (does a ContentResolver query).
     */
    fun onNumberAvailable(number: String) {
        if (number == currentNumber) return
        currentNumber = number
        val name = runCatching { lookupContactBlocking(number) }.getOrNull() ?: return
        publish(CallerIdentity(number, name, IdentitySource.CONTACT))
    }

    /** Called by the notification listener when Truecaller identifies the caller. */
    fun onTruecallerName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == lastTruecallerName) return // duplicate
        lastTruecallerName = trimmed
        // Saved contact name always wins over Truecaller.
        if (_identity.value?.source == IdentitySource.CONTACT) return
        publish(CallerIdentity(currentNumber, trimmed, IdentitySource.TRUECALLER))
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

    // -------------------------------------------------------------- contacts

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
}