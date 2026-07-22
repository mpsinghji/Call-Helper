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
 * 
 * **VoIP/Messaging app calls are ignored** - WhatsApp, Telegram, Discord,
 * Skype, etc. calls are not announced. Only cellular phone calls are announced.
 */
class CallNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Explicitly block VoIP/messaging apps - we only want cellular calls
        if (sbn.packageName in BLOCKED_VOIP_PACKAGES) {
            Log.d(TAG, "Ignoring VoIP/messaging app: ${sbn.packageName}")
            return
        }
        
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

        /**
         * VoIP and messaging apps to explicitly block.
         * We only want cellular phone calls, not app-based calls.
         */
        private val BLOCKED_VOIP_PACKAGES = setOf(
            "com.whatsapp",              // WhatsApp
            "com.whatsapp.w4b",          // WhatsApp Business
            "org.telegram.messenger",    // Telegram
            "com.telegram.messenger.web", // Telegram Web
            "com.skype.raider",          // Skype
            "com.discord",               // Discord
            "us.zoom.videomeetings",     // Zoom
            "com.microsoft.teams",       // Microsoft Teams
            "com.google.android.apps.tachyon", // Google Duo/Meet
            "com.facebook.orca",         // Facebook Messenger
            "com.viber.voip",            // Viber
            "jp.naver.line.android",     // LINE
            "com.imo.android.imoim",     // imo
            "com.snapchat.android",      // Snapchat
            "kik.android",               // Kik
            "com.instagram.android",     // Instagram
            "com.twitter.android",       // Twitter/X
            "com.google.android.apps.googlevoice", // Google Voice
            "com.rebtel.client"          // Rebtel
        )

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
