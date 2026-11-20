package com.callhandler.service.identity

import android.app.Notification
import android.os.Build
import android.service.notification.StatusBarNotification
import android.util.Log
import com.callhandler.service.core.CallSessionManager
import com.callhandler.service.core.CallType
import com.callhandler.service.core.SpamStatus
import com.callhandler.service.debug.CallBugDetector
import com.callhandler.service.debug.CallDebugTracker
import com.callhandler.service.debug.DebugLogStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * High-level classification of what the Truecaller notification represents.
 */
enum class TruecallerNotifType {
    INCOMING_CALL,
    MISSED_CALL,
    CALL_ENDED,
    PROMOTIONAL_NOISE,
    UNKNOWN
}

/**
 * Evaluation of how well this Truecaller notification correlates with
 * the active GSM call session.
 */
enum class TruecallerCorrelationStatus {
    /** Active GSM call exists, phone numbers match, and arrival timestamp is fresh. */
    CORRELATED_MATCH,

    /** Active GSM call exists and timestamp is fresh, but phone number was missing from one or both sides. */
    CORRELATED_UNVERIFIED_NUMBER,

    /** Active GSM call exists, but Truecaller number DOES NOT MATCH the GSM number. Cross-contamination risk! */
    NUMBER_MISMATCH,

    /** Notification post timestamp is older than the current GSM call start (> 2000 ms before ring). */
    STALE_NOTIFICATION,

    /** Notification arrived with no active GSM ringing call (idle, promo, or after-call). */
    NO_ACTIVE_GSM_CALL
}

/**
 * Diagnostic record capturing metadata and analysis of one Truecaller notification event.
 */
data class TruecallerDiagnosticReport(
    val timestamp: Long = System.currentTimeMillis(),
    val notifType: TruecallerNotifType,
    val correlationStatus: TruecallerCorrelationStatus,
    val latencyFromGsmRingMs: Long?,
    val extractedCallerName: String?,
    val extractedPhoneNumberRedacted: String?,
    val spamStatus: SpamStatus,
    val availableFields: List<String>,
    val notificationCategory: String?,
    val notificationChannelId: String?,
    val isOngoing: Boolean,
    val summary: String
) {
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    fun formattedTime(): String = timeFmt.format(Date(timestamp))
}

/**
 * Thread-safe in-memory store for Truecaller notification diagnostic reports.
 * Accessible from UI (DebugConsoleActivity) and tests.
 */
object TruecallerDiagnosticStore {
    private const val MAX_ENTRIES = 50

    private val _reports = MutableStateFlow<List<TruecallerDiagnosticReport>>(emptyList())
    val reports: StateFlow<List<TruecallerDiagnosticReport>> = _reports.asStateFlow()

    @Synchronized
    fun record(report: TruecallerDiagnosticReport) {
        _reports.update { current ->
            val updated = current + report
            if (updated.size > MAX_ENTRIES) updated.drop(updated.size - MAX_ENTRIES) else updated
        }
    }

    fun clear() {
        _reports.value = emptyList()
    }

    /**
     * Generates a structured feasibility and analysis report based on all captured events.
     */
    fun buildFeasibilityReport(): String = buildString {
        val list = _reports.value
        appendLine("=================================================================")
        appendLine("TRUECALLER NOTIFICATIONLISTENER FEASIBILITY & DIAGNOSTIC REPORT")
        appendLine("Captured events: ${list.size}")
        appendLine("=================================================================")
        if (list.isEmpty()) {
            appendLine("No Truecaller notifications captured yet.")
            appendLine("Ensure Notification Access is enabled and Truecaller is active.")
            return@buildString
        }

        val incomingList = list.filter { it.notifType == TruecallerNotifType.INCOMING_CALL }
        val matchedList = list.filter { it.correlationStatus == TruecallerCorrelationStatus.CORRELATED_MATCH }
        val mismatchList = list.filter { it.correlationStatus == TruecallerCorrelationStatus.NUMBER_MISMATCH }
        val staleList = list.filter { it.correlationStatus == TruecallerCorrelationStatus.STALE_NOTIFICATION }

        appendLine("1. SUMMARY METRICS:")
        appendLine("   - Total notifications: ${list.size}")
        appendLine("   - Incoming call notifications: ${incomingList.size}")
        appendLine("   - Successfully correlated with GSM: ${matchedList.size}")
        appendLine("   - Number mismatches (prevented): ${mismatchList.size}")
        appendLine("   - Stale notifications (rejected): ${staleList.size}")
        appendLine("")

        appendLine("2. FIELD AVAILABILITY:")
        val allFields = list.flatMap { it.availableFields }.distinct()
        for (f in allFields) {
            val count = list.count { it.availableFields.contains(f) }
            appendLine("   - $f: present in $count / ${list.size} notifications")
        }
        appendLine("")

        appendLine("3. RECENT CAPTURES (REDACTED):")
        for ((idx, r) in list.takeLast(10).withIndex()) {
            appendLine("   [$idx] [${r.formattedTime()}] Type: ${r.notifType} | Correlation: ${r.correlationStatus}")
            appendLine("       Latency from GSM Ring: ${r.latencyFromGsmRingMs?.let { "${it}ms" } ?: "N/A"}")
            appendLine("       Name: ${r.extractedCallerName ?: "<none>"} | Number: ${r.extractedPhoneNumberRedacted ?: "<none>"} | Spam: ${r.spamStatus}")
            appendLine("       Category: ${r.notificationCategory} | Ongoing: ${r.isOngoing}")
            appendLine("       Fields: ${r.availableFields.joinToString(", ")}")
        }
        appendLine("=================================================================")
    }
}

/**
 * Diagnostic inspector for Truecaller StatusBarNotifications.
 *
 * Captures, parses, correlates, and safely logs Truecaller notification events
 * WITHOUT announcing or mutating production call identity.
 */
object TruecallerNotificationDiagnosticInspector {

    private const val TAG = "TruecallerNotifDiag"

    private val INCOMING_CALL_HINTS = listOf(
        "incoming call", "identified call", "calling", "is calling",
        "incoming from", "incoming truecaller"
    )

    private val MISSED_CALL_HINTS = listOf(
        "missed call", "missed voice call", "missed video call"
    )

    private val CALL_ENDED_HINTS = listOf(
        "call ended", "declined", "busy", "unavailable", "not answered", "cancelled"
    )

    private val NOISE_HINTS = listOf(
        "viewed your profile", "profile view", "premium", "upgrade",
        "offer", "backup", "back up", "stats", "statistics",
        "who searched", "searched for you", "spam report", "digest",
        "what's new", "verify", "sale", "discount"
    )

    private val SPAM_KEYWORDS = listOf(
        "spam", "fraud", "scam", "telemarketer", "telemarketing", "sales", "suspicious"
    )

    /**
     * Inspect a StatusBarNotification received from Truecaller.
     * Diagnostic-only: NEVER triggers audio announcements.
     */
    fun inspect(sbn: StatusBarNotification): TruecallerDiagnosticReport {
        val notif = sbn.notification
        val extras = notif.extras

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
        val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()?.trim()
        val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()?.trim()

        val availableFields = mutableListOf<String>()
        if (!title.isNullOrBlank()) availableFields.add("EXTRA_TITLE")
        if (!text.isNullOrBlank()) availableFields.add("EXTRA_TEXT")
        if (!bigText.isNullOrBlank()) availableFields.add("EXTRA_BIG_TEXT")
        if (!subText.isNullOrBlank()) availableFields.add("EXTRA_SUB_TEXT")
        if (!infoText.isNullOrBlank()) availableFields.add("EXTRA_INFO_TEXT")
        if (!summaryText.isNullOrBlank()) availableFields.add("EXTRA_SUMMARY_TEXT")
        if (notif.category != null) availableFields.add("CATEGORY_${notif.category}")

        val combined = listOfNotNull(title, text, bigText, subText).joinToString(" ")
        val combinedLower = combined.lowercase()

        // 1. Classification
        val isCallCategory = notif.category == Notification.CATEGORY_CALL
        val notifType = when {
            NOISE_HINTS.any { combinedLower.contains(it) } -> TruecallerNotifType.PROMOTIONAL_NOISE
            MISSED_CALL_HINTS.any { combinedLower.contains(it) } -> TruecallerNotifType.MISSED_CALL
            CALL_ENDED_HINTS.any { combinedLower.contains(it) } -> TruecallerNotifType.CALL_ENDED
            isCallCategory || INCOMING_CALL_HINTS.any { combinedLower.contains(it) } -> TruecallerNotifType.INCOMING_CALL
            else -> TruecallerNotifType.UNKNOWN
        }

        // 2. Extracted caller name
        val candidateName = extractCandidateName(title, text, bigText)

        // 3. Extracted phone number
        val rawNumber = extractPhoneNumber(combined)
        val redactedNumber = redactPhoneNumber(rawNumber)

        // 4. Spam detection
        val isSpam = SPAM_KEYWORDS.any { combinedLower.contains(it) }
        val spamStatus = when {
            isSpam -> SpamStatus.SPAM
            combinedLower.contains("possible spam") -> SpamStatus.POSSIBLE_SPAM
            !candidateName.isNullOrBlank() -> SpamStatus.NORMAL
            else -> SpamStatus.UNKNOWN
        }

        // 5. Correlation with active GSM session
        val activeGsmSession = CallSessionManager.activeSession.value?.takeIf {
            it.callType == CallType.GSM && it.isActive
        }

        val gsmNumber = activeGsmSession?.initialNumber
        val gsmStartTime = activeGsmSession?.createdAtMs

        val latencyMs = if (gsmStartTime != null) sbn.postTime - gsmStartTime else null

        val correlationStatus = when {
            activeGsmSession == null -> TruecallerCorrelationStatus.NO_ACTIVE_GSM_CALL
            latencyMs != null && latencyMs < -2000L -> TruecallerCorrelationStatus.STALE_NOTIFICATION
            rawNumber != null && !gsmNumber.isNullOrBlank() -> {
                if (areNumbersStrictlyMatching(rawNumber, gsmNumber)) {
                    TruecallerCorrelationStatus.CORRELATED_MATCH
                } else {
                    TruecallerCorrelationStatus.NUMBER_MISMATCH
                }
            }
            else -> TruecallerCorrelationStatus.CORRELATED_UNVERIFIED_NUMBER
        }

        val isOngoing = (notif.flags and Notification.FLAG_ONGOING_EVENT) != 0

        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) notif.channelId else null

        val report = TruecallerDiagnosticReport(
            timestamp = System.currentTimeMillis(),
            notifType = notifType,
            correlationStatus = correlationStatus,
            latencyFromGsmRingMs = latencyMs,
            extractedCallerName = candidateName,
            extractedPhoneNumberRedacted = redactedNumber,
            spamStatus = spamStatus,
            availableFields = availableFields,
            notificationCategory = notif.category,
            notificationChannelId = channelId,
            isOngoing = isOngoing,
            summary = "Type: $notifType, Correlation: $correlationStatus, Name: $candidateName, Num: $redactedNumber"
        )

        TruecallerDiagnosticStore.record(report)

        // Log structured details to debug store (with redacted numbers)
        val logMsg = "TruecallerNotif: type=$notifType, corr=$correlationStatus, lat=${latencyMs ?: "N/A"}ms, " +
                "name='${candidateName ?: "<none>"}', num='$redactedNumber', spam=$spamStatus, fields=$availableFields"
        Log.i(TAG, logMsg)
        DebugLogStore.log("TRUECALLER_DIAG", logMsg)

        if (activeGsmSession != null) {
            CallDebugTracker.activeSession.value?.addEvent(
                tag = "TRUECALLER_NOTIF_DIAG",
                description = "type=$notifType, corr=$correlationStatus, name=${candidateName ?: "none"}, latency=${latencyMs}ms"
            )
        }

        return report
    }

    private fun extractCandidateName(title: String?, text: String?, bigText: String?): String? {
        val candidates = listOfNotNull(title, text, bigText)
        for (c in candidates) {
            val trimmed = c.trim()
            if (trimmed.isBlank() || trimmed.length > 60) continue
            val lower = trimmed.lowercase()
            if (INCOMING_CALL_HINTS.any { lower.contains(it) } && !isPureName(trimmed)) {
                // If it contains "Incoming call", strip boiler or check next
                val cleaned = stripBoilerplate(trimmed)
                if (cleaned.isNotBlank() && !isPurePhoneNumber(cleaned)) return cleaned
                continue
            }
            if (NOISE_HINTS.any { lower.contains(it) }) continue
            if (MISSED_CALL_HINTS.any { lower.contains(it) }) continue
            if (isPurePhoneNumber(trimmed)) continue
            return trimmed
        }
        return null
    }

    private fun stripBoilerplate(text: String): String {
        var result = text
        for (hint in INCOMING_CALL_HINTS) {
            result = result.replace(Regex("(?i)$hint"), "")
        }
        return result.replace(Regex("[:\\-–]"), "").trim()
    }

    private fun isPureName(text: String): Boolean {
        return !isPurePhoneNumber(text) && text.any { it.isLetter() }
    }

    private fun isPurePhoneNumber(text: String): Boolean {
        val digits = text.count { it.isDigit() }
        val letters = text.count { it.isLetter() }
        return digits >= 7 && letters == 0
    }

    fun extractPhoneNumber(text: String): String? {
        if (text.isBlank()) return null
        val phoneRegex = Regex("""[+]?[\d\s\-()]{7,25}""")
        val match = phoneRegex.find(text) ?: return null
        val candidate = match.value.trim()
        val digitsOnly = candidate.replace(Regex("[^0-9]"), "")
        return if (digitsOnly.length >= 7) candidate else null
    }

    /**
     * Strict normalized phone number matching adhering to requirement #8:
     * - Requires at least 7 digits on both sides (rejects ambiguous short codes).
     * - For standard subscriber numbers (>= 10 digits), strictly verifies that the
     *   last 10 national digits match.
     * - For 7-9 digit numbers, strictly requires identical digits.
     * - Does NOT guess or use partial substring matching.
     */
    fun areNumbersStrictlyMatching(num1: String?, num2: String?): Boolean {
        if (num1.isNullOrBlank() || num2.isNullOrBlank()) return false
        val d1 = num1.filter { it.isDigit() }
        val d2 = num2.filter { it.isDigit() }
        if (d1.length < 7 || d2.length < 7) return false
        if (d1.length >= 10 && d2.length >= 10) {
            return d1.takeLast(10) == d2.takeLast(10)
        }
        return d1 == d2
    }

    fun redactPhoneNumber(number: String?): String? {
        if (number.isNullOrBlank()) return null
        val digits = number.filter { it.isDigit() }
        if (digits.length <= 4) return "****"
        val prefix = if (number.startsWith("+")) number.take(3) else number.take(2)
        val suffix = number.takeLast(3)
        return "$prefix*** ***$suffix"
    }
}
