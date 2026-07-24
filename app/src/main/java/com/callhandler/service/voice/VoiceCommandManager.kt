package com.callhandler.service.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.callhandler.service.settings.SettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** Observable state of the voice listener for the overlay. */
enum class VoiceListenerState {
    /** Recognizer is actively listening for voice commands. */
    LISTENING,
    /** Recognizer exists but is temporarily paused (e.g. during TTS). */
    PAUSED,
    /** Recognizer is not running (disabled or stopped). */
    OFF
}

/**
 * Continuous voice-command listener.
 *
 * Key design: restarts the recognizer as fast as possible (≤ 50 ms)
 * after each result or benign error (NO_MATCH / SPEECH_TIMEOUT) so the
 * user perceives a single uninterrupted listening session.
 *
 * Partial results are acted on immediately for responsive command
 * execution, but the recognizer is NOT cancelled mid-session — it
 * finishes naturally. The debounce window prevents double-actions.
 */
class VoiceCommandManager(
    private val context: Context,
    @Suppress("unused") private val settings: SettingsManager,
    private val onCommand: (VoiceCommand, List<String>) -> Unit,
    private val onUnrecognizedPhrases: (List<String>) -> Unit
) {
    private enum class State { IDLE, STARTING, LISTENING, PAUSED, STOPPED }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var state = State.STOPPED

    private val _listenerState = MutableStateFlow(VoiceListenerState.OFF)
    /** Observable listener state for the overlay UI. */
    val listenerState: StateFlow<VoiceListenerState> = _listenerState

    private val _lastHeardText = MutableStateFlow("")
    /** What the speech recognizer last heard — for overlay debug display. */
    val lastHeardText: StateFlow<String> = _lastHeardText

    private var sessionId = 0
    private var consecutiveErrors = 0
    private val restartRunnable = Runnable { beginListening() }

    private var lastCommandAt = 0L
    private var lastCommand: VoiceCommand? = null

    private val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // Prefer offline recognition for lower latency
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        // Shorter silence timeouts → faster cycling → catches commands sooner
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5000L)
    }

    /** Start the continuous listener (idempotent). */
    fun startContinuous() {
        mainHandler.post {
            if (state != State.STOPPED) {
                Log.d(TAG, "Already running ($state)")
                return@post
            }

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "RECORD_AUDIO not granted — voice commands disabled")
                return@post
            }
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.w(TAG, "No speech recognizer on this device")
                return@post
            }

            Log.i(TAG, "Starting continuous listening...")
            state = State.IDLE
            _listenerState.value = VoiceListenerState.OFF
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            beginListening()
        }
    }

    /**
     * Pause listening. Cancels the current session to avoid hearing
     * the app's own TTS announcement.
     */
    fun pause() {
        mainHandler.post {
            if (state == State.STOPPED || state == State.PAUSED) return@post
            Log.d(TAG, "PAUSE requested")
            state = State.PAUSED
            _listenerState.value = VoiceListenerState.PAUSED
            mainHandler.removeCallbacks(restartRunnable)
            runCatching { recognizer?.cancel() }
        }
    }

    /** Resume listening after a pause. */
    fun resume(viaBluetooth: Boolean = false) {
        mainHandler.post {
            if (state != State.PAUSED) return@post
            val delay = if (viaBluetooth) RESUME_DELAY_BT_MS else RESUME_DELAY_SPEAKER_MS
            Log.d(TAG, "RESUME (viaBluetooth=$viaBluetooth, delay=${delay}ms)")
            state = State.IDLE
            restartAfter(delay)
        }
    }

    private fun beginListening() {
        sessionId++
        val sid = sessionId

        if (state != State.IDLE) {
            Log.d(TAG, "[$sid] beginListening ignored: state=$state")
            return
        }

        Log.d(TAG, "[$sid] -> STARTING recognizer")
        state = State.STARTING
        _lastHeardText.value = "🎤 Starting..."

        runCatching {
            recognizer?.setRecognitionListener(createListener(sid))
            recognizer?.startListening(recognizerIntent)
        }.onFailure { e ->
            Log.e(TAG, "[$sid] startListening failed: ${e.message}")
            _lastHeardText.value = "❌ Mic error: ${e.message?.take(40)}"
            state = State.IDLE
            _listenerState.value = VoiceListenerState.OFF
            if (recreateRecognizer()) {
                restartAfter(RETRY_SLOW_MS)
            }
        }
    }

    private fun recreateRecognizer(): Boolean {
        recognizer?.let { rec ->
            runCatching { rec.cancel() }
            runCatching { rec.destroy() }
        }
        if (state == State.STOPPED) return false
        return runCatching {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            true
        }.getOrElse { e ->
            Log.e(TAG, "Failed to recreate recognizer", e)
            recognizer = null
            false
        }
    }

    private fun restartAfter(delayMs: Long) {
        if (state == State.STOPPED || state == State.PAUSED) return
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.postDelayed(restartRunnable, delayMs)
    }

    private fun handlePhrases(phrases: List<String>): Boolean {
        if (state == State.PAUSED || state == State.STOPPED) return false
        if (phrases.isEmpty()) return false

        val command = phrases.firstNotNullOfOrNull { VoiceCommand.fromPhrase(it) }
            ?: run {
                onUnrecognizedPhrases(phrases)
                return false
            }

        val now = SystemClock.elapsedRealtime()
        if (command == lastCommand && now - lastCommandAt < COMMAND_DEBOUNCE_MS) return true

        lastCommand = command
        lastCommandAt = now
        Log.i(TAG, "MATCHED: $command (from $phrases)")

        runCatching { onCommand(command, phrases) }
            .onFailure { Log.e(TAG, "Command callback failed", it) }
        return true
    }

    private fun createListener(sid: Int) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (state != State.STARTING) return
            state = State.LISTENING
            _listenerState.value = VoiceListenerState.LISTENING
            _lastHeardText.value = "🎤 Listening..."
            consecutiveErrors = 0
            Log.d(TAG, "[$sid] READY — listening")
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            if (state == State.STOPPED) return

            val name = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
                SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
                SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
                SpeechRecognizer.ERROR_SERVER -> "SERVER"
                SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
                else -> "UNKNOWN($error)"
            }

            if (state == State.PAUSED) return
            if (state == State.STARTING || state == State.LISTENING) {
                state = State.IDLE
                _listenerState.value = VoiceListenerState.OFF
            }

            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    Log.e(TAG, "[$sid] $name — stopping (FGS mic type likely missing)")
                    _lastHeardText.value = "⚠ Mic permission blocked"
                    stopListening()
                }
                // Benign "nothing heard" — restart immediately
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    consecutiveErrors++
                    _lastHeardText.value = "🎤 (silence — restarting)"
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        Log.w(TAG, "[$sid] $name ($consecutiveErrors consecutive) — recreating")
                        consecutiveErrors = 0
                        if (recreateRecognizer()) restartAfter(RETRY_SLOW_MS)
                    } else {
                        restartAfter(RESTART_INSTANT_MS)
                    }
                }
                // Critical — need a clean slate
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT -> {
                    Log.w(TAG, "[$sid] $name — recreating recognizer")
                    _lastHeardText.value = "⚠ $name — retrying"
                    consecutiveErrors = 0
                    if (recreateRecognizer()) restartAfter(RETRY_SLOW_MS)
                }
                SpeechRecognizer.ERROR_AUDIO -> {
                    Log.e(TAG, "[$sid] $name — mic may be blocked by FGS type restriction")
                    _lastHeardText.value = "⚠ Audio error — mic blocked?"
                    consecutiveErrors = 0
                    if (recreateRecognizer()) restartAfter(RETRY_SLOW_MS)
                }
                else -> {
                    Log.w(TAG, "[$sid] $name")
                    _lastHeardText.value = "⚠ $name"
                    consecutiveErrors = 0
                    restartAfter(RETRY_FAST_MS)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            if (state != State.LISTENING) return

            val phrases = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            Log.d(TAG, "[$sid] RESULTS: $phrases")

            if (phrases.isNotEmpty()) {
                _lastHeardText.value = "✅ " + phrases.first()
            }
            handlePhrases(phrases)

            // Restart immediately for continuous listening
            if (state != State.STOPPED && state != State.PAUSED) {
                state = State.IDLE
                _listenerState.value = VoiceListenerState.OFF
                restartAfter(RESTART_INSTANT_MS)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (state != State.LISTENING) return

            val phrases = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

            if (phrases.isNotEmpty()) {
                Log.d(TAG, "[$sid] PARTIAL: $phrases")
                _lastHeardText.value = "… " + phrases.first()
                // Act on partial match immediately; do NOT cancel the
                // recognizer — let it finish naturally. Debounce prevents
                // the same command from firing again in onResults.
                handlePhrases(phrases)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** Stop and release the recognizer. */
    fun stopListening() {
        mainHandler.post {
            Log.i(TAG, "Stopping VoiceCommandManager")
            state = State.STOPPED
            _listenerState.value = VoiceListenerState.OFF
            mainHandler.removeCallbacks(restartRunnable)
            recognizer?.let { rec ->
                runCatching { rec.cancel() }
                runCatching { rec.destroy() }
            }
            recognizer = null
        }
    }

    fun destroy() = stopListening()

    companion object {
        private const val TAG = "VoiceCommandMgr"
        private const val RESTART_INSTANT_MS = 50L    // near-zero gap between sessions
        private const val RETRY_FAST_MS = 300L         // after minor server/network errors
        private const val RETRY_SLOW_MS = 1200L        // after recreating recognizer
        private const val RESUME_DELAY_BT_MS = 500L    // after TTS via Bluetooth
        private const val RESUME_DELAY_SPEAKER_MS = 200L
        private const val COMMAND_DEBOUNCE_MS = 1500L  // reduced from 2000 for faster response
        private const val MAX_CONSECUTIVE_ERRORS = 5
    }
}
