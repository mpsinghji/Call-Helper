package com.callhandler.service.debug

import com.callhandler.service.identity.VoipCallDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Supported call sources for debug tracking.
 */
enum class DebugCallSource(val displayName: String) {
    GSM("GSM"),
    WHATSAPP("WhatsApp"),
    TELEGRAM("Telegram"),
    MESSENGER("Messenger"),
    OTHER_VOIP("Other VoIP"),
    UNKNOWN("Unknown");

    companion object {
        fun fromPackage(packageName: String?): DebugCallSource {
            if (packageName == null) return UNKNOWN
            return when (packageName) {
                "com.whatsapp", "com.whatsapp.w4b" -> WHATSAPP
                "org.telegram.messenger" -> TELEGRAM
                "com.facebook.orca" -> MESSENGER
                in VoipCallDetector.KNOWN_PACKAGES -> OTHER_VOIP
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Central tracker for caller identity pipeline debugging.
 *
 * Distinguishes the three distinct identities:
 * 1. [contactName]      - Phone/Contacts lookup
 * 2. [truecallerName]   - Truecaller overlay extraction
 * 3. [announcementName] - Final identity selected for announcement/TTS
 *
 * Tracks the complete lifecycle:
 * PHONE STATE -> CONTACT LOOKUP -> TRUECALLER -> IDENTITY SELECTION -> ANNOUNCEMENT -> TTS
 * Automatically detects identity mismatches and outputs clean chronological timelines and summaries.
 */
object CallDebugTracker {

    private const val DIVIDER = "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val summaryTimeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    data class CallDebugSnapshot(
        val phoneNumber: String?,
        val contactName: String?,
        val truecallerName: String?,
        val announcementName: String?,
        val announcementSource: String?,
        val ttsText: String?,
        val isMismatch: Boolean,
        val isRinging: Boolean,
        val callSource: DebugCallSource = DebugCallSource.GSM,
        val callDirection: String? = null,
        val reason: String? = null,
        val notifPackage: String? = null,
        val notifTitle: String? = null,
        val notifText: String? = null,
        val detectorDecision: String? = null,
        val lastUpdated: Long = System.currentTimeMillis()
    )

    private val _currentSnapshot = MutableStateFlow<CallDebugSnapshot?>(null)
    val currentSnapshot: StateFlow<CallDebugSnapshot?> = _currentSnapshot.asStateFlow()

    @Volatile
    var callSource: DebugCallSource = DebugCallSource.GSM
        private set

    @Volatile
    var callDirection: String? = null
        private set

    @Volatile
    var reason: String? = null
        private set

    @Volatile
    var notifPackage: String? = null
        private set

    @Volatile
    var notifTitle: String? = null
        private set

    @Volatile
    var notifText: String? = null
        private set

    @Volatile
    var detectorDecision: String? = null
        private set

    @Volatile
    var phoneNumber: String? = null
        private set

    @Volatile
    var contactName: String? = null
        private set

    @Volatile
    var truecallerName: String? = null
        private set

    @Volatile
    var announcementName: String? = null
        private set

    @Volatile
    var announcementSource: String? = null
        private set

    @Volatile
    var ttsText: String? = null
        private set

    @Volatile
    private var callStartTimeMs: Long = 0L

    @Volatile
    var isMismatch: Boolean = false
        private set

    @Volatile
    private var isCallActive: Boolean = false

    private fun now(): String = timeFmt.format(Date())

    private fun updateSnapshot() {
        _currentSnapshot.value = CallDebugSnapshot(
            phoneNumber = phoneNumber,
            contactName = contactName,
            truecallerName = truecallerName,
            announcementName = announcementName,
            announcementSource = announcementSource,
            ttsText = ttsText,
            isMismatch = isMismatch,
            isRinging = isCallActive,
            callSource = callSource,
            callDirection = callDirection,
            reason = reason,
            notifPackage = notifPackage,
            notifTitle = notifTitle,
            notifText = notifText,
            detectorDecision = detectorDecision
        )
    }

    /**
     * Called when a call is detected (from PhoneStateReceiver or CallScreening).
     */
    @Synchronized
    fun onCallDetected(number: String?, source: String) {
        if (!isCallActive || (number != null && phoneNumber == null)) {
            isCallActive = true
            callStartTimeMs = System.currentTimeMillis()
            callSource = DebugCallSource.GSM
            callDirection = "INCOMING"
            reason = null
            if (number != null) {
                phoneNumber = number
            }
            isMismatch = false
            updateSnapshot()

            val sb = java.lang.StringBuilder()
            sb.appendLine(DIVIDER)
            sb.appendLine("${now()}  CALL DETECTED")
            sb.appendLine(DIVIDER)
            sb.appendLine("Call Source    : ${callSource.displayName}")
            sb.appendLine("Direction      : ${callDirection ?: "Unknown"}")
            sb.appendLine("Number         : ${phoneNumber ?: "Unknown"}")
            sb.appendLine("Phone/Contacts : ${contactName ?: "Waiting..."}")
            sb.appendLine("Truecaller     : ${truecallerName ?: "Waiting..."}")
            sb.appendLine("Announcement   : Waiting...")
            sb.appendLine("Source         : -")
            sb.appendLine("Boundary       : PHONE (source=$source)")
            DebugLogStore.logRaw(sb.toString().trimEnd())
        }
    }

    /**
     * Called when Contacts lookup for the number completes.
     */
    @Synchronized
    fun onContactLookupResult(number: String?, name: String?) {
        if (number != null && phoneNumber == null) {
            phoneNumber = number
        }
        contactName = name
        updateSnapshot()

        val sb = java.lang.StringBuilder()
        sb.appendLine("${now()} CONTACT")
        sb.appendLine("Name   : ${name ?: "Unknown"}")
        sb.appendLine("Number : ${phoneNumber ?: number ?: "Unknown"}")
        DebugLogStore.logRaw(sb.toString().trimEnd())
    }

    /**
     * Called when Truecaller overlay is parsed or received.
     */
    @Synchronized
    fun onTruecallerResult(number: String?, name: String?) {
        val trimmed = name?.trim()
        if (trimmed.isNullOrEmpty()) return

        if (number != null && phoneNumber == null) {
            phoneNumber = number
        }
        truecallerName = trimmed
        updateSnapshot()

        // 1. Boundary event
        val boundarySb = java.lang.StringBuilder()
        boundarySb.appendLine("${now()} TRUECALLER")
        boundarySb.appendLine("Name   : $trimmed")
        boundarySb.appendLine("Number : ${phoneNumber ?: number ?: "Unknown"}")
        DebugLogStore.logRaw(boundarySb.toString().trimEnd())

        // 2. Timeline card
        val timelineSb = java.lang.StringBuilder()
        timelineSb.appendLine(DIVIDER)
        timelineSb.appendLine("${now()}  TRUECALLER RESULT")
        timelineSb.appendLine(DIVIDER)
        timelineSb.appendLine("Number         : ${phoneNumber ?: "Unknown"}")
        timelineSb.appendLine("Phone/Contacts : ${contactName ?: "Unknown"}")
        timelineSb.appendLine("Truecaller     : $trimmed")
        DebugLogStore.logRaw(timelineSb.toString().trimEnd())

        // Re-check mismatch if identity was already announced
        checkAndReportMismatch()
    }

    /**
     * Called when CallerIdentityManager selects the identity to announce.
     */
    @Synchronized
    fun onIdentitySelected(number: String?, selectedName: String?, source: String) {
        if (number != null && phoneNumber == null) {
            phoneNumber = number
        }
        announcementName = selectedName ?: "Unknown caller"
        announcementSource = source
        checkAndReportMismatch()
        updateSnapshot()

        val sb = java.lang.StringBuilder()
        sb.appendLine(DIVIDER)
        sb.appendLine("${now()}  IDENTITY SELECTED")
        sb.appendLine(DIVIDER)
        sb.appendLine("Phone/Contacts : ${contactName ?: "Unknown"}")
        sb.appendLine("Truecaller     : ${truecallerName ?: "Unknown"}")
        sb.appendLine("Announcement   : $announcementName")
        sb.appendLine("Source         : $announcementSource")
        DebugLogStore.logRaw(sb.toString().trimEnd())
    }

    /**
     * Called immediately before sending text to AnnouncementManager.
     */
    @Synchronized
    fun onAnnouncementPrepared(name: String, text: String, source: String) {
        announcementName = name
        ttsText = text
        announcementSource = source
        checkAndReportMismatch()
        updateSnapshot()

        val sb = java.lang.StringBuilder()
        sb.appendLine(DIVIDER)
        sb.appendLine("${now()}  ANNOUNCEMENT")
        sb.appendLine(DIVIDER)
        sb.appendLine("Phone/Contacts : ${contactName ?: "Unknown"}")
        sb.appendLine("Truecaller     : ${truecallerName ?: "Unknown"}")
        sb.appendLine("Announcement   : $announcementName")
        sb.appendLine("Source         : $announcementSource")
        sb.appendLine("TTS Text       : $ttsText")
        DebugLogStore.logRaw(sb.toString().trimEnd())
    }

    /**
     * Called when TTS audio actually starts speaking.
     */
    @Synchronized
    fun onTtsStarted(text: String) {
        ttsText = text
        updateSnapshot()

        val sb = java.lang.StringBuilder()
        sb.appendLine(DIVIDER)
        sb.appendLine("${now()}  TTS STARTED")
        sb.appendLine(DIVIDER)
        sb.appendLine("Text           : $text")
        DebugLogStore.logRaw(sb.toString().trimEnd())
    }

    /**
     * Determines whether the selected/announced identity conflicts with expected sources.
     */
    @Synchronized
    private fun checkAndReportMismatch() {
        val finalName = announcementName ?: return
        if (finalName.isBlank() || finalName.equals("Unknown caller", ignoreCase = true)) return

        // Expected identity follows priority: CONTACT -> TRUECALLER
        val expectedContact = contactName?.takeIf { it.isNotBlank() }
        val expectedTruecaller = truecallerName?.takeIf { it.isNotBlank() }

        var mismatchDetected = false

        if (expectedContact != null && !finalName.equals(expectedContact, ignoreCase = true)) {
            mismatchDetected = true
        } else if (expectedContact == null && expectedTruecaller != null &&
            !finalName.equals(expectedTruecaller, ignoreCase = true)
        ) {
            mismatchDetected = true
        } else if (announcementSource == "TRUECALLER" && expectedTruecaller != null &&
            !finalName.equals(expectedTruecaller, ignoreCase = true)
        ) {
            mismatchDetected = true
        } else if (announcementSource == "CONTACT" && expectedContact != null &&
            !finalName.equals(expectedContact, ignoreCase = true)
        ) {
            mismatchDetected = true
        }

        if (mismatchDetected && !isMismatch) {
            isMismatch = true
            val sb = java.lang.StringBuilder()
            sb.appendLine(DIVIDER)
            sb.appendLine("${now()}  ⚠ IDENTITY MISMATCH")
            sb.appendLine(DIVIDER)
            sb.appendLine("Expected sources:")
            sb.appendLine("  CONTACT       = ${expectedContact ?: "Unknown"}")
            sb.appendLine("  TRUECALLER    = ${expectedTruecaller ?: "Unknown"}")
            sb.appendLine("")
            sb.appendLine("Final:")
            sb.appendLine("  ANNOUNCEMENT  = $finalName")
            sb.appendLine("")
            sb.appendLine("TTS:")
            sb.appendLine("  ${ttsText ?: "Waiting..."}")
            sb.appendLine(DIVIDER)
            DebugLogStore.logRaw(sb.toString().trimEnd())
        }
    }

    /**
     * Called when a VoIP call event is detected or updated (WhatsApp, Telegram, Messenger, etc.).
     */
    @Synchronized
    fun onVoipCallEvent(
        source: DebugCallSource,
        direction: String,
        callerName: String?,
        number: String?,
        allowed: Boolean,
        reason: String?,
        ttsText: String? = null,
        packageName: String? = null,
        notifTitle: String? = null,
        notifText: String? = null,
        detectorDecision: String? = null
    ) {
        this.callSource = source
        this.callDirection = direction
        this.reason = reason
        this.notifPackage = packageName
        this.notifTitle = notifTitle
        this.notifText = notifText
        this.detectorDecision = detectorDecision

        val isPhoneNumber = callerName?.matches(Regex("^[+]?[0-9\\s()-]{7,25}$")) == true
        val resolvedNumber = number ?: (if (isPhoneNumber) callerName else null)
        val resolvedContact = if (isPhoneNumber) "Unknown" else (callerName ?: "Unknown")

        if (resolvedNumber != null) {
            this.phoneNumber = resolvedNumber
        }
        if (callerName != null) {
            this.contactName = resolvedContact
        }

        val finalAnnouncement = if (allowed) {
            truecallerName ?: resolvedContact
        } else {
            "BLOCKED"
        }
        this.announcementName = finalAnnouncement

        this.announcementSource = if (allowed) {
            if (truecallerName != null) "TRUECALLER" else source.name
        } else {
            "-"
        }

        val resolvedTts = if (allowed) {
            ttsText ?: "Incoming call from $finalAnnouncement"
        } else {
            "-"
        }
        this.ttsText = resolvedTts

        this.isCallActive = allowed
        if (allowed) {
            callStartTimeMs = System.currentTimeMillis()
        }

        checkAndReportMismatch()
        updateSnapshot()

        val headerTitle = when (source) {
            DebugCallSource.WHATSAPP -> "WHATSAPP CALL"
            DebugCallSource.TELEGRAM -> "TELEGRAM CALL"
            DebugCallSource.MESSENGER -> "MESSENGER CALL"
            DebugCallSource.OTHER_VOIP -> "VOIP CALL"
            else -> "VOIP CALL"
        }

        val sb = java.lang.StringBuilder()
        sb.appendLine(DIVIDER)
        sb.appendLine("${now()}  $headerTitle")
        sb.appendLine(DIVIDER)
        sb.appendLine("Call Source   : ${source.displayName}")
        sb.appendLine("Direction     : $direction")
        if (resolvedNumber != null || direction == "INCOMING") {
            sb.appendLine("Number        : ${phoneNumber ?: resolvedNumber ?: "Unknown"}")
        }
        sb.appendLine("Contact       : ${contactName ?: resolvedContact}")
        sb.appendLine("Truecaller    : ${truecallerName ?: "Unknown"}")
        sb.appendLine("Announcement  : $announcementName")
        sb.appendLine("Source        : $announcementSource")
        sb.appendLine("TTS Text      : $ttsText")
        if (!allowed && !reason.isNullOrBlank()) {
            sb.appendLine("Reason        : $reason")
        }
        DebugLogStore.logRaw(sb.toString().trimEnd())
    }

    /**
     * Called when an announcement is blocked at the service / audio router level
     * (e.g. Bluetooth audio not connected, volume 0, or disabled in settings).
     */
    @Synchronized
    fun onAnnouncementBlocked(reason: String) {
        this.reason = reason
        this.announcementName = "BLOCKED"
        this.ttsText = "-"
        this.isCallActive = false
        updateSnapshot()

        val sb = java.lang.StringBuilder()
        sb.appendLine(DIVIDER)
        sb.appendLine("${now()}  ANNOUNCEMENT BLOCKED")
        sb.appendLine(DIVIDER)
        sb.appendLine("Call Source   : ${callSource.displayName}")
        sb.appendLine("Direction     : ${callDirection ?: "Unknown"}")
        sb.appendLine("Announcement  : BLOCKED")
        sb.appendLine("Reason        : $reason")
        DebugLogStore.logRaw(sb.toString().trimEnd())
    }

    /**
     * Called when a call ends to produce one compact summary.
     */
    @Synchronized
    fun onCallEnded() {
        if (!isCallActive) return
        isCallActive = false

        val callTime = if (callStartTimeMs > 0) summaryTimeFmt.format(Date(callStartTimeMs)) else summaryTimeFmt.format(Date())
        val resultString = if (isMismatch) "⚠ IDENTITY MISMATCH" else "✓ IDENTITY MATCH"

        val sb = java.lang.StringBuilder()
        sb.appendLine(DIVIDER)
        sb.appendLine("CALL SUMMARY")
        sb.appendLine(DIVIDER)
        sb.appendLine("Time           : $callTime")
        sb.appendLine("Call Source    : ${callSource.displayName}")
        sb.appendLine("Direction      : ${callDirection ?: "Unknown"}")
        sb.appendLine("Number         : ${phoneNumber ?: "Unknown"}")
        sb.appendLine("")
        sb.appendLine("Phone/Contacts : ${contactName ?: "Unknown"}")
        sb.appendLine("Truecaller     : ${truecallerName ?: "Unknown"}")
        sb.appendLine("Announcement   : ${announcementName ?: "Unknown"}")
        sb.appendLine("")
        sb.appendLine("Source         : ${announcementSource ?: "UNKNOWN"}")
        sb.appendLine("TTS Text       : ${ttsText ?: "None"}")
        if (reason != null) {
            sb.appendLine("Reason         : $reason")
        }
        sb.appendLine("")
        sb.appendLine("RESULT         : $resultString")
        sb.appendLine(DIVIDER)

        DebugLogStore.logRaw(sb.toString().trimEnd())
        updateSnapshot()
    }

    /**
     * Reset tracker for a new session.
     */
    @Synchronized
    fun reset() {
        phoneNumber = null
        contactName = null
        truecallerName = null
        announcementName = null
        announcementSource = null
        ttsText = null
        callStartTimeMs = 0L
        isMismatch = false
        isCallActive = false
        callSource = DebugCallSource.GSM
        callDirection = null
        reason = null
        notifPackage = null
        notifTitle = null
        notifText = null
        detectorDecision = null
        _currentSnapshot.value = null
    }
}
