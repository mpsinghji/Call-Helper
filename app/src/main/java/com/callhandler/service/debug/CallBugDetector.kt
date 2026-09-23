package com.callhandler.service.debug

import com.callhandler.service.core.SpamStatus
import com.callhandler.service.settings.SpamAnnouncementPolicy

/**
 * Types of automatic bug / diagnostic violations detected in the call pipeline.
 */
enum class BugType(val displayName: String) {
    IDENTITY_PRIORITY_VIOLATION("IDENTITY PRIORITY VIOLATION"),
    LATE_TRUECALLER_UPDATE("POST-ANNOUNCEMENT TRUECALLER UPDATE"),
    TRUECALLER_STALE_TEXT("TRUECALLER STALE/STATUS TEXT"),
    TRUECALLER_NUMBER_MISMATCH("TRUECALLER NUMBER MISMATCH"),
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
     * Detects when Truecaller arrives after announcement identity was already locked / TTS started.
     */
    fun checkLateTruecaller(
        isIdentityLocked: Boolean,
        lockedAnnouncement: String?,
        tcName: String
    ): CallBug? {
        if (!isIdentityLocked) return null
        return CallBug(
            type = BugType.LATE_TRUECALLER_UPDATE,
            title = "POST-ANNOUNCEMENT TRUECALLER UPDATE",
            details = "Announcement identity was locked at: '$lockedAnnouncement'.\nLate Truecaller observation: '$tcName'.\nAnnouncement preserved."
        )
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
