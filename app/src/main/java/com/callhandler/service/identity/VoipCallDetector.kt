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

    /** Explicit ringing language accepted only from notification boilerplate fields. */
    private val EXPLICIT_INCOMING_CALL_HINT = Regex(
        "\\b(incoming(?:\\s+(?:voice|audio|video))?\\s+call|" +
            "incoming|ringing|is\\s+calling|calling\\s+you)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Discord chat text commonly follows "sender: message". */
    private val DISCORD_MESSAGE_TEXT = Regex("^.{1,40}:\\s+.+$")

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

        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val combined = "$title $text".lowercase()
        val template = extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()

        // MessagingStyle is Android's structural signal for a chat/message
        // notification. Never infer a call from words inside its message body.
        if (template.endsWith("MessagingStyle")) {
            Log.d(TAG, "VoipDetect: REJECTED_MESSAGING_STYLE (${config.displayName})")
            return null
        }

        val isCallCategory = notification.category == Notification.CATEGORY_CALL
        val hasFullScreenIntent = notification.fullScreenIntent != null
        val hasStructuralCallSignal = isCallCategory || hasFullScreenIntent

        // Reject known non-call states before accepting any textual fallback.
        val hasNoiseHint = config.noiseHints.any { combined.contains(it) }
        if (hasNoiseHint) {
            Log.d(TAG, "VoipDetect: REJECTED_NOISE (${config.displayName}): '$title' / '$text'")
            return null
        }

        // Discord channel messages commonly use "#channel" as the title and
        // "sender: message" as text. Reject that shape unless Android itself
        // marks the notification as an active incoming call.
        val looksLikeDiscordMessage = config.packageName == "com.discord" &&
            (title.contains('#') || DISCORD_MESSAGE_TEXT.matches(text.trim()))
        if (!hasStructuralCallSignal && looksLikeDiscordMessage) {
            Log.d(TAG, "VoipDetect: REJECTED_DISCORD_MESSAGE: '$title' / '$text'")
            return null
        }

        // Some OEM/app versions omit CATEGORY_CALL. Their fallback must use
        // explicit ringing language from boilerplate fields only; arbitrary
        // chat message bodies are deliberately excluded.
        val boilerplate = "$title $subText"
        val hasExplicitCallHint = EXPLICIT_INCOMING_CALL_HINT.containsMatchIn(boilerplate)
        if (!hasStructuralCallSignal && !hasExplicitCallHint) {
            Log.d(
                TAG,
                "VoipDetect: REJECTED_NO_CALL_SIGNAL (${config.displayName}, " +
                    "category=${notification.category}, fullScreen=$hasFullScreenIntent)"
            )
            return null
        }

        val videoFields = "$title $text $subText".lowercase()
        val isVideo = config.videoHints.any { videoFields.contains(it) }
        val callType = if (isVideo) "video call" else "voice call"
        val callerName = extractCallerName(title, text, config) ?: run {
            Log.d(TAG, "VoipDetect: REJECTED_NO_CALLER_NAME (${config.displayName})")
            return null
        }

        Log.i(
            TAG,
            "VoipDetect: ACCEPTED ${config.displayName} $callType from '$callerName' " +
                "(category=${notification.category}, fullScreen=$hasFullScreenIntent, " +
                "explicitHint=$hasExplicitCallHint)"
        )
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
