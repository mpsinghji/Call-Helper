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
    /** Recognizer exists but is temporarily paused. */
    PAUSED,
    /** Recognizer is not running (disabled or stopped). */
    OFF
}

/**
 * Continuous voice-command listener with state-machine management.
 *
 * This manager coordinates with the system SpeechRecognizer to provide
 * reliable voice command detection during incoming calls. It handles
 * race conditions with audio focus and Bluetooth SCO by using
 * a state machine and appropriate delays.
 *
 * Exposes [listenerState] as a StateFlow so the overlay can observe
 * whether the recognizer is LISTENING, PAUSED, or OFF.
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

    private var sessionId = 0
    private var consecutiveNoMatch = 0
    private val restartRunnable = Runnable { beginListening() }

    private var lastCommandAt = 0L
    private var lastCommand: VoiceCommand? = null

    private val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // Increased silence timeouts to be more forgiving during ringing
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
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
     * Pause processing results and prevent new listening sessions.
     * Explicitly cancels the current session.
     */
    fun pause() {
        mainHandler.post {
            if (state == State.STOPPED || state == State.PAUSED) return@post
            val hash = System.identityHashCode(recognizer)
            Log.d(TAG, "PAUSE requested hash=$hash")
            state = State.PAUSED
            _listenerState.value = VoiceListenerState.PAUSED
            mainHandler.removeCallbacks(restartRunnable)
            runCatching { recognizer?.cancel() }
        }
    }

    /** Resume listening after a pause, with a delay to let audio settle. */
    fun resume(viaBluetooth: Boolean = false) {
        mainHandler.post {
            if (state != State.PAUSED) return@post
            val delay = if (viaBluetooth) RESUME_DELAY_BT_MS else RESUME_DELAY_SPEAKER_MS
            Log.d(TAG, "RESUME requested (viaBluetooth=$viaBluetooth). Waiting ${delay}ms...")
            state = State.IDLE
            restartAfter(delay)
        }
    }

    private fun beginListening() {
        sessionId++
        val sid = sessionId
        val hash = System.identityHashCode(recognizer)

        Log.d(TAG, "[$sid] beginListening() state=$state recognizer=$hash")

        if (state != State.IDLE) {
            Log.d(TAG, "[$sid] beginListening ignored: state is $state")
            return
        }

        Log.d(TAG, "[$sid] -> STARTING recognizer")
        state = State.STARTING

        runCatching {
            recognizer?.setRecognitionListener(createListener(sid, hash))
            recognizer?.startListening(recognizerIntent)
            Log.d(TAG, "[$sid] startListening() returned")
        }.onFailure { e ->
            Log.e(TAG, "[$sid] startListening failed: ${e.message}")
            state = State.IDLE
            _listenerState.value = VoiceListenerState.OFF
            if (recreateRecognizer()) {
                restartAfter(RETRY_SLOW_MS)
            }
        }
    }

    private fun recreateRecognizer(): Boolean {
        Log.d(TAG, "Recreating SpeechRecognizer instance...")

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
        Log.d(TAG, "Recognizer phrases = $phrases")
        if (phrases.isEmpty()) {
            Log.d(TAG, "Recognizer returned no phrases")
            return false
        }

        val command = phrases.firstNotNullOfOrNull { VoiceCommand.fromPhrase(it) }

        if (command == null) {
            onUnrecognizedPhrases(phrases)
            return false
        }

        val now = SystemClock.elapsedRealtime()
        if (command == lastCommand && now - lastCommandAt < COMMAND_DEBOUNCE_MS) return true

        lastCommand = command
        lastCommandAt = now
        Log.i(TAG, "MATCHED command: $command (from $phrases)")

        runCatching {
            onCommand(command, phrases)
        }.onFailure { e ->
            Log.e(TAG, "Command callback failed", e)
        }

        return true
    }

    private fun createListener(sid: Int, hash: Int) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "[$sid] READY state=$state hash=$hash")
            if (state != State.STARTING) return
            state = State.LISTENING
            _listenerState.value = VoiceListenerState.LISTENING
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "[$sid] BEGIN state=$state hash=$hash")
            consecutiveNoMatch = 0
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Intentionally not logging RMS every frame to reduce log spam
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "[$sid] END state=$state hash=$hash")
        }

        override fun onError(error: Int) {
            val errorMsg = when (error) {
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
                SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
                SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
                SpeechRecognizer.ERROR_SERVER -> "SERVER"
                SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
                SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
                else -> "UNKNOWN ($error)"
            }

            Log.w(TAG, "[$sid] ERROR: $errorMsg state=$state")

            if (state == State.STOPPED) return

            if (state == State.STARTING || state == State.LISTENING) {
                state = State.IDLE
                _listenerState.value = VoiceListenerState.OFF
            }

            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    Log.e(TAG, "[$sid] Insufficient permissions, stopping.")
                    stopListening()
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_AUDIO -> {
                    consecutiveNoMatch = 0
                    if (recreateRecognizer()) {
                        restartAfter(RETRY_SLOW_MS)
                    }
                }
                SpeechRecognizer.ERROR_NO_MATCH -> {
                    consecutiveNoMatch++
                    if (consecutiveNoMatch >= 3) {
                        Log.w(TAG, "[$sid] 3 consecutive NO_MATCH, recreating recognizer...")
                        consecutiveNoMatch = 0
                        if (recreateRecognizer()) {
                            restartAfter(RETRY_SLOW_MS)
                        }
                    } else {
                        restartAfter(RETRY_FAST_MS)
                    }
                }
                else -> {
                    consecutiveNoMatch = 0
                    restartAfter(RETRY_FAST_MS)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            if (state != State.LISTENING) {
                Log.d(TAG, "[$sid] onResults ignored: state is $state hash=$hash")
                return
            }

            consecutiveNoMatch = 0
            val phrases = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            Log.d(TAG, "[$sid] RESULTS: $phrases state=$state hash=$hash")

            handlePhrases(phrases)

            if (state != State.STOPPED && state != State.PAUSED) {
                state = State.IDLE
                _listenerState.value = VoiceListenerState.OFF
                restartAfter(RETRY_FAST_MS)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (state != State.LISTENING) {
                return
            }

            consecutiveNoMatch = 0
            val phrases = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            Log.d(TAG, "[$sid] PARTIAL: $phrases state=$state hash=$hash")

            if (handlePhrases(phrases)) {
                Log.d(TAG, "[$sid] Command matched in partial results, restarting...")
                runCatching { recognizer?.cancel() }
                state = State.IDLE
                _listenerState.value = VoiceListenerState.OFF
                restartAfter(RETRY_FAST_MS)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            Log.d(TAG, "[$sid] EVENT: $eventType state=$state hash=$hash")
        }
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
        private const val TAG = "VoiceCommandManager"
        private const val RETRY_FAST_MS = 1000L
        private const val RETRY_SLOW_MS = 1500L
        private const val RESUME_DELAY_BT_MS = 700L
        private const val RESUME_DELAY_SPEAKER_MS = 300L
        private const val COMMAND_DEBOUNCE_MS = 2000L
    }
}
