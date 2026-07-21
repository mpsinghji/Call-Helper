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
import java.util.Locale

/**
 * Continuous voice-command listener with pause/resume.
 *
 * The recognizer must NOT run while the TTS announcement is playing: they
 * fight over audio focus and the recognizer dies instantly and restarts in
 * a loop (visible as the mic indicator flickering on/off). The service
 * pauses listening for the duration of each announcement and resumes the
 * moment it ends.
 */
class VoiceCommandManager(
    private val context: Context,
    @Suppress("unused") private val settings: SettingsManager,
    private val onCommand: (VoiceCommand) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    private var lastCommandAt = 0L
    private var lastCommand: VoiceCommand? = null

    private val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 400L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 400L)
    }

    /** Start the continuous listener (idempotent). */
    fun startContinuous() {
        mainHandler.post {
            if (running) return@post
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
            running = true
            paused = false
            val rec = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = rec
            rec.setRecognitionListener(listener)
            beginListening()
            Log.i(TAG, "Continuous listening started")
        }
    }

    /** Temporarily stop listening (e.g. while the announcement plays). */
    fun pause() {
        mainHandler.post {
            if (!running || paused) return@post
            paused = true
            mainHandler.removeCallbacksAndMessages(null)
            runCatching { recognizer?.cancel() }
            Log.d(TAG, "Listening paused")
        }
    }

    /** Resume listening after a pause. */
    fun resume() {
        mainHandler.post {
            if (!running || !paused) return@post
            paused = false
            beginListening()
            Log.d(TAG, "Listening resumed")
        }
    }

    private fun beginListening() {
        if (!running || paused) return
        runCatching { recognizer?.startListening(recognizerIntent) }
            .onFailure { restartAfter(RETRY_SLOW_MS) }
    }

    private fun restartAfter(delayMs: Long) {
        if (!running || paused) return
        mainHandler.postDelayed({ beginListening() }, delayMs)
    }

    private fun handlePhrases(phrases: List<String>): Boolean {
        val command = phrases.firstNotNullOfOrNull { VoiceCommand.fromPhrase(it) }
            ?: return false
        val now = SystemClock.elapsedRealtime()
        // Debounce: partial + final results often both match the same phrase.
        if (command == lastCommand && now - lastCommandAt < COMMAND_DEBOUNCE_MS) return true
        lastCommand = command
        lastCommandAt = now
        Log.i(TAG, "Matched $command from $phrases")
        onCommand(command)
        return true
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val phrases = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
            handlePhrases(phrases)
            restartAfter(RETRY_FAST_MS)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val phrases = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
            if (handlePhrases(phrases)) {
                runCatching { recognizer?.cancel() }
                restartAfter(RETRY_FAST_MS)
            }
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    restartAfter(RETRY_FAST_MS)

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    runCatching { recognizer?.cancel() }
                    restartAfter(RETRY_SLOW_MS)
                }

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    Log.e(TAG, "Mic permission lost — stopping")
                    stopListening()
                }

                else -> restartAfter(RETRY_SLOW_MS)
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** Stop and release the recognizer. */
    fun stopListening() {
        running = false
        paused = false
        mainHandler.post {
            mainHandler.removeCallbacksAndMessages(null)
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
        private const val RETRY_FAST_MS = 100L
        private const val RETRY_SLOW_MS = 600L
        private const val COMMAND_DEBOUNCE_MS = 2000L
    }
}