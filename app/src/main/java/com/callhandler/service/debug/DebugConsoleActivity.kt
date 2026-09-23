package com.callhandler.service.debug

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.callhandler.service.R
import com.callhandler.service.voice.VoiceCommand
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Developer debug console for:
 * 1. Truecaller accessibility tree inspection
 * 2. Speech recognizer testing (without a live call)
 * 3. Caller-ID pipeline debugging
 *
 * Accessible from MainActivity's Developer section.
 */
class DebugConsoleActivity : AppCompatActivity() {

    // Status views
    private lateinit var statusAccessibility: TextView
    private lateinit var statusTruecaller: TextView
    private lateinit var statusObservation: TextView

    // Panels
    private lateinit var panelAccessibility: View
    private lateinit var panelSpeech: View
    private lateinit var panelCallerId: View

    // Verbose A11y toggle
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
    private lateinit var callerIdDebugText: TextView

    // Speech test
    private var testRecognizer: SpeechRecognizer? = null
    private var isRecognizerRunning = false

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug_console)

        // Bind status views
        statusAccessibility = findViewById(R.id.statusAccessibility)
        statusTruecaller = findViewById(R.id.statusTruecaller)
        statusObservation = findViewById(R.id.statusObservation)

        // Panels
        panelAccessibility = findViewById(R.id.panelAccessibility)
        panelSpeech = findViewById(R.id.panelSpeech)
        panelCallerId = findViewById(R.id.panelCallerId)

        // Log views
        logTextView = findViewById(R.id.logTextView)
        logScrollView = findViewById(R.id.logScrollView)
        callerIdDebugText = findViewById(R.id.callerIdDebugText)

        // Tab buttons
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
                Toast.makeText(this, "Enable the accessibility service first", Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.btnStopObserving).setOnClickListener {
            TruecallerAccessibilityService.instance?.stopObserving()
            updateStatus()
        }
        findViewById<Button>(R.id.btnDumpWindow).setOnClickListener {
            val svc = TruecallerAccessibilityService.instance
            if (svc != null) {
                svc.dumpCurrentWindows()
            } else {
                DebugLogStore.log("DUMP", "ERROR: Accessibility service not connected. Enable it in Settings.")
                Toast.makeText(this, "Enable the accessibility service first", Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.btnCopyLogs).setOnClickListener {
            val text = DebugLogStore.logs.value.joinToString("\n") { it.formatted() }
            if (text.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Truecaller Debug Logs", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Logs copied to clipboard!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No logs to copy", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnClearLogs).setOnClickListener {
            DebugLogStore.clear()
            CallDebugTracker.reset()
        }

        btnToggleVerboseA11y = findViewById(R.id.btnToggleVerboseA11y)
        btnToggleVerboseA11y.setOnClickListener {
            TruecallerAccessibilityService.verboseLogging = !TruecallerAccessibilityService.verboseLogging
            updateVerboseA11yButton()
        }

        // Caller-ID card views
        cardResultStatus = findViewById(R.id.cardResultStatus)
        cardCallSource = findViewById(R.id.cardCallSource)
        cardDirection = findViewById(R.id.cardDirection)
        cardPhoneNumber = findViewById(R.id.cardPhoneNumber)
        cardContactName = findViewById(R.id.cardContactName)
        cardTruecallerName = findViewById(R.id.cardTruecallerName)
        cardAnnouncementName = findViewById(R.id.cardAnnouncementName)
        cardSource = findViewById(R.id.cardSource)
        cardTtsText = findViewById(R.id.cardTtsText)

        findViewById<Button>(R.id.btnSimulateMatch).setOnClickListener {
            simulateCall(mismatch = false)
        }
        findViewById<Button>(R.id.btnSimulateMismatch).setOnClickListener {
            simulateCall(mismatch = true)
        }
        findViewById<Button>(R.id.btnSimulateWaIncoming).setOnClickListener {
            simulateWaCall(incoming = true)
        }
        findViewById<Button>(R.id.btnSimulateWaOutgoing).setOnClickListener {
            simulateWaCall(incoming = false)
        }
        findViewById<Button>(R.id.btnClearCallerIdLogs).setOnClickListener {
            DebugLogStore.clear()
            CallDebugTracker.reset()
        }

        // Speech test controls
        findViewById<Button>(R.id.btnStartRecognizer).setOnClickListener { startTestRecognizer() }
        findViewById<Button>(R.id.btnStopRecognizer).setOnClickListener { stopTestRecognizer() }
        findViewById<Button>(R.id.btnTestAnswer).setOnClickListener { testCommandMatch("answer") }
        findViewById<Button>(R.id.btnTestReject).setOnClickListener { testCommandMatch("reject") }
        findViewById<Button>(R.id.btnTestSilent).setOnClickListener { testCommandMatch("silent") }
        findViewById<Button>(R.id.btnTestSpeaker).setOnClickListener { testCommandMatch("speaker") }
        findViewById<Button>(R.id.btnClearSpeechLogs).setOnClickListener {
            DebugLogStore.clear()
        }

        // Observe log store
        lifecycleScope.launch {
            DebugLogStore.logs.collect { entries ->
                val text = entries.joinToString("\n") { it.formatted() }
                logTextView.text = text
                logScrollView.post {
                    logScrollView.fullScroll(View.FOCUS_DOWN)
                }
            }
        }

        // Observe CallDebugTracker
        lifecycleScope.launch {
            CallDebugTracker.currentSnapshot.collect { snapshot ->
                updateCallerIdCard(snapshot)
            }
        }

        updateStatus()
        updateVerboseA11yButton()
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

    // -------------------------------------------------- panel switching

    private fun showPanel(index: Int) {
        panelAccessibility.visibility = if (index == 0) View.VISIBLE else View.GONE
        panelSpeech.visibility = if (index == 1) View.VISIBLE else View.GONE
        panelCallerId.visibility = if (index == 2) View.VISIBLE else View.GONE
    }

    // -------------------------------------------------- status updates

    private fun updateStatus() {
        // Accessibility service status
        val serviceConnected = TruecallerAccessibilityService.isServiceConnected
        statusAccessibility.text = "Accessibility Service: ${if (serviceConnected) "✅ ENABLED" else "❌ DISABLED"}"

        // Truecaller installed check
        val truecallerInstalled = isTruecallerInstalled()
        statusTruecaller.text = "Truecaller: ${if (truecallerInstalled) "✅ INSTALLED" else "❌ NOT FOUND"}"

        // Observation status
        val observing = TruecallerAccessibilityService.instance?.observing == true
        statusObservation.text = "Observation: ${if (observing) "🟢 RUNNING" else "⏹ STOPPED"}"
    }

    private fun updateVerboseA11yButton() {
        val isVerbose = TruecallerAccessibilityService.verboseLogging
        btnToggleVerboseA11y.text = if (isVerbose) {
            "Verbose Tree: ON (Full Dumps)"
        } else {
            "Verbose Tree: OFF (Clean Debug)"
        }
    }

    private fun updateCallerIdCard(snapshot: CallDebugTracker.CallDebugSnapshot?) {
        if (snapshot == null) {
            cardResultStatus.text = "STATUS         : IDLE"
            cardResultStatus.setTextColor(Color.GRAY)
            cardCallSource.text = "Call Source    : -"
            cardDirection.text = "Direction      : -"
            cardPhoneNumber.text = "Number         : None"
            cardContactName.text = "Phone/Contacts : -"
            cardTruecallerName.text = "Truecaller     : -"
            cardAnnouncementName.text = "Announcement   : -"
            cardSource.text = "Source         : -"
            cardTtsText.text = "TTS Text       : -"
            return
        }

        when {
            snapshot.isMismatch -> {
                cardResultStatus.text = "RESULT         : ⚠ IDENTITY MISMATCH"
                cardResultStatus.setTextColor(Color.parseColor("#FF5252"))
            }
            snapshot.announcementName != null && snapshot.announcementName != "BLOCKED" -> {
                cardResultStatus.text = "RESULT         : ✓ IDENTITY MATCH"
                cardResultStatus.setTextColor(Color.parseColor("#4CAF50"))
            }
            snapshot.announcementName == "BLOCKED" -> {
                cardResultStatus.text = "RESULT         : 🚫 BLOCKED (${snapshot.reason ?: "POLICY"})"
                cardResultStatus.setTextColor(Color.parseColor("#FF5252"))
            }
            snapshot.isRinging -> {
                cardResultStatus.text = "STATUS         : 📞 CALL IN PROGRESS"
                cardResultStatus.setTextColor(Color.parseColor("#FFC107"))
            }
            else -> {
                cardResultStatus.text = "STATUS         : IDLE"
                cardResultStatus.setTextColor(Color.GRAY)
            }
        }

        cardCallSource.text = "Call Source    : ${snapshot.callSource.displayName}"
        cardDirection.text = "Direction      : ${snapshot.callDirection ?: "-"}"
        cardPhoneNumber.text = "Number         : ${snapshot.phoneNumber ?: "Unknown"}"
        cardContactName.text = "Phone/Contacts : ${snapshot.contactName ?: "Unknown"}"
        cardTruecallerName.text = "Truecaller     : ${snapshot.truecallerName ?: "Waiting..."}"
        cardAnnouncementName.text = "Announcement   : ${snapshot.announcementName ?: "Waiting..."}"
        cardSource.text = "Source         : ${snapshot.announcementSource ?: "-"}"
        cardTtsText.text = "TTS Text       : ${snapshot.ttsText ?: "-"}"
    }

    private fun simulateCall(mismatch: Boolean) {
        val testNumber = "09805305407"
        val tcName = "Pavinder Jasrotia"

        CallDebugTracker.reset()
        CallDebugTracker.onCallDetected(testNumber, "PHONE_STATE")
        CallDebugTracker.onContactLookupResult(testNumber, null)
        CallDebugTracker.onTruecallerResult(testNumber, tcName)

        if (mismatch) {
            // Replicates the reported mismatch bug: Truecaller is Pavinder Jasrotia, but announcement is Aman
            CallDebugTracker.onIdentitySelected(testNumber, "Aman", "TRUECALLER")
            CallDebugTracker.onAnnouncementPrepared("Aman", "Call from Aman", "TRUECALLER")
            CallDebugTracker.onTtsStarted("Call from Aman")
        } else {
            CallDebugTracker.onIdentitySelected(testNumber, tcName, "TRUECALLER")
            CallDebugTracker.onAnnouncementPrepared(tcName, "Incoming call from $tcName", "TRUECALLER")
            CallDebugTracker.onTtsStarted("Incoming call from $tcName")
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
        // 1. Direct package lookup (works with <queries> declaration on Android 11+)
        val direct = runCatching {
            packageManager.getPackageInfo(TruecallerAccessibilityService.TRUECALLER_PACKAGE, 0)
        }.isSuccess
        if (direct) return true

        // 2. Launch intent check
        val launchIntent = packageManager.getLaunchIntentForPackage(
            TruecallerAccessibilityService.TRUECALLER_PACKAGE
        )
        if (launchIntent != null) return true

        // 3. Fallback: if accessibility service has been connected or received events
        return TruecallerAccessibilityService.isServiceConnected
    }

    // -------------------------------------------------- speech test

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

                // Auto-restart for benign errors
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

                // Auto-restart for continuous listening
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
        val normalized = raw.lowercase().replace("[^a-z ]".toRegex(), "").trim()
        DebugLogStore.log("SPEECH", "NORMALIZED = \"$normalized\"")

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

    companion object {
        private const val TAG = "DebugConsole"
    }
}
