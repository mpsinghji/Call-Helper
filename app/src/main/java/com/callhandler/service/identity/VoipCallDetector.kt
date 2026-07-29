package com.callhandler.service.identity

import android.app.Notification
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Detects incoming VoIP calls from messaging apps by parsing their
 * notifications. Returns a [VoipCallInfo] if the notification looks
 * like an incoming call, or null otherwise.
 */
object VoipCallDetector {

    private const val TAG = "VoipCallDetector"

    /**
     * Parsed VoIP call information.
     *
     * @param appDisplayName  Human-readable app name (e.g. "WhatsApp")
     * @param callType        "voice call", "video call", or "call"
     * @param callerName      Name extracted from the notification
     * @param packageName     Original package name
     */
    data class VoipCallInfo(
        val appDisplayName: String,
        val callType: String,
        val callerName: String,
        val packageName: String
    ) {
        /** e.g. "WhatsApp voice call from John" */
        fun toAnnouncementText(): String =
            "$appDisplayName $callType from $callerName"
    }

    /** All known VoIP app configurations. */
    private data class AppConfig(
        val packageName: String,
        val displayName: String,
        /** Hints in notification text/title that indicate an incoming call. */
        val callHints: List<String>,
        /** Hints that identify a VIDEO call (if none match, defaults to "voice call"). */
        val videoHints: List<String> = listOf("video"),
        /** Hints that should cause the notification to be ignored (not a call). */
        val noiseHints: List<String> = emptyList()
    )

    private val APPS = listOf(
        AppConfig(
            packageName = "com.whatsapp",
            displayName = "WhatsApp",
            callHints = listOf("incoming", "call", "calling", "ringing"),
            videoHints = listOf("video"),
            noiseHints = listOf("missed", "message", "backup", "end-to-end", "declined", "unavailable", "busy", "ended", "cancelled")
        ),
        AppConfig(
            packageName = "com.whatsapp.w4b",
            displayName = "WhatsApp Business",
            callHints = listOf("incoming", "call", "calling", "ringing"),
            videoHints = listOf("video"),
            noiseHints = listOf("missed", "message", "backup", "declined", "unavailable", "busy", "ended", "cancelled")
        ),
        AppConfig(
            packageName = "com.instagram.android",
            displayName = "Instagram",
            callHints = listOf("incoming", "call", "calling", "audio", "video"),
            videoHints = listOf("video"),
            noiseHints = listOf("liked", "commented", "followed", "mentioned", "story", "reel", "message", "tagged")
        ),
        AppConfig(
            packageName = "com.snapchat.android",
            displayName = "Snapchat",
            callHints = listOf("incoming", "call", "calling"),
            videoHints = listOf("video"),
            noiseHints = listOf("snap", "chat", "story", "memory", "streak")
        ),
        AppConfig(
            packageName = "org.telegram.messenger",
            displayName = "Telegram",
            callHints = listOf("incoming", "call", "calling"),
            videoHints = listOf("video"),
            noiseHints = listOf("message", "channel", "group")
        ),
        AppConfig(
            packageName = "com.facebook.orca",
            displayName = "Messenger",
            callHints = listOf("incoming", "call", "calling", "ringing"),
            videoHints = listOf("video"),
            noiseHints = listOf("message", "sent", "photo", "sticker", "reacted")
        ),
        AppConfig(
            packageName = "com.discord",
            displayName = "Discord",
            callHints = listOf("incoming", "call", "calling", "ringing"),
            videoHints = listOf("video"),
            noiseHints = listOf("message", "mentioned", "server")
        ),
        AppConfig(
            packageName = "com.google.android.apps.tachyon",
            displayName = "Google Meet",
            callHints = listOf("incoming", "call", "calling", "ringing"),
            videoHints = listOf("video"),
            noiseHints = listOf("meeting", "scheduled")
        ),
        AppConfig(
            packageName = "com.skype.raider",
            displayName = "Skype",
            callHints = listOf("incoming", "call", "calling", "ringing"),
            videoHints = listOf("video"),
            noiseHints = listOf("message", "chat")
        ),
        AppConfig(
            packageName = "com.viber.voip",
            displayName = "Viber",
            callHints = listOf("incoming", "call", "calling", "ringing"),
            videoHints = listOf("video"),
            noiseHints = listOf("message", "sticker")
        ),
        AppConfig(
            packageName = "com.microsoft.teams",
            displayName = "Teams",
            callHints = listOf("incoming", "call", "calling", "ringing"),
            videoHints = listOf("video"),
            noiseHints = listOf("message", "chat", "meeting", "scheduled")
        ),
        AppConfig(
            packageName = "us.zoom.videomeetings",
            displayName = "Zoom",
            callHints = listOf("incoming", "call", "calling", "ringing", "meeting"),
            videoHints = listOf("video"),
            noiseHints = listOf("scheduled", "reminder")
        ),
        AppConfig(
            packageName = "jp.naver.line.android",
            displayName = "LINE",
            callHints = listOf("incoming", "call", "calling"),
            videoHints = listOf("video"),
            noiseHints = listOf("message", "sticker", "timeline")
        ),
        AppConfig(
            packageName = "com.imo.android.imoim",
            displayName = "imo",
            callHints = listOf("incoming", "call", "calling"),
            videoHints = listOf("video"),
            noiseHints = listOf("message")
        )
    )

    /** Package names we know about — for fast filtering in the listener. */
    val KNOWN_PACKAGES: Set<String> = APPS.map { it.packageName }.toSet()

    private val appConfigMap: Map<String, AppConfig> =
        APPS.associateBy { it.packageName }

    /**
     * Attempts to detect a VoIP call from the given notification.
     *
     * @return [VoipCallInfo] if this looks like an incoming call, null otherwise.
     */
    fun detect(sbn: StatusBarNotification): VoipCallInfo? {
        val config = appConfigMap[sbn.packageName] ?: return null

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val combined = "$title $text".lowercase()

        // PRIORITY CHECK: Reject noise patterns first (missed calls, messages, etc.)
        // Even if they contain call keywords, noise patterns should be filtered out
        val hasNoiseHint = config.noiseHints.any { combined.contains(it) }
        if (hasNoiseHint) {
            Log.d(TAG, "Rejected ${config.displayName} notification (noise detected): '$title' / '$text'")
            return null
        }

        // Must look like a call: either CATEGORY_CALL or text contains call hints
        val isCallCategory = sbn.notification.category == Notification.CATEGORY_CALL
        val hasCallHint = config.callHints.any { combined.contains(it) }

        if (!isCallCategory && !hasCallHint) return null

        // Determine call type
        val isVideo = config.videoHints.any { combined.contains(it) }
        val callType = if (isVideo) "video call" else "voice call"

        // Extract caller name — usually the title for most apps
        val callerName = extractCallerName(title, text, config) ?: return null

        Log.i(TAG, "Detected ${config.displayName} $callType from '$callerName'")
        return VoipCallInfo(
            appDisplayName = config.displayName,
            callType = callType,
            callerName = callerName,
            packageName = sbn.packageName
        )
    }

    /**
     * Extract the caller name from notification fields.
     *
     * Most VoIP apps put the caller name in the title and the call
     * description ("Incoming voice call") in the text. Some apps
     * reverse this. We pick whichever field does NOT look like a
     * boilerplate call description.
     */
    private fun extractCallerName(
        title: String,
        text: String,
        config: AppConfig
    ): String? {
        val titleLower = title.lowercase()
        val textLower = text.lowercase()

        val titleIsBoiler = config.callHints.any { titleLower.contains(it) }
        val textIsBoiler = config.callHints.any { textLower.contains(it) }

        val candidate = when {
            // Title is "Incoming voice call", text is the name
            titleIsBoiler && !textIsBoiler && text.isNotBlank() -> text
            // Text is "Incoming voice call", title is the name (most common)
            textIsBoiler && !titleIsBoiler && title.isNotBlank() -> title
            // Neither is boiler — prefer title
            !titleIsBoiler && title.isNotBlank() -> title
            // Both are boiler or title is empty
            text.isNotBlank() -> text
            else -> title
        }.trim()

        if (candidate.isEmpty()) return null
        if (candidate.length > 80) return null  // probably not a name
        return candidate
    }
}
