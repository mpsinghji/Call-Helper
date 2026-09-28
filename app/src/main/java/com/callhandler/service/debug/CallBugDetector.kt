package com.callhandler.service.debug

import com.callhandler.service.core.CallSessionState
import com.callhandler.service.core.SpamStatus
import com.callhandler.service.settings.SpamAnnouncementPolicy

/**
 * Types of automatic bug / diagnostic violations detected in the call pipeline.
 */
enum class BugType(val displayName: String) {
    IDENTITY_PRIORITY_VIOLATION("IDENTITY PRIORITY VIOLATION"),
    IDENTITY_DOWNGRADE("IDENTITY DOWNGRADE"),
    DUPLICATE_GSM_SESSION("DUPLICATE GSM SESSION FOR SAME CALL"),
    LATE_TRUECALLER_UPDATE("POST-ANNOUNCEMENT TRUECALLER UPDATE"),
    POST_ANNOUNCEMENT_MUTATION("POST-ANNOUNCEMENT IDENTITY MUTATION"),
    TRUECALLER_STALE_TEXT("TRUECALLER STALE/STATUS TEXT"),
    TRUECALLER_NUMBER_MISMATCH("TRUECALLER NUMBER MISMATCH"),
    STALE_TRUECALLER_IDENTITY("STALE TRUECALLER IDENTITY USED"),
    CONTACT_IDENTITY_REPLACED("CONTACT IDENTITY REPLACED BY TRUECALLER"),
    ANNOUNCEMENT_IDENTITY_MISMATCH("ANNOUNCEMENT/IDENTITY MISMATCH"),
    HISTORY_ENTRY_MUTATION("COMPLETED HISTORY ENTRY MUTATED"),
    PREVIOUS_SESSION_LEAK("PREVIOUS SESSION IDENTITY LEAK"),
    DUPLICATE_TTS("DUPLICATE TTS"),
    AUDIO_PIPELINE_CONFLICT("AUDIO PIPELINE CONFLICT"),
    VOIP_DIRECTION_VIOLATION("VOIP DIRECTION VIOLATION"),
    SPAM_POLICY_VIOLATION("SPAM POLICY VIOLATION")
}

/**
 * Structured bug record detected by [CallBugDetector].
 */
data class CallBug(
    val type: BugType,
    val title: String,
    val details: String,
    val sessionId: Long? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun format(): String {
        return """
            ⚠ $title
            $details
        """.trimIndent()
    }
}

/**
 * Dedicated automatic diagnostics engine.
 *
 * Analyzes real production events and automatically reports actual detected problems.
 * Does NOT generate fake warnings simply because two values differ at different times.
 * Only reports a bug when the event sequence actually violates a defined rule.
 */
object CallBugDetector {

    /**
     * Evaluates caller identity priority:
     * Priority: CONTACT > TRUECALLER > LOCAL_FALLBACK > UNKNOWN
     *
     * If Contact exists, Contact should win.
     * CONTACT = Aman, TRUECALLER = Pavinder, ANNOUNCEMENT = Aman -> ✓ PRIORITY CORRECT (NOT a bug!)
     *
     * Only reports a bug when announcement violates the priority:
     * e.g. CONTACT = Aman, TRUECALLER = Pavinder, ANNOUNCEMENT = Pavinder -> ⚠ IDENTITY PRIORITY VIOLATION
     */
    fun checkIdentityPriority(
        contactName: String?,
        truecallerName: String?,
        announcementName: String?,
        announcementSource: String?
    ): CallBug? {
        val finalName = announcementName?.trim()
        if (finalName.isNullOrBlank() || finalName.equals("BLOCKED", ignoreCase = true) ||
            finalName.equals("Waiting...", ignoreCase = true)
        ) {
            return null
        }

        val hasContact = !contactName.isNullOrBlank() && !contactName.equals("Unknown", ignoreCase = true)
        val hasTruecaller = !truecallerName.isNullOrBlank() && !truecallerName.equals("Unknown", ignoreCase = true)

        if (hasContact) {
            val contact = contactName!!.trim()
            if (!finalName.equals(contact, ignoreCase = true)) {
                return CallBug(
                    type = BugType.IDENTITY_PRIORITY_VIOLATION,
                    title = "IDENTITY PRIORITY VIOLATION",
                    details = "Expected: CONTACT → $contact\nActual: ${announcementSource ?: "UNKNOWN"} → $finalName"
                )
            }
            return null
        }

        if (hasTruecaller) {
            val tc = truecallerName!!.trim()
            if (finalName.equals("Unknown caller", ignoreCase = true)) {
                return CallBug(
                    type = BugType.IDENTITY_PRIORITY_VIOLATION,
                    title = "IDENTITY PRIORITY VIOLATION",
                    details = "Expected: TRUECALLER → $tc\nActual: UNKNOWN → $finalName"
                )
            }
            if (!finalName.equals(tc, ignoreCase = true) && !finalName.equals("Unknown caller", ignoreCase = true)) {
                return CallBug(
                    type = BugType.IDENTITY_PRIORITY_VIOLATION,
                    title = "IDENTITY PRIORITY VIOLATION",
                    details = "Expected: TRUECALLER → $tc\nActual: ${announcementSource ?: "UNKNOWN"} → $finalName"
                )
            }
        }

        return null
    }

    /**
     * Checks if a new session creation is actually an illegal duplicate GSM session for the same call.
     */
    fun checkDuplicateGsmSession(
        existingSession: CallDebugSession,
        newSource: String,
        newNumber: String?,
        timestamp: Long = System.currentTimeMillis()
    ): CallBug? {
        if (existingSession.callSource != DebugCallSource.GSM) return null
        if (existingSession.state == CallSessionState.ENDED || existingSession.isSealed) return null

        val timeDiff = timestamp - existingSession.startTimeMs
        if (timeDiff > 45_000L && existingSession.state != CallSessionState.ACTIVE) return null

        val n1 = normalizePhoneNumber(existingSession.phoneNumber)
        val n2 = normalizePhoneNumber(newNumber)
        val numbersCompatible = n1.isEmpty() || n2.isEmpty() || n1 == n2 || n1.endsWith(n2) || n2.endsWith(n1)

        if (numbersCompatible) {
            return CallBug(
                type = BugType.DUPLICATE_GSM_SESSION,
                title = "DUPLICATE GSM SESSION FOR SAME CALL",
                details = "Active Session #${existingSession.id} (${existingSession.phoneNumber ?: "Unknown"}) detected ${timeDiff}ms ago.\n" +
                        "A duplicate event was received from $newSource (number=${newNumber ?: "Unknown"}).\n" +
                        "Action: Must merge into Session #${existingSession.id} instead of creating a new session.",
                sessionId = existingSession.id
            )
        }
        return null
    }

    /**
     * Evaluates whether an incoming identity selection would illegally downgrade an existing stronger identity.
     * Priority: CONTACT > TRUECALLER > LOCAL_FALLBACK > UNKNOWN
     */
    fun checkIdentityDowngrade(
        currentSource: String?,
        currentName: String?,
        newSource: String,
        newName: String?
    ): CallBug? {
        if (currentSource.isNullOrBlank() || currentSource.equals("UNKNOWN", ignoreCase = true)) {
            return null
        }

        fun priority(src: String?): Int = when (src?.uppercase()) {
            "CONTACT" -> 4
            "TRUECALLER" -> 3
            "LOCAL_FALLBACK" -> 2
            else -> 1
        }

        val currPri = priority(currentSource)
        val newPri = priority(newSource)

        if (newPri < currPri) {
            return CallBug(
                type = BugType.IDENTITY_DOWNGRADE,
                title = "IDENTITY DOWNGRADE",
                details = "Attempted downgrade from $currentSource ($currentName) to $newSource ($newName).\n" +
                        "Priority rule violated: CONTACT > TRUECALLER > LOCAL_FALLBACK > UNKNOWN."
            )
        }
        return null
    }

    /**
     * Detects when Truecaller arrives after announcement identity was already locked / TTS started.
     *
     * Only flags a bug when the Truecaller name actually DIFFERS from the locked announcement
     * (a POST-ANNOUNCEMENT IDENTITY MUTATION). A harmless post-announcement Truecaller observation
     * where the name matches (or is just diagnostic) is NOT a bug.
     */
    fun checkLateTruecaller(
        isIdentityLocked: Boolean,
        lockedAnnouncement: String?,
        tcName: String
    ): CallBug? {
        if (!isIdentityLocked) return null
        // Only flag if the Truecaller name would actually CHANGE the locked identity
        val isMutation = !tcName.equals(lockedAnnouncement, ignoreCase = true)
        if (isMutation) {
            return CallBug(
                type = BugType.POST_ANNOUNCEMENT_MUTATION,
                title = "POST-ANNOUNCEMENT IDENTITY MUTATION",
                details = "Announcement identity was locked at: '$lockedAnnouncement'.\nLate Truecaller observation: '$tcName'.\nThis would have CHANGED the announced identity.\nAnnouncement preserved."
            )
        }
        // Harmless observation (same name) — not a bug
        return null
    }

    /**
     * Detects when the announcement identity differs from the validated session identity.
     * This is a critical bug: the TTS would speak a different name than what was resolved.
     */
    fun checkAnnouncementMismatch(
        validatedName: String?,
        validatedSource: String?,
        announcementName: String?,
        announcementSource: String?
    ): CallBug? {
        if (validatedName.isNullOrBlank() || announcementName.isNullOrBlank()) return null
        if (!validatedName.equals(announcementName, ignoreCase = true)) {
            return CallBug(
                type = BugType.ANNOUNCEMENT_IDENTITY_MISMATCH,
                title = "ANNOUNCEMENT/IDENTITY MISMATCH",
                details = "Validated identity: $validatedSource / $validatedName\nAnnouncement identity: $announcementSource / $announcementName\nThese must always match."
            )
        }
        return null
    }

    /**
     * Checks if observed Truecaller phone number matches active GSM call number.
     */
    fun checkNumberMismatch(
        activeCallNumber: String?,
        observedNumber: String?,
        sessionId: Long? = null
    ): CallBug? {
        if (activeCallNumber.isNullOrBlank() || observedNumber.isNullOrBlank()) return null

        val normActive = normalizePhoneNumber(activeCallNumber)
        val normObserved = normalizePhoneNumber(observedNumber)

        if (normActive.isNotEmpty() && normObserved.isNotEmpty()) {
            val matches = normActive == normObserved ||
                    normActive.endsWith(normObserved) ||
                    normObserved.endsWith(normActive)
            if (!matches) {
                val sessionPart = if (sessionId != null) "Session #$sessionId\n\n" else ""
                val details = "${sessionPart}Active call:\n$activeCallNumber\n\nObserved:\n$observedNumber\n\nAction:\nIgnored"
                return CallBug(
                    type = BugType.TRUECALLER_NUMBER_MISMATCH,
                    title = "TRUECALLER NUMBER MISMATCH",
                    details = details,
                    sessionId = sessionId
                )
            }
        }
        return null
    }

    /**
     * Detects stale / non-name UI text from Truecaller.
     */
    fun createStaleTextBug(staleText: String): CallBug {
        return CallBug(
            type = BugType.TRUECALLER_STALE_TEXT,
            title = "TRUECALLER STALE/STATUS TEXT",
            details = "Value: $staleText\nAction: Ignored"
        )
    }

    /**
     * Detects unexpected duplicate TTS announcements for the same call session.
     */
    fun checkDuplicateTts(
        ttsCount: Int,
        isRepeatEnabled: Boolean,
        text: String
    ): CallBug? {
        if (ttsCount > 1 && !isRepeatEnabled) {
            return CallBug(
                type = BugType.DUPLICATE_TTS,
                title = "DUPLICATE TTS",
                details = "Same session\nText: \"$text\"\nUnexpected repeated announcement (repeat disabled)"
            )
        }
        return null
    }

    /**
     * Detects audio pipeline conflict (e.g. recognizer active when TTS started).
     */
    fun checkAudioConflict(isRecognizerActive: Boolean): CallBug? {
        if (isRecognizerActive) {
            return CallBug(
                type = BugType.AUDIO_PIPELINE_CONFLICT,
                title = "AUDIO PIPELINE CONFLICT",
                details = "Recognizer was active when TTS started."
            )
        }
        return null
    }

    /**
     * Detects illegal VoIP announcements.
     */
    fun checkVoipPolicy(direction: String, allowed: Boolean, reason: String?): CallBug? {
        if (direction == "OUTGOING" && allowed) {
            return CallBug(
                type = BugType.VOIP_DIRECTION_VIOLATION,
                title = "VOIP DIRECTION VIOLATION",
                details = "Outgoing VoIP call was permitted to announce."
            )
        }
        if (direction == "UNKNOWN" && allowed) {
            return CallBug(
                type = BugType.VOIP_DIRECTION_VIOLATION,
                title = "VOIP DIRECTION VIOLATION",
                details = "VoIP call with UNKNOWN direction was permitted to announce."
            )
        }
        return null
    }

    /**
     * Checks if spam policy was violated.
     */
    fun checkSpamPolicy(
        spamStatus: SpamStatus,
        policy: SpamAnnouncementPolicy,
        announced: Boolean,
        ttsText: String?
    ): CallBug? {
        if ((spamStatus == SpamStatus.SPAM || spamStatus == SpamStatus.POSSIBLE_SPAM)) {
            if (policy == SpamAnnouncementPolicy.BLOCK && announced) {
                return CallBug(
                    type = BugType.SPAM_POLICY_VIOLATION,
                    title = "SPAM POLICY VIOLATION",
                    details = "Spam call was announced despite policy being 'Don't announce'."
                )
            }
        }
        return null
    }

    fun normalizePhoneNumber(raw: String?): String {
        if (raw == null) return ""
        val digitsOnly = raw.replace("[^0-9]".toRegex(), "")
        return if (digitsOnly.length > 10) digitsOnly.takeLast(10) else digitsOnly
    }
}
