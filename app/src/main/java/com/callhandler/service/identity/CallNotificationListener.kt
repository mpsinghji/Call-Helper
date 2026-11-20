package com.callhandler.service.identity

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.callhandler.service.audio.VoipAnnouncementService
import com.callhandler.service.core.CallHandlerService
import com.callhandler.service.debug.CallDebugTracker
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
        // Clean up tracking when notification is dismissed (call ended/missed/declined)
        if (sbn.packageName in VoipCallDetector.KNOWN_PACKAGES) {
            announcedVoipCalls.remove(sbn.key)
            val session = VoipCallDetector.onNotificationRemoved(sbn)
            val sessionId = session?.sessionId ?: 0L
            Log.i(TAG, "VoIP notification removed: key=${sbn.key}, session=$sessionId — stopping announcement")
            VoipAnnouncementService.stopAnnouncement(this, sessionId, sbn.key)
        }
    }

    // ------------------------------------------------------------ VoIP calls

    private fun handleVoipNotification(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val notifTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val notifText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        
        Log.d(TAG, "VoIP notification from ${sbn.packageName}: title='$notifTitle', text='$notifText', key=${sbn.key}")

        // If this notification was already announced, check if it transitioned to declined/ended/answered
        if (sbn.key in announcedVoipCalls) {
            val terminalResult = VoipCallDetector.checkNotificationTerminalEvent(sbn)
            if (terminalResult != null) {
                Log.i(TAG, "VoIP notification transitioned to terminal state: $terminalResult — stopping announcement")
                announcedVoipCalls.remove(sbn.key)
                VoipAnnouncementService.stopAnnouncement(
                    context = this,
                    sessionId = terminalResult.sessionId,
                    notificationKey = terminalResult.notificationKey
                )
                if (terminalResult.isDeclinedOrEnded) {
                    VoipCallDetector.onNotificationRemoved(sbn)
                }
            } else {
                Log.d(TAG, "Already announced this notification key and still active: ${sbn.key}")
            }
            return
        }

        val callInfo = VoipCallDetector.detect(sbn)
        if (callInfo == null) {
            Log.d(TAG, "VoIP announcement blocked or not a call: ${sbn.packageName} (${getNotifSummary(sbn)})")
            return
        }

        if (!settings.voipAnnouncementEnabled) {
            Log.d(TAG, "VoIP announcement disabled in settings: ${sbn.packageName}")
            CallDebugTracker.onAnnouncementBlocked("DISABLED_IN_SETTINGS")
            return
        }

        // Mark as announced before starting the service
        announcedVoipCalls.add(sbn.key)

        val announcementText = callInfo.toAnnouncementText()
        Log.i(TAG, "VoIP call detected: $announcementText [key=${sbn.key}, session=${callInfo.sessionId}]")
        VoipAnnouncementService.announce(
            context = this,
            text = announcementText,
            sessionId = callInfo.sessionId,
            notificationKey = callInfo.notificationKey
        )
    }

    // ----------------------------------------------------------- Truecaller

    private fun handleTruecaller(sbn: StatusBarNotification) {
        // Step 1: Proof of concept & diagnostic-only inspection
        // Captures actual metadata and extras without mutating production identity or announcing
        val report = TruecallerNotificationDiagnosticInspector.inspect(sbn)
        Log.d(TAG, "Truecaller diagnostic capture: ${report.summary}")
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

    /**
     * Extracts a phone number from text (e.g., "+91 98169 39576" or "070183 08746").
     * Returns the raw matched text if it looks like a phone number, null otherwise.
     */
    private fun extractPhoneNumberFromText(text: String): String? {
        if (text.isBlank()) return null
        // Match sequences of digits, spaces, dashes, parens that form a phone number (7+ digits)
        val phoneRegex = Regex("""[+]?[\d\s\-()]{7,25}""")
        val match = phoneRegex.find(text) ?: return null
        val candidate = match.value.trim()
        val digitsOnly = candidate.replace(Regex("[^0-9]"), "")
        return if (digitsOnly.length >= 7) candidate else null
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
