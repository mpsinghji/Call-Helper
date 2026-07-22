package com.callhandler.service.identity

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.callhandler.service.core.CallHandlerService

/**
 * Listens for **Truecaller** incoming-call identification notifications
 * and forwards the identified caller name to [CallHandlerService].
 *
 * Non-call Truecaller notifications (profile views, promotions, etc.)
 * are filtered out.
 */
class CallNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in TRUECALLER_PACKAGES) return
        handleTruecaller(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // No action needed — only Truecaller is observed and it's one-way.
    }

    private fun handleTruecaller(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val combined = "$title $text"

        val isCallCategory = sbn.notification.category == Notification.CATEGORY_CALL
        val looksLikeIncomingCall = INCOMING_CALL_HINTS.any {
            combined.contains(it, ignoreCase = true)
        }
        val looksLikeNoise = NOISE_HINTS.any {
            combined.contains(it, ignoreCase = true)
        }

        if (looksLikeNoise) {
            Log.d(TAG, "Ignoring non-call Truecaller notification: $title")
            return
        }
        if (!isCallCategory && !looksLikeIncomingCall) return

        val callerName = extractTruecallerName(title, text) ?: return

        runCatching {
            startService(
                Intent(this, CallHandlerService::class.java)
                    .setAction(CallHandlerService.ACTION_TRUECALLER_UPDATE)
                    .putExtra(CallHandlerService.EXTRA_CALLER_NAME, callerName)
            )
        }
    }

    private fun extractTruecallerName(title: String, text: String): String? {
        val titleIsBoiler = INCOMING_CALL_HINTS.any { title.contains(it, true) }
        val textIsBoiler = INCOMING_CALL_HINTS.any { text.contains(it, true) }
        val candidate = when {
            titleIsBoiler && !textIsBoiler -> text
            textIsBoiler && !titleIsBoiler -> title
            else -> title
        }.trim()

        if (candidate.isEmpty()) return null
        if (candidate.length > 60) return null
        if (NOISE_HINTS.any { candidate.contains(it, true) }) return null
        return candidate
    }

    companion object {
        private const val TAG = "CallNotifListener"

        private val TRUECALLER_PACKAGES = setOf("com.truecaller")

        private val INCOMING_CALL_HINTS = listOf(
            "incoming call", "identified call", "calling", "is calling",
            "incoming from"
        )

        private val NOISE_HINTS = listOf(
            "viewed your profile", "profile view", "premium", "upgrade",
            "offer", "backup", "back up", "stats", "statistics",
            "who searched", "searched for you", "spam report", "digest",
            "missed call", "what's new", "verify", "sale", "discount"
        )
    }
}
