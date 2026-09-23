package com.callhandler.service.debug

import com.callhandler.service.core.CallSessionState
import com.callhandler.service.core.SpamStatus
import com.callhandler.service.identity.VoipCallDetector
import com.callhandler.service.settings.SpamAnnouncementPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

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
 * Single granular event within a call debug session.
 */
data class SessionEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String,
    val description: String
) {
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    fun formatted(): String = "${timeFmt.format(Date(timestamp))}  $tag${if (description.isNotBlank()) ": $description" else ""}"
}

/**
 * Persistent session representing ONE real call.
 */
data class CallDebugSession(
    val id: Long,
    val startTimeMs: Long = System.currentTimeMillis(),
    var endTimeMs: Long = 0L,
    var callSource: DebugCallSource = DebugCallSource.GSM,
    var direction: String = "INCOMING",
    var phoneNumber: String? = null,
    var contactName: String? = null,
    var truecallerName: String? = null,
    var announcedName: String? = null,
    var announcementSource: String? = null,
    var ttsText: String? = null,
    var state: CallSessionState = CallSessionState.DETECTED,
    var spamStatus: SpamStatus = SpamStatus.UNKNOWN,
    var blockReason: String? = null,
    var isIdentityLocked: Boolean = false,
    var identityUpdateCount: Int = 0,
    var ttsCount: Int = 0,
    var notificationKey: String? = null,
    var packageName: String? = null,
    var bluetoothConnected: Boolean = false,
    var bluetoothDevice: String? = null,
    var ttsRoute: String? = null,
    var speakerRoute: String? = null,
    val events: MutableList<SessionEvent> = mutableListOf(),
    val detectedBugs: MutableList<CallBug> = mutableListOf()
) {
    fun addEvent(tag: String, description: String = "") {
        events.add(SessionEvent(tag = tag, description = description))
    }

    fun addBug(bug: CallBug) {
        if (detectedBugs.none { it.type == bug.type && it.details == bug.details }) {
            detectedBugs.add(bug)
        }
    }
}

/**
 * Central tracker for caller identity pipeline and VoIP debugging.
 *
 * Implements persistent session-based call tracking, automatic bug detection,
 * clean history cards, developer technical event logs, and diagnostic exports.
 */
object CallDebugTracker {

    private const val DIVIDER = "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val summaryTimeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val sessionIdGenerator = AtomicLong(100L)

    data class CallDebugSnapshot(
        val sessionId: Long,
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
        val spamStatus: SpamStatus = SpamStatus.UNKNOWN,
        val state: CallSessionState = CallSessionState.DETECTED,
        val notifPackage: String? = null,
        val notifTitle: String? = null,
        val notifText: String? = null,
        val detectorDecision: String? = null,
        val bugCount: Int = 0,
        val lastUpdated: Long = System.currentTimeMillis()
    )

    private val _currentSnapshot = MutableStateFlow<CallDebugSnapshot?>(null)
    val currentSnapshot: StateFlow<CallDebugSnapshot?> = _currentSnapshot.asStateFlow()

    private val _activeSession = MutableStateFlow<CallDebugSession?>(null)
    val activeSession: StateFlow<CallDebugSession?> = _activeSession.asStateFlow()

    private val _sessionHistory = MutableStateFlow<List<CallDebugSession>>(emptyList())
    val sessionHistory: StateFlow<List<CallDebugSession>> = _sessionHistory.asStateFlow()

    @Volatile
    var isRepeatEnabled: Boolean = false

    // Backward compatibility delegates
    val callSource: DebugCallSource
        get() = _activeSession.value?.callSource ?: DebugCallSource.GSM

    val callDirection: String?
        get() = _activeSession.value?.direction

    val reason: String?
        get() = _activeSession.value?.blockReason

    val phoneNumber: String?
        get() = _activeSession.value?.phoneNumber

    val contactName: String?
        get() = _activeSession.value?.contactName

    val truecallerName: String?
        get() = _activeSession.value?.truecallerName

    val announcementName: String?
        get() = _activeSession.value?.announcedName

    val announcementSource: String?
        get() = _activeSession.value?.announcementSource

    val ttsText: String?
        get() = _activeSession.value?.ttsText

    val isMismatch: Boolean
        get() = _activeSession.value?.detectedBugs?.any { it.type == BugType.IDENTITY_PRIORITY_VIOLATION } == true

    val isCallActive: Boolean
        get() {
            val s = _activeSession.value?.state
            return s != null && s != CallSessionState.ENDED && s != CallSessionState.BLOCKED
        }

    private fun now(): String = timeFmt.format(Date())

    @Synchronized
    private fun getOrCreateGsmSession(number: String?): CallDebugSession {
        val current = _activeSession.value
        if (current != null && current.callSource == DebugCallSource.GSM && current.state != CallSessionState.ENDED) {
            if (number != null && current.phoneNumber == null) {
                current.phoneNumber = number
            }
            return current
        }

        val session = CallDebugSession(
            id = sessionIdGenerator.incrementAndGet(),
            callSource = DebugCallSource.GSM,
            direction = "INCOMING",
            phoneNumber = number,
            state = CallSessionState.DETECTED
        )
        _activeSession.value = session
        addSessionToHistory(session)
        return session
    }

    @Synchronized
    private fun getOrCreateVoipSession(
        source: DebugCallSource,
        direction: String,
        callerName: String?,
        number: String?,
        packageName: String?,
        notificationKey: String?
    ): CallDebugSession {
        val current = _activeSession.value
        if (current != null && current.state != CallSessionState.ENDED) {
            val sameKey = notificationKey != null && current.notificationKey == notificationKey
            val samePkgAndCaller = packageName != null && current.packageName == packageName &&
                    callerName != null && current.contactName == callerName
            val sameSource = current.callSource == source && current.contactName == callerName

            if (sameKey || samePkgAndCaller || sameSource) {
                if (number != null && current.phoneNumber == null) current.phoneNumber = number
                if (notificationKey != null && current.notificationKey == null) current.notificationKey = notificationKey
                return current
            }
        }

        val session = CallDebugSession(
            id = sessionIdGenerator.incrementAndGet(),
            callSource = source,
            direction = direction,
            phoneNumber = number,
            contactName = callerName,
            packageName = packageName,
            notificationKey = notificationKey,
            state = CallSessionState.DETECTED
        )
        _activeSession.value = session
        addSessionToHistory(session)
        return session
    }

    private fun addSessionToHistory(session: CallDebugSession) {
        val list = _sessionHistory.value.toMutableList()
        if (list.none { it.id == session.id }) {
            list.add(session)
            if (list.size > 50) {
                list.removeAt(0)
            }
            _sessionHistory.value = list
        }
    }

    private fun updateSnapshot() {
        val session = _activeSession.value
        if (session == null) {
            _currentSnapshot.value = null
            return
        }
        _currentSnapshot.value = CallDebugSnapshot(
            sessionId = session.id,
            phoneNumber = session.phoneNumber,
            contactName = session.contactName,
            truecallerName = session.truecallerName,
            announcementName = session.announcedName,
            announcementSource = session.announcementSource,
            ttsText = session.ttsText,
            isMismatch = session.detectedBugs.any { it.type == BugType.IDENTITY_PRIORITY_VIOLATION },
            isRinging = session.state == CallSessionState.DETECTED || session.state == CallSessionState.IDENTIFYING || session.state == CallSessionState.IDENTIFIED,
            callSource = session.callSource,
            callDirection = session.direction,
            reason = session.blockReason,
            spamStatus = session.spamStatus,
            state = session.state,
            notifPackage = session.packageName,
            bugCount = session.detectedBugs.size
        )
    }

    /**
     * Called when a call is detected (PHONE_STATE, etc.).
     */
    @Synchronized
    fun onCallDetected(number: String?, source: String) {
        val session = getOrCreateGsmSession(number)
        session.addEvent("CALL DETECTED", "number=${number ?: "Unknown"}, source=$source")
        session.state = CallSessionState.IDENTIFYING
        updateSnapshot()

        DebugLogStore.log("DIAG", "[Session #${session.id}] GSM CALL DETECTED ($source)")
    }

    /**
     * Called when Contacts lookup for the number completes.
     */
    @Synchronized
    fun onContactLookupResult(number: String?, name: String?) {
        val session = _activeSession.value ?: getOrCreateGsmSession(number)
        if (number != null && session.phoneNumber == null) {
            session.phoneNumber = number
        }
        session.contactName = name
        session.identityUpdateCount++
        session.addEvent("CONTACT LOOKUP", "name='${name ?: "Unknown"}'")
        updateSnapshot()

        DebugLogStore.log("DIAG", "[Session #${session.id}] CONTACT RESULT: ${name ?: "Not found"}")
    }

    /**
     * Called when Truecaller overlay is parsed or received.
     *
     * Rule: Truecaller accessibility updates must NEVER create a call session by themselves.
     * They only update the currently relevant GSM call session when one exists.
     */
    @Synchronized
    fun onTruecallerResult(number: String?, name: String?, spamStatus: SpamStatus = SpamStatus.UNKNOWN) {
        val trimmed = name?.trim()
        if (trimmed.isNullOrEmpty()) return

        val active = _activeSession.value
        // If there is no active GSM call session, do NOT create a new call session or history entry!
        if (active == null || active.state == CallSessionState.ENDED || active.callSource != DebugCallSource.GSM) {
            DebugLogStore.log("DIAG", "TRUECALLER OBSERVATION (no active GSM call): '$trimmed' (number=${number ?: "Unknown"})")
            return
        }

        // Verify phone number association with the current active call
        val numberMismatchBug = CallBugDetector.checkNumberMismatch(active.phoneNumber, number)
        if (numberMismatchBug != null) {
            active.addBug(numberMismatchBug)
            active.addEvent("TRUECALLER NUMBER MISMATCH", "Active: ${active.phoneNumber}, Observed: $number")
            DebugLogStore.log("CALLER_ID", numberMismatchBug.format())
            updateSnapshot()
            return
        }

        // Check if announcement identity was already locked
        if (active.isIdentityLocked) {
            val lateBug = CallBugDetector.checkLateTruecaller(
                isIdentityLocked = true,
                lockedAnnouncement = active.announcedName,
                tcName = trimmed
            )
            if (lateBug != null) {
                active.addBug(lateBug)
                active.addEvent("POST-ANNOUNCEMENT TRUECALLER UPDATE", "Observed: $trimmed (Identity locked at '${active.announcedName}')")
                DebugLogStore.log("CALLER_ID", "✓ ANNOUNCEMENT IDENTITY LOCKED\nPOST-ANNOUNCEMENT TRUECALLER UPDATE: $trimmed")
                updateSnapshot()
            }
            return
        }

        active.truecallerName = trimmed
        if (spamStatus != SpamStatus.UNKNOWN) {
            active.spamStatus = spamStatus
        }
        active.identityUpdateCount++
        active.addEvent("TRUECALLER MATCH", "name='$trimmed', spam=$spamStatus")
        updateSnapshot()

        DebugLogStore.log("DIAG", "[Session #${active.id}] TRUECALLER MATCH: $trimmed")
    }

    /**
     * Called when stale UI or status text is rejected by TruecallerParser.
     */
    @Synchronized
    fun onTruecallerStaleText(staleText: String) {
        val active = _activeSession.value
        val bug = CallBugDetector.createStaleTextBug(staleText)
        active?.addBug(bug)
        active?.addEvent("TRUECALLER STALE/STATUS TEXT", "Value: $staleText (Ignored)")
        DebugLogStore.log("CALLER_ID", bug.format())
        updateSnapshot()
    }

    /**
     * Called when CallerIdentityManager selects the identity to announce.
     */
    @Synchronized
    fun onIdentitySelected(number: String?, selectedName: String?, source: String) {
        val session = _activeSession.value ?: getOrCreateGsmSession(number)
        if (number != null && session.phoneNumber == null) {
            session.phoneNumber = number
        }

        session.announcedName = selectedName ?: "Unknown caller"
        session.announcementSource = source
        session.state = CallSessionState.IDENTIFIED
        session.addEvent("IDENTITY SELECTED", "source=$source, name='$selectedName'")

        // Check bug detection for identity priority
        val priorityBug = CallBugDetector.checkIdentityPriority(
            contactName = session.contactName,
            truecallerName = session.truecallerName,
            announcementName = session.announcedName,
            announcementSource = source
        )
        if (priorityBug != null) {
            session.addBug(priorityBug)
            DebugLogStore.log("CALLER_ID", priorityBug.format())
        } else if (!session.contactName.isNullOrBlank() && !session.truecallerName.isNullOrBlank()) {
            session.addEvent("PRIORITY CHECK", "✓ PRIORITY CORRECT (CONTACT > TRUECALLER)")
        }

        updateSnapshot()
    }

    /**
     * Called immediately before sending text to AnnouncementManager.
     */
    @Synchronized
    fun onAnnouncementPrepared(name: String, text: String, source: String) {
        val session = _activeSession.value ?: getOrCreateGsmSession(null)
        session.announcedName = name
        session.ttsText = text
        session.announcementSource = source
        session.addEvent("ANNOUNCEMENT PREPARED", "text='$text'")
        updateSnapshot()
    }

    /**
     * Called when TTS audio actually starts speaking.
     */
    @Synchronized
    fun onTtsStarted(text: String, isRecognizerActive: Boolean = false) {
        val session = _activeSession.value ?: getOrCreateGsmSession(null)
        session.ttsCount++
        session.ttsText = text
        session.state = CallSessionState.ANNOUNCED
        session.isIdentityLocked = true
        session.addEvent("TTS START", "text='$text', count=${session.ttsCount}")

        // Check duplicate TTS
        val dupBug = CallBugDetector.checkDuplicateTts(session.ttsCount, isRepeatEnabled, text)
        if (dupBug != null) {
            session.addBug(dupBug)
            DebugLogStore.log("CALLER_ID", dupBug.format())
        }

        // Check audio pipeline conflict
        val audioConflictBug = CallBugDetector.checkAudioConflict(isRecognizerActive)
        if (audioConflictBug != null) {
            session.addBug(audioConflictBug)
            DebugLogStore.log("AUDIO", audioConflictBug.format())
        }

        updateSnapshot()
        DebugLogStore.log("DIAG", "[Session #${session.id}] TTS STARTED (count=${session.ttsCount})")
    }

    /**
     * Called when a VoIP call event is detected or updated.
     * Deduplicates repeated notification updates into the SAME session.
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
        detectorDecision: String? = null,
        notificationKey: String? = null
    ) {
        val isPhoneNumber = callerName?.matches(Regex("^[+]?[0-9\\s()-]{7,25}$")) == true
        val resolvedNumber = number ?: (if (isPhoneNumber) callerName else null)
        val resolvedContact = if (isPhoneNumber) "Unknown" else (callerName ?: "Unknown")

        val session = getOrCreateVoipSession(
            source = source,
            direction = direction,
            callerName = resolvedContact,
            number = resolvedNumber,
            packageName = packageName,
            notificationKey = notificationKey
        )

        session.direction = direction
        if (resolvedNumber != null) session.phoneNumber = resolvedNumber
        session.contactName = resolvedContact

        if (!allowed) {
            session.state = CallSessionState.BLOCKED
            session.announcedName = "BLOCKED"
            session.announcementSource = "-"
            session.blockReason = reason
            session.ttsText = "-"
            session.addEvent("VOIP BLOCKED", "reason=$reason, decision=$detectorDecision")
        } else {
            session.state = if (session.isIdentityLocked) CallSessionState.ANNOUNCED else CallSessionState.IDENTIFIED
            session.announcedName = resolvedContact
            session.announcementSource = source.name
            session.ttsText = ttsText ?: "Incoming call from $resolvedContact"
            session.addEvent("VOIP INCOMING", "caller=$resolvedContact, decision=$detectorDecision")
        }

        // Check for VoIP policy violation
        val voipBug = CallBugDetector.checkVoipPolicy(direction, allowed, reason)
        if (voipBug != null) {
            session.addBug(voipBug)
            DebugLogStore.log("VOIP", voipBug.format())
        }

        updateSnapshot()
    }

    /**
     * Called when an announcement is blocked (no BT, volume 0, spam policy, etc.).
     */
    @Synchronized
    fun onAnnouncementBlocked(reason: String) {
        val session = _activeSession.value ?: getOrCreateGsmSession(null)
        session.state = CallSessionState.BLOCKED
        session.blockReason = reason
        session.announcedName = "BLOCKED"
        session.ttsText = "-"
        session.addEvent("ANNOUNCEMENT BLOCKED", "reason=$reason")
        updateSnapshot()

        DebugLogStore.log("DIAG", "[Session #${session.id}] ANNOUNCEMENT BLOCKED: $reason")
    }

    /**
     * Records Bluetooth diagnostics during ringing.
     */
    @Synchronized
    fun recordBluetoothDiagnostics(
        connected: Boolean,
        deviceName: String?,
        ttsRoute: String = "BLUETOOTH SCO",
        speakerRoute: String = "SYSTEM RINGTONE"
    ) {
        val session = _activeSession.value ?: return
        session.bluetoothConnected = connected
        session.bluetoothDevice = deviceName
        session.ttsRoute = ttsRoute
        session.speakerRoute = speakerRoute
        session.addEvent("BLUETOOTH ROUTE", "connected=$connected, device=$deviceName, tts=$ttsRoute, ringtone=$speakerRoute")
    }

    /**
     * Records active call mic limitation.
     */
    @Synchronized
    fun recordActiveCallState() {
        val session = _activeSession.value ?: return
        session.state = CallSessionState.ACTIVE
        session.addEvent("ACTIVE CALL", "Voice recognition: unavailable (Reason: CALL AUDIO OWNS MICROPHONE)")
        DebugLogStore.log("DIAG", "[Session #${session.id}] ACTIVE CALL: CALL AUDIO OWNS MICROPHONE")
    }

    /**
     * Called when a call ends to seal the session and emit ONE clean summary card.
     */
    @Synchronized
    fun onCallEnded() {
        val session = _activeSession.value ?: return
        if (session.state == CallSessionState.ENDED) return

        session.state = CallSessionState.ENDED
        session.endTimeMs = System.currentTimeMillis()
        session.addEvent("CALL ENDED", "duration=${(session.endTimeMs - session.startTimeMs) / 1000}s")

        // Output exactly ONE formatted session card to DebugLogStore
        DebugLogStore.logRaw(formatCleanSessionCard(session))

        updateSnapshot()
    }

    /**
     * Resets tracker for tests or manual clear.
     */
    @Synchronized
    fun reset() {
        _activeSession.value = null
        _currentSnapshot.value = null
    }

    @Synchronized
    fun clearAll() {
        _activeSession.value = null
        _sessionHistory.value = emptyList()
        _currentSnapshot.value = null
    }

    // ------------------------------------------------ formatting & exports

    /**
     * Formats the clean compact session card specified in Section 19.
     */
    fun formatCleanSessionCard(session: CallDebugSession): String {
        val sb = java.lang.StringBuilder()
        val timeStr = summaryTimeFmt.format(Date(session.startTimeMs))
        val header = when (session.callSource) {
            DebugCallSource.GSM -> "$timeStr  GSM CALL"
            DebugCallSource.WHATSAPP -> "$timeStr  WHATSAPP CALL"
            DebugCallSource.TELEGRAM -> "$timeStr  TELEGRAM CALL"
            DebugCallSource.MESSENGER -> "$timeStr  MESSENGER CALL"
            else -> "$timeStr  ${session.callSource.displayName.uppercase()} CALL"
        }

        sb.appendLine(DIVIDER)
        sb.appendLine(header)
        sb.appendLine(DIVIDER)
        sb.appendLine("")
        sb.appendLine("Direction : ${session.direction}")
        sb.appendLine("Number    : ${session.phoneNumber ?: "Unknown"}")
        sb.appendLine("")
        if (session.callSource == DebugCallSource.GSM) {
            sb.appendLine("Phone     : ${session.contactName ?: "Unknown"}")
            sb.appendLine("Truecaller: ${session.truecallerName ?: "Unknown"}")
        } else {
            sb.appendLine("Contact   : ${session.contactName ?: "Unknown"}")
            sb.appendLine("Truecaller: ${session.truecallerName ?: "Unknown"}")
        }
        sb.appendLine("")
        sb.appendLine("Announced : ${session.announcedName ?: "Unknown"}")

        if (session.state == CallSessionState.BLOCKED) {
            sb.appendLine("Status    : ⚠ BLOCKED")
            if (session.blockReason != null) {
                sb.appendLine("")
                sb.appendLine("Reason    : ${session.blockReason}")
            }
        } else {
            sb.appendLine("Source    : ${session.announcementSource ?: "-"}")
            sb.appendLine("")
            sb.appendLine("TTS       : ${session.ttsText ?: "-"}")
            val statusDisplay = if (session.detectedBugs.isEmpty()) "✓ ANNOUNCED" else "⚠ ANNOUNCED (BUGS DETECTED)"
            sb.appendLine("Status    : $statusDisplay")
        }
        sb.appendLine("Spam      : ${session.spamStatus}")

        if (session.detectedBugs.isNotEmpty()) {
            sb.appendLine("")
            sb.appendLine("⚠ BUGS DETECTED (${session.detectedBugs.size}):")
            for (bug in session.detectedBugs) {
                sb.appendLine("  • ${bug.title}")
                bug.details.lines().forEach { line ->
                    sb.appendLine("    $line")
                }
            }
        }

        sb.appendLine(DIVIDER)
        return sb.toString().trimEnd()
    }

    /**
     * Formats granular technical event stream for Developer Details (Section 20).
     */
    fun formatSessionDeveloperDetails(session: CallDebugSession): String {
        val sb = java.lang.StringBuilder()
        sb.appendLine("=== SESSION #${session.id} TECHNICAL TIMELINE ===")
        sb.appendLine("Source: ${session.callSource.displayName} | Direction: ${session.direction} | State: ${session.state}")
        sb.appendLine("Number: ${session.phoneNumber ?: "None"} | Contact: ${session.contactName ?: "None"} | Truecaller: ${session.truecallerName ?: "None"}")
        sb.appendLine("Bluetooth: ${if (session.bluetoothConnected) "CONNECTED (${session.bluetoothDevice ?: "Unknown"})" else "DISCONNECTED"}")
        sb.appendLine("TTS Route: ${session.ttsRoute ?: "-"} | Speaker: ${session.speakerRoute ?: "-"}")
        sb.appendLine("--------------------------------------------------")
        sb.appendLine("EVENTS:")
        if (session.events.isEmpty()) {
            sb.appendLine("  (no events recorded)")
        } else {
            session.events.forEach { sb.appendLine("  ${it.formatted()}") }
        }
        if (session.detectedBugs.isNotEmpty()) {
            sb.appendLine("--------------------------------------------------")
            sb.appendLine("AUTOMATIC BUG REPORTS:")
            session.detectedBugs.forEach {
                sb.appendLine("  [${it.type}] ${it.title}")
                sb.appendLine("    ${it.details.replace("\n", "\n    ")}")
            }
        }
        sb.appendLine("==================================================")
        return sb.toString()
    }

    /**
     * Export Current Session report.
     */
    fun exportCurrentSession(): String {
        val session = _activeSession.value ?: _sessionHistory.value.lastOrNull()
        if (session == null) return "No active or recorded call session found."
        return buildString {
            appendLine(formatCleanSessionCard(session))
            appendLine()
            appendLine(formatSessionDeveloperDetails(session))
        }
    }

    /**
     * Export Last N Sessions report.
     */
    fun exportLastSessions(count: Int = 10): String {
        val list = _sessionHistory.value.takeLast(count)
        if (list.isEmpty()) return "No recorded call sessions found."
        return buildString {
            appendLine("CALL SESSIONS EXPORT (Last ${list.size} sessions)")
            appendLine("Generated: ${now()}")
            appendLine("==================================================")
            appendLine()
            list.forEach { session ->
                appendLine(formatCleanSessionCard(session))
                appendLine()
                appendLine(formatSessionDeveloperDetails(session))
                appendLine()
            }
        }
    }

    /**
     * Export Debug Log.
     */
    fun exportDebugLog(): String {
        val rawLogs = DebugLogStore.logs.value.joinToString("\n") { it.formatted() }
        val sessions = exportLastSessions(10)
        return buildString {
            appendLine("=== CALL HANDLER COMPLETE DIAGNOSTIC LOG ===")
            appendLine("Generated: ${now()}")
            appendLine()
            appendLine(sessions)
            appendLine()
            appendLine("=== RAW DEBUG LOG STORE ===")
            appendLine(rawLogs)
        }
    }
}
