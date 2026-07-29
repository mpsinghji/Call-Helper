package com.callhandler.service.identity

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.callhandler.service.audio.VoipAnnouncementService
import com.callhandler.service.core.CallHandlerService
import com.callhandler.service.settings.SettingsManager

/**
 * Listens for:
 * 1. **Truecaller** incoming-call identification → forwards to [CallHandlerService]
 * 2. **VoIP app** incoming calls (WhatsApp, Instagram, Snapchat, etc.)
 *    → announces through Bluetooth via [VoipAnnouncementService]
 */
class CallNotificationListener : NotificationListenerService() {

    private val settings by lazy { SettingsManager(this) }

    /**
     * Tracks recently announced VoIP calls to avoid duplicate announcements
     * for the same notification being re-posted.
     * Key = "${packageName}:${notification_key}", expires naturally on removal.
     */
    private val announcedVoipCalls = mutableSetOf<String>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 1. Check for VoIP incoming call
        if (sbn.packageName in VoipCallDetector.KNOWN_PACKAGES) {
            handleVoipNotification(sbn)
            return
        }

        // 2. Check for Truecaller caller-ID
        if (sbn.packageName in TRUECALLER_PACKAGES) {
            handleTruecaller(sbn)
            return
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Clean up tracking when notification is dismissed (call ended/missed)
        if (sbn.packageName in VoipCallDetector.KNOWN_PACKAGES) {
            announcedVoipCalls.remove(sbn.key)
        }
    }

    // ------------------------------------------------------------ VoIP calls

    private fun handleVoipNotification(sbn: StatusBarNotification) {
        if (!settings.voipAnnouncementEnabled) return

        val extras = sbn.notification.extras
        val notifTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val notifText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        
        Log.d(TAG, "VoIP notification from ${sbn.packageName}: title='$notifTitle', text='$notifText', key=${sbn.key}")

        // Avoid duplicate announcement for the same notification
        if (sbn.key in announcedVoipCalls) {
            Log.d(TAG, "Already announced this notification key: ${sbn.key}")
            return
        }

        val callInfo = VoipCallDetector.detect(sbn)
        if (callInfo == null) {
            Log.d(TAG, "Not a VoIP call: ${sbn.packageName} (${getNotifSummary(sbn)})")
            return
        }

        // Mark as announced before starting the service
        announcedVoipCalls.add(sbn.key)

        val announcementText = callInfo.toAnnouncementText()
        Log.i(TAG, "VoIP call detected: $announcementText [key=${sbn.key}]")
        VoipAnnouncementService.announce(this, announcementText)
    }

    // ----------------------------------------------------------- Truecaller

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

    // ---------------------------------------------------------------- utils

    private fun getNotifSummary(sbn: StatusBarNotification): String {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        return "title='$title', text='$text', category=${sbn.notification.category}"
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
            "missed call", "what's new", "verify", "sale", "discount",
            "missed video call", "missed voice call", "call ended", 
            "declined", "busy", "unavailable", "not answered"
        )
    }
}
