package com.callhandler.service.identity

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.callhandler.service.core.CallHandlerService
import com.callhandler.service.settings.SettingsManager

/**
 * Listens for two kinds of notifications:
 *
 * 1. **Truecaller** incoming-call identification — forwarded to
 *    [CallHandlerService] so the announcement can use the identified name.
 *    Non-call Truecaller notifications (profile views, promotions, backups,
 *    Premium offers, statistics, spam digests…) are filtered out.
 *
 * 2. **WhatsApp** incoming voice/video calls — announced only; Android has
 *    no public API to answer them, so voice commands stay disabled. When
 *    the WhatsApp call notification is removed (user answered, rejected, or
 *    caller hung up) we tell the service to stop immediately.
 */
class CallNotificationListener : NotificationListenerService() {

    private lateinit var settings: SettingsManager

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        when (sbn.packageName) {
            in TRUECALLER_PACKAGES -> handleTruecaller(sbn)
            in WHATSAPP_PACKAGES -> handleWhatsApp(sbn)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName in WHATSAPP_PACKAGES && isWhatsAppCallNotification(sbn)) {
            // Covers: user manually answered the WhatsApp call, rejected it,
            // or the caller hung up — stop announcements immediately.
            // runCatching: startService from background throws if the
            // service isn't running — and then there is nothing to stop.
            runCatching {
                startService(
                    Intent(this, CallHandlerService::class.java)
                        .setAction(CallHandlerService.ACTION_WHATSAPP_DISMISSED)
                )
            }
        }
    }

    // ------------------------------------------------------------ Truecaller

    private fun handleTruecaller(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val combined = "$title $text"

        // Only accept notifications that clearly represent an active
        // incoming call. Category CATEGORY_CALL is the strongest signal;
        // keyword matching is the fallback.
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

        // runCatching: if the phone isn't actually ringing the service is
        // not running, and a background startService would throw — the
        // update would be stale anyway, so dropping it is correct.
        runCatching {
            startService(
                Intent(this, CallHandlerService::class.java)
                    .setAction(CallHandlerService.ACTION_TRUECALLER_UPDATE)
                    .putExtra(CallHandlerService.EXTRA_CALLER_NAME, callerName)
            )
        }
    }

    /**
     * Truecaller call notifications typically look like:
     *   title = "Incoming call" / "Identified call", text = "John Doe"
     * or
     *   title = "John Doe", text = "Incoming call…"
     * Pick whichever half is NOT the boilerplate.
     */
    private fun extractTruecallerName(title: String, text: String): String? {
        val titleIsBoiler = INCOMING_CALL_HINTS.any { title.contains(it, true) }
        val textIsBoiler = INCOMING_CALL_HINTS.any { text.contains(it, true) }
        val candidate = when {
            titleIsBoiler && !textIsBoiler -> text
            textIsBoiler && !titleIsBoiler -> title
            else -> title
        }.trim()

        // Reject obvious non-names.
        if (candidate.isEmpty()) return null
        if (candidate.length > 60) return null
        if (NOISE_HINTS.any { candidate.contains(it, true) }) return null
        return candidate
    }

    // -------------------------------------------------------------- WhatsApp

    private fun handleWhatsApp(sbn: StatusBarNotification) {
        if (!settings.whatsappEnabled) return
        if (!isWhatsAppCallNotification(sbn)) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        val isVideo = text.contains("video", ignoreCase = true)

        // For call notifications the title is the caller's (WhatsApp) name.
        val callerName = title?.takeIf { it.isNotEmpty() && it.length <= 60 }

        startForegroundService(
            Intent(this, CallHandlerService::class.java)
                .setAction(CallHandlerService.ACTION_WHATSAPP_RINGING)
                .putExtra(CallHandlerService.EXTRA_CALLER_NAME, callerName)
                .putExtra(CallHandlerService.EXTRA_IS_VIDEO, isVideo)
        )
    }

    private fun isWhatsAppCallNotification(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (n.category == Notification.CATEGORY_CALL) return true
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)
            ?.toString().orEmpty()
        return WHATSAPP_CALL_HINTS.any { text.contains(it, ignoreCase = true) }
    }

    companion object {
        private const val TAG = "CallNotifListener"

        private val TRUECALLER_PACKAGES = setOf("com.truecaller")
        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

        /** Phrases that indicate an active incoming call. */
        private val INCOMING_CALL_HINTS = listOf(
            "incoming call", "identified call", "calling", "is calling",
            "incoming from"
        )

        /** Phrases for Truecaller noise we must ignore per the spec. */
        private val NOISE_HINTS = listOf(
            "viewed your profile", "profile view", "premium", "upgrade",
            "offer", "backup", "back up", "stats", "statistics",
            "who searched", "searched for you", "spam report", "digest",
            "missed call", "what's new", "verify", "sale", "discount"
        )

        private val WHATSAPP_CALL_HINTS = listOf(
            "incoming voice call", "incoming video call", "ringing",
            "whatsapp voice call", "whatsapp video call", "calling"
        )
    }
}
