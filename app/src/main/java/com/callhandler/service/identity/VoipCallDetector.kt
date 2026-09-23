package com.callhandler.service.identity

import android.app.Notification
import android.service.notification.StatusBarNotification
import android.util.Log
import com.callhandler.service.debug.CallDebugTracker
import com.callhandler.service.debug.DebugCallSource
import com.callhandler.service.debug.DebugLogStore
import java.util.concurrent.ConcurrentHashMap

/**
 * Call direction for VoIP notifications.
 */
enum class VoipCallDirection {
    INCOMING,
    OUTGOING,
    UNKNOWN
}

/**
 * Call state for VoIP notifications.
 */
enum class VoipCallState {
    INCOMING_RINGING,
    INCOMING_ACTIVE,
    OUTGOING_DIALING,
    OUTGOING_RINGING,
    OUTGOING_ACTIVE,
    ENDED,
    UNKNOWN
}

/**
 * Active VoIP session data tracked per notification key.
 */
data class VoipSession(
    val key: String,
    val packageName: String,
    var direction: VoipCallDirection,
    var state: VoipCallState,
    var identity: String?,
    var announced: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Detects incoming VoIP calls from messaging apps by parsing their
 * notifications and tracking session direction.
 *
 * Rules:
 * - INCOMING → announcement allowed (initial ringing only)
 * - OUTGOING → announcement forbidden
 * - UNKNOWN  → announcement forbidden (fail-closed)
 */
object VoipCallDetector {

    private const val TAG = "VoipCallDetector"

    /** Active sessions keyed by notification key (e.g. sbn.key). */
    private val activeSessions = ConcurrentHashMap<String, VoipSession>()

    /** Explicit incoming language accepted from notification boilerplate fields. */
    private val EXPLICIT_INCOMING_CALL_HINT = Regex(
        "\\b(incoming(?:\\s+(?:voice|audio|video))?\\s+call|" +
            "incoming(?:\\s+(?:voice|audio|video))?|" +
            "is\\s+calling|calling\\s+you)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Outgoing setup language. Evaluated before incoming heuristics. */
    private val EXPLICIT_OUTGOING_CALL_HINT = Regex(
        "(?:^\\s*calling(?:\\s|…|\\.{3}|$)|" +
            "\\b(?:dial(?:l)?ing|connecting|outgoing(?:\\s+(?:voice|audio|video))?\\s+call|" +
            "placing\\s+(?:a\\s+)?call)\\b)",
        RegexOption.IGNORE_CASE
    )

    /** Connected/finished states must never start a caller announcement. */
    private val NON_RINGING_CALL_HINT = Regex(
        "\\b(ongoing\\s+call|call\\s+in\\s+progress|connected|reconnecting|" +
            "call\\s+ended|missed(?:\\s+(?:voice|video))?\\s+call|declined|" +
            "busy|unavailable|not\\s+answered|cancelled|canceled)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Duration timer indicating an active/connected call (e.g. "0:01", "12:45"). */
    private val DURATION_TIMESTAMP_REGEX = Regex("^\\d{1,2}:\\d{2}(?::\\d{2})?$")

    /** Discord chat text commonly follows "sender: message". */
    private val DISCORD_MESSAGE_TEXT = Regex("^.{1,40}:\\s+.+$")

    /**
     * Parsed VoIP call information.
     *
     * @param appDisplayName  Human-readable app name (e.g. "WhatsApp")
     * @param callType        "voice call", "video call", or "call"
     * @param callerName      Name extracted from the notification
     * @param packageName     Original package name
     * @param direction       Direction (INCOMING, OUTGOING, UNKNOWN)
     * @param state           Call state
     */
    data class VoipCallInfo(
        val appDisplayName: String,
        val callType: String,
        val callerName: String,
        val packageName: String,
        val direction: VoipCallDirection = VoipCallDirection.INCOMING,
        val state: VoipCallState = VoipCallState.INCOMING_RINGING
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
     * @return [VoipCallInfo] if this is confirmed to be an incoming ringing call
     *         that has not yet been announced; null otherwise.
     */
    fun detect(sbn: StatusBarNotification): VoipCallInfo? {
        val config = appConfigMap[sbn.packageName] ?: return null

        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val combined = "$title $text $subText $bigText"
        val combinedLower = combined.lowercase()
        val template = extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()

        // 1. MessagingStyle is Android's structural signal for chat messages
        if (template.endsWith("MessagingStyle")) {
            Log.d(TAG, "VoipDetect: REJECTED_MESSAGING_STYLE (${config.displayName})")
            return null
        }

        // 2. Reject known noise hints (backup, missed, declined, etc.)
        if (config.noiseHints.any { combinedLower.contains(it) }) {
            logDecision(sbn, config, title, text, VoipCallDirection.UNKNOWN, VoipCallState.ENDED, null, false, "REJECTED_NOISE")
            return null
        }

        // 3. Discord message format check
        val isCallCategory = notification.category == Notification.CATEGORY_CALL
        val hasFullScreenIntent = notification.fullScreenIntent != null
        val hasStructuralCallSignal = isCallCategory || hasFullScreenIntent
        val looksLikeDiscordMessage = config.packageName == "com.discord" &&
            (title.contains('#') || DISCORD_MESSAGE_TEXT.matches(text.trim()))
        if (!hasStructuralCallSignal && looksLikeDiscordMessage) {
            return null
        }

        // 4. Action inspection
        val actions = notification.actions.orEmpty()
        val hasAnswerAction = actions.any { action ->
            val actionTitle = action.title?.toString().orEmpty().lowercase()
            action.semanticAction == 1 || // Notification.Action.SEMANTIC_ACTION_ANSWER
                actionTitle.contains("answer") ||
                actionTitle.contains("accept") ||
                actionTitle.contains("receive")
        }
        val hasHangUpAction = actions.any { action ->
            val actionTitle = action.title?.toString().orEmpty().lowercase()
            action.semanticAction == 2 || // Notification.Action.SEMANTIC_ACTION_REJECT
                actionTitle.contains("decline") ||
                actionTitle.contains("reject") ||
                actionTitle.contains("end call") ||
                actionTitle.contains("hang up") ||
                actionTitle.contains("cancel") ||
                actionTitle == "end"
        }

        // 5. System CallStyle extra checks (API 31+)
        val callTypeExtra = extras.getInt(Notification.EXTRA_CALL_TYPE, -1)
            .takeIf { it != -1 } ?: extras.getInt("android.callType", -1)
        val hasAnswerIntent = extras.containsKey("android.answerIntent")

        // 6. Check existing session for this notification key
        val existingSession = activeSessions[sbn.key]

        // --- SESSION LOCK: Once OUTGOING, stay OUTGOING ---
        if (existingSession != null && existingSession.direction == VoipCallDirection.OUTGOING) {
            val updatedState = when {
                DURATION_TIMESTAMP_REGEX.matches(text.trim()) || NON_RINGING_CALL_HINT.containsMatchIn(combined) ->
                    VoipCallState.OUTGOING_ACTIVE
                text.contains("ringing", ignoreCase = true) ->
                    VoipCallState.OUTGOING_RINGING
                else ->
                    VoipCallState.OUTGOING_DIALING
            }
            existingSession.state = updatedState
            logDecision(sbn, config, title, text, VoipCallDirection.OUTGOING, updatedState, existingSession.identity, false, "OUTGOING_CALL")
            return null
        }

        // --- OUTGOING DETECTION ---
        val matchesExplicitOutgoing = listOf(title, text, subText, bigText).any {
            EXPLICIT_OUTGOING_CALL_HINT.containsMatchIn(it)
        }
        val isRingingWithoutAnswer = text.contains("ringing", ignoreCase = true) &&
            !hasAnswerAction && !hasAnswerIntent &&
            !EXPLICIT_INCOMING_CALL_HINT.containsMatchIn(combined)
        val isOutgoingCallStyle = callTypeExtra == 2 // CallStyle.CALL_TYPE_OUTGOING

        if (matchesExplicitOutgoing || isRingingWithoutAnswer || isOutgoingCallStyle) {
            val callerName = extractCallerName(title, text, config)
            val state = if (text.contains("ringing", ignoreCase = true)) {
                VoipCallState.OUTGOING_RINGING
            } else {
                VoipCallState.OUTGOING_DIALING
            }
            activeSessions[sbn.key] = VoipSession(
                key = sbn.key,
                packageName = sbn.packageName,
                direction = VoipCallDirection.OUTGOING,
                state = state,
                identity = callerName
            )
            logDecision(sbn, config, title, text, VoipCallDirection.OUTGOING, state, callerName, false, "OUTGOING_CALL")
            return null
        }

        // --- ACTIVE / CONNECTED / NON-RINGING DETECTION ---
        val isNonRinging = DURATION_TIMESTAMP_REGEX.matches(text.trim()) ||
            NON_RINGING_CALL_HINT.containsMatchIn(combined) ||
            callTypeExtra == 3 // CallStyle.CALL_TYPE_ONGOING
        if (isNonRinging) {
            val state = if (existingSession?.direction == VoipCallDirection.INCOMING) {
                VoipCallState.INCOMING_ACTIVE
            } else {
                VoipCallState.UNKNOWN
            }
            if (existingSession != null) existingSession.state = state
            logDecision(sbn, config, title, text, existingSession?.direction ?: VoipCallDirection.UNKNOWN, state, existingSession?.identity, false, "NON_RINGING_CALL")
            return null
        }

        // --- INCOMING DETECTION ---
        val hasExplicitIncomingHint = EXPLICIT_INCOMING_CALL_HINT.containsMatchIn(combined)
        val isIncomingCallStyle = callTypeExtra == 1 // CallStyle.CALL_TYPE_INCOMING
        val hasPositiveIncomingEvidence = hasAnswerAction || hasAnswerIntent || isIncomingCallStyle || hasExplicitIncomingHint

        if (hasPositiveIncomingEvidence) {
            val callerName = extractCallerName(title, text, config) ?: run {
                logDecision(sbn, config, title, text, VoipCallDirection.INCOMING, VoipCallState.INCOMING_RINGING, null, false, "REJECTED_NO_CALLER_NAME")
                return null
            }

            val session = activeSessions.getOrPut(sbn.key) {
                VoipSession(
                    key = sbn.key,
                    packageName = sbn.packageName,
                    direction = VoipCallDirection.INCOMING,
                    state = VoipCallState.INCOMING_RINGING,
                    identity = callerName
                )
            }

            if (session.announced) {
                logDecision(sbn, config, title, text, VoipCallDirection.INCOMING, VoipCallState.INCOMING_RINGING, callerName, false, "ALREADY_ANNOUNCED")
                return null
            }

            session.announced = true
            logDecision(sbn, config, title, text, VoipCallDirection.INCOMING, VoipCallState.INCOMING_RINGING, callerName, true, "INCOMING_CALL")

            val videoFields = "$title $text $subText".lowercase()
            val isVideo = config.videoHints.any { videoFields.contains(it) }
            val callType = if (isVideo) "video call" else "voice call"

            return VoipCallInfo(
                appDisplayName = config.displayName,
                callType = callType,
                callerName = callerName,
                packageName = sbn.packageName,
                direction = VoipCallDirection.INCOMING,
                state = VoipCallState.INCOMING_RINGING
            )
        }

        // --- FAIL-CLOSED FOR UNKNOWN DIRECTION ---
        logDecision(sbn, config, title, text, VoipCallDirection.UNKNOWN, VoipCallState.UNKNOWN, null, false, "NO_POSITIVE_INCOMING_EVIDENCE")
        return null
    }

    /**
     * Clean up session tracking when notification is removed.
     */
    fun onNotificationRemoved(sbn: StatusBarNotification) {
        val session = activeSessions.remove(sbn.key)
        if (session != null) {
            val title = session.identity.orEmpty()
            logDecision(
                sbn = sbn,
                config = appConfigMap[sbn.packageName] ?: AppConfig(sbn.packageName, sbn.packageName, emptyList()),
                title = title,
                text = "Call removed",
                direction = session.direction,
                state = VoipCallState.ENDED,
                identity = session.identity,
                allowed = false,
                reason = "NOTIFICATION_REMOVED"
            )
            CallDebugTracker.onCallEnded()
        }
    }

    /**
     * Clear all sessions (e.g. for unit tests or app reset).
     */
    fun clearSessions() {
        activeSessions.clear()
    }

    private fun logDecision(
        sbn: StatusBarNotification,
        config: AppConfig,
        title: String,
        text: String,
        direction: VoipCallDirection,
        state: VoipCallState,
        identity: String?,
        allowed: Boolean,
        reason: String
    ) {
        val category = sbn.notification.category ?: "null"
        val fullScreen = sbn.notification.fullScreenIntent != null
        val normalized = "$title $text".trim()

        val lines = listOf(
            "package=${sbn.packageName}",
            "event=POSTED",
            "category=$category",
            "title=\"$title\"",
            "text=\"$text\"",
            "fullScreenIntent=$fullScreen",
            "normalizedText=\"$normalized\"",
            "direction=$direction",
            "state=$state",
            "identity=\"${identity.orEmpty()}\"",
            "ANNOUNCEMENT=${if (allowed) "ALLOWED" else "BLOCKED"}",
            "reason=$reason"
        )

        for (line in lines) {
            Log.i(TAG, "[VOIP] $line")
        }

        // Suppress ordinary chat messages and background noise from the call-debug timeline
        if (reason == "REJECTED_NOISE" || reason == "REJECTED_MESSAGING_STYLE") {
            return
        }

        val callSource = DebugCallSource.fromPackage(sbn.packageName)
        val extractedNumber = extractPhoneNumber(title, text)
        val dirString = when (direction) {
            VoipCallDirection.INCOMING -> "INCOMING"
            VoipCallDirection.OUTGOING -> "OUTGOING"
            VoipCallDirection.UNKNOWN -> "UNKNOWN"
        }
        val formattedReason = when (reason) {
            "OUTGOING_CALL" -> "OUTGOING CALL"
            "NOTIFICATION_REMOVED" -> "NOTIFICATION REMOVED"
            "NO_POSITIVE_INCOMING_EVIDENCE" -> "NO_POSITIVE_INCOMING_EVIDENCE"
            else -> reason
        }

        val isVideo = config.videoHints.any { "$title $text".lowercase().contains(it) }
        val callType = if (isVideo) "video call" else "voice call"
        val ttsText = if (allowed && identity != null) {
            "${config.displayName} $callType from $identity"
        } else {
            null
        }

        CallDebugTracker.onVoipCallEvent(
            source = callSource,
            direction = dirString,
            callerName = identity,
            number = extractedNumber,
            allowed = allowed,
            reason = formattedReason,
            ttsText = ttsText,
            packageName = sbn.packageName,
            notifTitle = title,
            notifText = text,
            detectorDecision = reason,
            notificationKey = sbn.key
        )
    }

    private fun extractPhoneNumber(title: String, text: String): String? {
        val phoneRegex = Regex("(?:\\+?\\d{1,4}[\\s-]*)?(?:\\(?\\d{2,5}\\)?[\\s-]*)?\\d{3,5}[\\s-]*\\d{4,6}")
        val matchTitle = phoneRegex.find(title)?.value?.trim()
        if (matchTitle != null && matchTitle.replace(Regex("[^0-9]"), "").length >= 7) {
            return matchTitle
        }
        val matchText = phoneRegex.find(text)?.value?.trim()
        if (matchText != null && matchText.replace(Regex("[^0-9]"), "").length >= 7) {
            return matchText
        }
        return null
    }

    /**
     * Extract the caller name from notification fields.
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
        if (candidate.length > 80) return null
        return candidate
    }
}
