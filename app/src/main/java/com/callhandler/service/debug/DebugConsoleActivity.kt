package com.callhandler.service.debug

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.callhandler.service.R
import com.callhandler.service.voice.VoiceCommand
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Developer debug console with:
 * 1. Clean visual hierarchy and strong Material contrast
 * 2. Prominent Call History and Developer Details navigation
 * 3. 0 clipped buttons with full-label display
 * 4. Collapsible Developer Tools (collapsed by default)
 * 5. Fullscreen Console-Only mode with auto-scroll
 */
class DebugConsoleActivity : AppCompatActivity() {

    // Header views
    private lateinit var headerToolbar: View
    private lateinit var btnClearLogs: ImageButton
    private lateinit var btnToggleFullscreen: ImageButton

    // Status views
    private lateinit var statusAccessibility: TextView
    private lateinit var statusTruecaller: TextView
    private lateinit var statusObservation: TextView

    // Console-Only Containers and views
    private lateinit var containerConsoleOnlyBar: View
    private lateinit var containerConsoleOnlyBottom: View
    private lateinit var btnConsoleOnlyExit: ImageButton
    private lateinit var btnConsoleOnlyCopyDev: Button

    // Standard Controls Container
    private lateinit var containerStandardControls: View

    // Collapsible Developer Tools
    private lateinit var headerDeveloperTools: View
    private lateinit var imgExpandTools: ImageView
    private lateinit var containerToolsBody: View

    // Section Switcher Buttons
    private lateinit var btnViewHistory: Button
    private lateinit var btnViewDeveloper: Button

    // Copy Buttons
    private lateinit var btnCopyCallHistory: Button
    private lateinit var btnCopyDeveloperDetails: Button

    // Filter Chips
    private lateinit var containerDeveloperFilters: View
    private lateinit var chipFilterAll: Button
    private lateinit var chipFilterGsm: Button
    private lateinit var chipFilterWhatsapp: Button
    private lateinit var chipFilterVoip: Button
    private lateinit var chipFilterBugs: Button

    // Collapsible Tool Panels
    private lateinit var panelAccessibility: View
    private lateinit var panelSpeech: View
    private lateinit var panelCallerId: View
    private lateinit var btnToggleVerboseA11y: Button

    // Caller-ID Card views
    private lateinit var cardResultStatus: TextView
    private lateinit var cardCallSource: TextView
    private lateinit var cardDirection: TextView
    private lateinit var cardPhoneNumber: TextView
    private lateinit var cardContactName: TextView
    private lateinit var cardTruecallerName: TextView
    private lateinit var cardAnnouncementName: TextView
    private lateinit var cardSource: TextView
    private lateinit var cardTtsText: TextView

    // Log views
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView

    // State
    private var isDeveloperDetailsMode = false
    private var isConsoleOnlyMode = false
    private var currentDeveloperFilter = DeveloperFilter.ALL

    // Speech test
    private var testRecognizer: SpeechRecognizer? = null
    private var isRecognizerRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug_console)

        // 1. Header Toolbar
        headerToolbar = findViewById(R.id.headerToolbar)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        btnToggleFullscreen = findViewById(R.id.btnToggleFullscreen)

        // 2. Status Views
        statusAccessibility = findViewById(R.id.statusAccessibility)
        statusTruecaller = findViewById(R.id.statusTruecaller)
        statusObservation = findViewById(R.id.statusObservation)

        // 3. Containers
        containerStandardControls = findViewById(R.id.containerStandardControls)
        containerConsoleOnlyBar = findViewById(R.id.containerConsoleOnlyBar)
        containerConsoleOnlyBottom = findViewById(R.id.containerConsoleOnlyBottom)
        btnConsoleOnlyExit = findViewById(R.id.btnConsoleOnlyExit)
        btnConsoleOnlyCopyDev = findViewById(R.id.btnConsoleOnlyCopyDev)

        // 4. Collapsible Developer Tools
        headerDeveloperTools = findViewById(R.id.headerDeveloperTools)
        imgExpandTools = findViewById(R.id.imgExpandTools)
        containerToolsBody = findViewById(R.id.containerToolsBody)

        // 5. Section Switcher
        btnViewHistory = findViewById(R.id.btnViewHistory)
        btnViewDeveloper = findViewById(R.id.btnViewDeveloper)

        // 6. Copy Buttons
        btnCopyCallHistory = findViewById(R.id.btnCopyCallHistory)
        btnCopyDeveloperDetails = findViewById(R.id.btnCopyDeveloperDetails)

        // 7. Filter Chips
        containerDeveloperFilters = findViewById(R.id.containerDeveloperFilters)
        chipFilterAll = findViewById(R.id.chipFilterAll)
        chipFilterGsm = findViewById(R.id.chipFilterGsm)
        chipFilterWhatsapp = findViewById(R.id.chipFilterWhatsapp)
        chipFilterVoip = findViewById(R.id.chipFilterVoip)
        chipFilterBugs = findViewById(R.id.chipFilterBugs)

        // 8. Tool Panels
        panelAccessibility = findViewById(R.id.panelAccessibility)
        panelSpeech = findViewById(R.id.panelSpeech)
        panelCallerId = findViewById(R.id.panelCallerId)
        btnToggleVerboseA11y = findViewById(R.id.btnToggleVerboseA11y)

        // 9. Card Views
        cardResultStatus = findViewById(R.id.cardResultStatus)
        cardCallSource = findViewById(R.id.cardCallSource)
        cardDirection = findViewById(R.id.cardDirection)
        cardPhoneNumber = findViewById(R.id.cardPhoneNumber)
        cardContactName = findViewById(R.id.cardContactName)
        cardTruecallerName = findViewById(R.id.cardTruecallerName)
        cardAnnouncementName = findViewById(R.id.cardAnnouncementName)
        cardSource = findViewById(R.id.cardSource)
        cardTtsText = findViewById(R.id.cardTtsText)

        // 10. Log Views
        logTextView = findViewById(R.id.logTextView)
        logScrollView = findViewById(R.id.logScrollView)

        // Setup Header Actions
        btnClearLogs.setOnClickListener {
            DebugLogStore.clear()
            CallDebugTracker.clearAll()
            renderLogs()
            Toast.makeText(this, "Logs & Sessions cleared", Toast.LENGTH_SHORT).show()
        }
        btnToggleFullscreen.setOnClickListener {
            setConsoleOnlyMode(true)
        }
        btnConsoleOnlyExit.setOnClickListener {
            setConsoleOnlyMode(false)
        }

        // Setup Collapsible Developer Tools (Requirement 9)
        headerDeveloperTools.setOnClickListener {
            val isCurrentlyVisible = containerToolsBody.visibility == View.VISIBLE
            containerToolsBody.visibility = if (isCurrentlyVisible) View.GONE else View.VISIBLE
            imgExpandTools.setImageResource(
                if (isCurrentlyVisible) R.drawable.ic_expand_more else R.drawable.ic_expand_less
            )
        }

        // Tool Tabs inside Collapsible Section
        findViewById<Button>(R.id.tabAccessibility).setOnClickListener { showPanel(0) }
        findViewById<Button>(R.id.tabSpeech).setOnClickListener { showPanel(1) }
        findViewById<Button>(R.id.tabCallerIdDebug).setOnClickListener { showPanel(2) }

        // Accessibility controls
        findViewById<Button>(R.id.btnOpenA11ySettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnStartObserving).setOnClickListener {
            val svc = TruecallerAccessibilityService.instance
            if (svc != null) {
                svc.startObserving()
                updateStatus()
            } else {
                Toast.makeText(this, "Enable accessibility service in Settings first", Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.btnStopObserving).setOnClickListener {
            TruecallerAccessibilityService.instance?.stopObserving()
            updateStatus()
        }
        btnToggleVerboseA11y.setOnClickListener {
            TruecallerAccessibilityService.verboseLogging = !TruecallerAccessibilityService.verboseLogging
            updateVerboseA11yButton()
        }

        // Section Navigation (Requirement 6)
        btnViewHistory.setOnClickListener {
            isDeveloperDetailsMode = false
            updateSectionUi()
            renderLogs()
        }
        btnViewDeveloper.setOnClickListener {
            isDeveloperDetailsMode = true
            updateSectionUi()
            renderLogs()
        }

        // Dedicated Copy Buttons (Requirement 7)
        btnCopyCallHistory.setOnClickListener {
            copyToClipboard(CallDebugTracker.exportCleanCallHistory(), "Call History")
        }
        btnCopyDeveloperDetails.setOnClickListener {
            copyToClipboard(CallDebugTracker.exportDeveloperDetails(), "Developer Details")
        }
        btnConsoleOnlyCopyDev.setOnClickListener {
            copyToClipboard(CallDebugTracker.exportDeveloperDetails(), "Developer Details")
        }

        // Developer Filter Chips (Requirement 12)
        chipFilterAll.setOnClickListener { setDeveloperFilter(DeveloperFilter.ALL) }
        chipFilterGsm.setOnClickListener { setDeveloperFilter(DeveloperFilter.GSM) }
        chipFilterWhatsapp.setOnClickListener { setDeveloperFilter(DeveloperFilter.WHATSAPP) }
        chipFilterVoip.setOnClickListener { setDeveloperFilter(DeveloperFilter.VOIP) }
        chipFilterBugs.setOnClickListener { setDeveloperFilter(DeveloperFilter.BUGS) }

        // Simulators
        findViewById<Button>(R.id.btnSimulateMatch).setOnClickListener { simulateCall(mismatch = false) }
        findViewById<Button>(R.id.btnSimulateMismatch).setOnClickListener { simulateCall(mismatch = true) }
        findViewById<Button>(R.id.btnSimulateWaIncoming).setOnClickListener { simulateWaCall(incoming = true) }
        findViewById<Button>(R.id.btnSimulateWaOutgoing).setOnClickListener { simulateWaCall(incoming = false) }

        // Speech Test Controls
        findViewById<Button>(R.id.btnStartRecognizer).setOnClickListener { startTestRecognizer() }
        findViewById<Button>(R.id.btnStopRecognizer).setOnClickListener { stopTestRecognizer() }
        findViewById<Button>(R.id.btnTestAnswer).setOnClickListener { testCommandMatch("answer") }
        findViewById<Button>(R.id.btnTestReject).setOnClickListener { testCommandMatch("reject") }
        findViewById<Button>(R.id.btnTestSilent).setOnClickListener { testCommandMatch("silent") }
        findViewById<Button>(R.id.btnTestSpeaker).setOnClickListener { testCommandMatch("speaker") }

        // Live Observation Observers
        lifecycleScope.launch {
            DebugLogStore.logs.collect { renderLogs() }
        }
        lifecycleScope.launch {
            CallDebugTracker.sessionHistory.collect { renderLogs() }
        }
        lifecycleScope.launch {
            CallDebugTracker.activeSession.collect { renderLogs() }
        }
        lifecycleScope.launch {
            CallDebugTracker.currentSnapshot.collect { snapshot -> updateCallerIdCard(snapshot) }
        }

        // Initialize UI States
        updateStatus()
        updateVerboseA11yButton()
        updateSectionUi()
        updateFilterChipsUi()
        renderLogs()
    }

    private fun setConsoleOnlyMode(enabled: Boolean) {
        isConsoleOnlyMode = enabled
        if (enabled) {
            headerToolbar.visibility = View.GONE
            containerStandardControls.visibility = View.GONE
            containerConsoleOnlyBar.visibility = View.VISIBLE
            containerConsoleOnlyBottom.visibility = View.VISIBLE
            isDeveloperDetailsMode = true
        } else {
            headerToolbar.visibility = View.VISIBLE
            containerStandardControls.visibility = View.VISIBLE
            containerConsoleOnlyBar.visibility = View.GONE
            containerConsoleOnlyBottom.visibility = View.GONE
        }
        updateSectionUi()
        renderLogs()
    }

    private fun setDeveloperFilter(filter: DeveloperFilter) {
        currentDeveloperFilter = filter
        updateFilterChipsUi()
        renderLogs()
    }

    private fun updateSectionUi() {
        val selectedBg = Color.parseColor("#2563EB")
        val unselectedBg = Color.TRANSPARENT
        val selectedText = Color.WHITE
        val unselectedText = Color.parseColor("#94A3B8")

        if (isDeveloperDetailsMode) {
            btnViewDeveloper.setBackgroundColor(selectedBg)
            btnViewDeveloper.setTextColor(selectedText)
            btnViewHistory.setBackgroundColor(unselectedBg)
            btnViewHistory.setTextColor(unselectedText)
            containerDeveloperFilters.visibility = if (isConsoleOnlyMode) View.GONE else View.VISIBLE
        } else {
            btnViewHistory.setBackgroundColor(Color.parseColor("#16A34A"))
            btnViewHistory.setTextColor(selectedText)
            btnViewDeveloper.setBackgroundColor(unselectedBg)
            btnViewDeveloper.setTextColor(unselectedText)
            containerDeveloperFilters.visibility = View.GONE
        }
    }

    private fun updateFilterChipsUi() {
        val activeBg = Color.parseColor("#2563EB")
        val inactiveBg = Color.parseColor("#1E293B")
        val activeText = Color.WHITE
        val inactiveText = Color.parseColor("#CBD5E1")

        fun applyChipStyle(button: Button, isActive: Boolean, isBug: Boolean = false) {
            if (isActive) {
                button.setBackgroundColor(if (isBug) Color.parseColor("#DC2626") else activeBg)
                button.setTextColor(activeText)
            } else {
                button.setBackgroundColor(inactiveBg)
                button.setTextColor(inactiveText)
            }
        }

        applyChipStyle(chipFilterAll, currentDeveloperFilter == DeveloperFilter.ALL)
        applyChipStyle(chipFilterGsm, currentDeveloperFilter == DeveloperFilter.GSM)
        applyChipStyle(chipFilterWhatsapp, currentDeveloperFilter == DeveloperFilter.WHATSAPP)
        applyChipStyle(chipFilterVoip, currentDeveloperFilter == DeveloperFilter.VOIP)
        applyChipStyle(chipFilterBugs, currentDeveloperFilter == DeveloperFilter.BUGS, isBug = true)
    }

    private fun renderLogs() {
        val text = if (isDeveloperDetailsMode) {
            CallDebugTracker.getFilteredDeveloperTimeline(currentDeveloperFilter)
        } else {
            val history = CallDebugTracker.getCleanCallHistoryText()
            if (history.isNotBlank()) history else "No calls recorded yet."
        }

        logTextView.text = text
        logScrollView.post {
            logScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun copyToClipboard(content: String, label: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, content)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "$label copied to clipboard!", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateVerboseA11yButton()
    }

    override fun onDestroy() {
        stopTestRecognizer()
        super.onDestroy()
    }

    private fun showPanel(index: Int) {
        val target = when (index) {
            0 -> panelAccessibility
            1 -> panelSpeech
            2 -> panelCallerId
            else -> null
        }
        val isCurrentlyVisible = target?.visibility == View.VISIBLE
        panelAccessibility.visibility = View.GONE
        panelSpeech.visibility = View.GONE
        panelCallerId.visibility = View.GONE

        if (!isCurrentlyVisible && target != null) {
            target.visibility = View.VISIBLE
        }
    }

    private fun updateStatus() {
        val serviceConnected = TruecallerAccessibilityService.isServiceConnected
        statusAccessibility.text = if (serviceConnected) "● ENABLED" else "● DISABLED"
        statusAccessibility.setTextColor(
            if (serviceConnected) Color.parseColor("#4ADE80") else Color.parseColor("#F87171")
        )

        val truecallerInstalled = isTruecallerInstalled()
        statusTruecaller.text = if (truecallerInstalled) "● INSTALLED" else "● NOT FOUND"
        statusTruecaller.setTextColor(
            if (truecallerInstalled) Color.parseColor("#4ADE80") else Color.parseColor("#F87171")
        )

        val observing = TruecallerAccessibilityService.instance?.observing == true
        statusObservation.text = if (observing) "● RUNNING" else "● STOPPED"
        statusObservation.setTextColor(
            if (observing) Color.parseColor("#4ADE80") else Color.parseColor("#94A3B8")
        )
    }

    private fun updateVerboseA11yButton() {
        val isVerbose = TruecallerAccessibilityService.verboseLogging
        btnToggleVerboseA11y.text = if (isVerbose) {
            "Verbose Tree: ON (Full Dumps)"
        } else {
            "Verbose Tree: OFF"
        }
    }

    private fun updateCallerIdCard(snapshot: CallDebugTracker.CallDebugSnapshot?) {
        if (snapshot == null) {
            cardResultStatus.text = "STATUS: IDLE"
            cardResultStatus.setTextColor(Color.GRAY)
            cardCallSource.text = "Source: -"
            cardDirection.text = "Direction: -"
            cardPhoneNumber.text = "Number: None"
            cardContactName.text = "Contact: -"
            cardTruecallerName.text = "Truecaller: -"
            cardAnnouncementName.text = "Announced: -"
            cardSource.text = "Source: -"
            cardTtsText.text = "TTS: -"
            return
        }

        when {
            snapshot.bugCount > 0 -> {
                cardResultStatus.text = "RESULT: ⚠ BUG DETECTED (${snapshot.bugCount})"
                cardResultStatus.setTextColor(Color.parseColor("#FF5252"))
            }
            snapshot.announcementName == "BLOCKED" -> {
                cardResultStatus.text = "RESULT: 🚫 BLOCKED (${snapshot.reason ?: "POLICY"})"
                cardResultStatus.setTextColor(Color.parseColor("#FF5252"))
            }
            snapshot.announcementName != null -> {
                cardResultStatus.text = "RESULT: ✓ IDENTITY ANNOUNCED"
                cardResultStatus.setTextColor(Color.parseColor("#4ADE80"))
            }
            snapshot.isRinging -> {
                cardResultStatus.text = "STATUS: 📞 CALL IN PROGRESS"
                cardResultStatus.setTextColor(Color.parseColor("#FBBF24"))
            }
            else -> {
                cardResultStatus.text = "STATUS: IDLE"
                cardResultStatus.setTextColor(Color.GRAY)
            }
        }

        cardCallSource.text = "Source: ${snapshot.callSource.displayName}"
        cardDirection.text = "Direction: ${snapshot.callDirection ?: "-"}"
        cardPhoneNumber.text = "Number: ${snapshot.phoneNumber ?: "Unknown"}"
        cardContactName.text = "Contact: ${snapshot.contactName ?: "Unknown"}"
        cardTruecallerName.text = "Truecaller: ${snapshot.truecallerName ?: "Waiting..."}"
        cardAnnouncementName.text = "Announced: ${snapshot.announcementName ?: "Waiting..."}"
        cardSource.text = "Source: ${snapshot.announcementSource ?: "-"}"
        cardTtsText.text = "TTS: ${snapshot.ttsText ?: "-"}"
    }

    private fun simulateCall(mismatch: Boolean) {
        val testNumber = "09805305407"
        val tcName = "Pavinder Jasrotia"

        CallDebugTracker.reset()
        CallDebugTracker.onCallDetected(testNumber, "PHONE_STATE")
        CallDebugTracker.onContactLookupResult(testNumber, "Aman")
        CallDebugTracker.onTruecallerResult(testNumber, tcName)

        if (mismatch) {
            CallDebugTracker.onIdentitySelected(testNumber, tcName, "TRUECALLER")
            CallDebugTracker.onAnnouncementPrepared(tcName, "Incoming call from $tcName", "TRUECALLER")
            CallDebugTracker.onTtsStarted("Incoming call from $tcName")
        } else {
            CallDebugTracker.onIdentitySelected(testNumber, "Aman", "CONTACT")
            CallDebugTracker.onAnnouncementPrepared("Aman", "Incoming call from Aman", "CONTACT")
            CallDebugTracker.onTtsStarted("Incoming call from Aman")
        }
        CallDebugTracker.onCallEnded()
    }

    private fun simulateWaCall(incoming: Boolean) {
        CallDebugTracker.reset()
        if (incoming) {
            val tcName = "Pavinder Jasrotia"
            val waNumber = "+919805305407"
            CallDebugTracker.onTruecallerResult(waNumber, tcName)
            CallDebugTracker.onVoipCallEvent(
                source = DebugCallSource.WHATSAPP,
                direction = "INCOMING",
                callerName = tcName,
                number = waNumber,
                allowed = true,
                reason = null,
                ttsText = "Incoming call from $tcName",
                packageName = "com.whatsapp",
                notifTitle = tcName,
                notifText = "Incoming voice call",
                detectorDecision = "INCOMING_CALL"
            )
            CallDebugTracker.onTtsStarted("Incoming call from $tcName")
            CallDebugTracker.onCallEnded()
        } else {
            val contactName = "Tushar"
            CallDebugTracker.onVoipCallEvent(
                source = DebugCallSource.WHATSAPP,
                direction = "OUTGOING",
                callerName = contactName,
                number = null,
                allowed = false,
                reason = "OUTGOING CALL",
                ttsText = null,
                packageName = "com.whatsapp",
                notifTitle = contactName,
                notifText = "Calling...",
                detectorDecision = "OUTGOING_CALL"
            )
        }
    }

    private fun isTruecallerInstalled(): Boolean {
        val direct = runCatching {
            packageManager.getPackageInfo(TruecallerAccessibilityService.TRUECALLER_PACKAGE, 0)
        }.isSuccess
        if (direct) return true

        val launchIntent = packageManager.getLaunchIntentForPackage(
            TruecallerAccessibilityService.TRUECALLER_PACKAGE
        )
        if (launchIntent != null) return true

        return TruecallerAccessibilityService.isServiceConnected
    }

    private fun startTestRecognizer() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            DebugLogStore.log("SPEECH", "ERROR: RECORD_AUDIO permission not granted")
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            DebugLogStore.log("SPEECH", "ERROR: Speech recognition not available")
            return
        }

        if (isRecognizerRunning) {
            DebugLogStore.log("SPEECH", "Already running")
            return
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        testRecognizer = recognizer
        isRecognizerRunning = true

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5000L)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                DebugLogStore.log("SPEECH", "RECOGNIZER STARTED — listening")
            }

            override fun onBeginningOfSpeech() {
                DebugLogStore.log("SPEECH", "Speech detected")
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                DebugLogStore.log("SPEECH", "End of speech")
            }

            override fun onError(error: Int) {
                val name = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
                    SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
                    SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
                    SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
                    else -> "UNKNOWN($error)"
                }
                DebugLogStore.log("SPEECH", "ERROR: $name")

                if (isRecognizerRunning && error in listOf(
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    )
                ) {
                    runCatching { recognizer.startListening(intent) }
                }
            }

            override fun onResults(results: Bundle?) {
                val phrases = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

                val receiveTime = SystemClock.elapsedRealtime()
                DebugLogStore.log("SPEECH", "FINAL = ${phrases.joinToString(", ") { "\"$it\"" }}")

                if (phrases.isNotEmpty()) {
                    matchAndLog(phrases.first(), receiveTime)
                }

                if (isRecognizerRunning) {
                    runCatching { recognizer.startListening(intent) }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val phrases = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

                if (phrases.isNotEmpty()) {
                    val receiveTime = SystemClock.elapsedRealtime()
                    DebugLogStore.log("SPEECH", "PARTIAL = \"${phrases.first()}\"")
                    matchAndLog(phrases.first(), receiveTime)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        DebugLogStore.log("SPEECH", "Starting recognizer...")
        recognizer.startListening(intent)
    }

    private fun matchAndLog(raw: String, receiveTimeMs: Long) {
        val command = VoiceCommand.fromPhrase(raw)
        val matchTime = SystemClock.elapsedRealtime()
        val latency = matchTime - receiveTimeMs

        if (command != null) {
            DebugLogStore.log("SPEECH", "MATCH = $command")
            DebugLogStore.log("SPEECH", "ACTION = EXECUTED (test mode — no real action)")
            DebugLogStore.log("SPEECH", "LATENCY = ${latency}ms")
        } else {
            DebugLogStore.log("SPEECH", "MATCH = NONE")
        }
    }

    private fun stopTestRecognizer() {
        isRecognizerRunning = false
        testRecognizer?.let { rec ->
            runCatching { rec.cancel() }
            runCatching { rec.destroy() }
        }
        testRecognizer = null
        DebugLogStore.log("SPEECH", "Recognizer stopped")
    }

    private fun testCommandMatch(phrase: String) {
        val start = SystemClock.elapsedRealtime()
        DebugLogStore.log("SPEECH", "TEST INPUT = \"$phrase\"")
        matchAndLog(phrase, start)
    }
}
