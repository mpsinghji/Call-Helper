package com.callhandler.service.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.callhandler.service.debug.CallDebugTracker
import com.callhandler.service.settings.SettingsManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Speaks caller announcements through Bluetooth SCO only.
 *
 * Uses USAGE_VOICE_COMMUNICATION audio attributes AND passes
 * STREAM_VOICE_CALL in the speak() Bundle — belt-and-suspenders
 * to ensure TTS routes through the BT phone-call channel on all
 * devices. The caller must have called AudioRouter.prepareForAnnouncement()
 * (which sets MODE_IN_COMMUNICATION) before invoking [announce].
 */
class AnnouncementManager(
    context: Context,
    private val settings: SettingsManager
) {
    private val appContext = context.applicationContext
    private val ttsReady = CompletableDeferred<Boolean>()
    private val utteranceSeq = AtomicInteger()

    @Volatile
    var currentSessionId: Long = 0L
        private set

    private val cancelledSessionIds = ConcurrentHashMap.newKeySet<Long>()

    /** Audio attributes for the SCO / phone-call channel. */
    private val scoAttributes: AudioAttributes by lazy {
        runCatching {
            AudioAttributes.Builder()
                .apply {
                    runCatching { setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION) }
                    runCatching { setContentType(AudioAttributes.CONTENT_TYPE_SPEECH) }
                }
                .build()
        }.getOrElse {
            AudioAttributes.Builder().build()
        }
    }

    private lateinit var tts: TextToSpeech

    init {
        runCatching {
            tts = TextToSpeech(appContext) { status ->
                val ok = status == TextToSpeech.SUCCESS
                if (ok) {
                    val result = tts.setLanguage(Locale.getDefault())
                    if (result == TextToSpeech.LANG_MISSING_DATA ||
                        result == TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        tts.language = Locale.US
                    }
                } else {
                    Log.e(TAG, "TTS init failed (status=$status)")
                }
                ttsReady.complete(ok)
            }
        }.onFailure {
            Log.w(TAG, "Failed to instantiate TextToSpeech: ${it.message}")
            ttsReady.complete(false)
        }
    }

    /**
     * Checks whether an announcement for [sessionId] has been cancelled.
     */
    fun isSessionCancelled(sessionId: Long): Boolean {
        if (sessionId == 0L) return false
        return cancelledSessionIds.contains(sessionId)
    }

    /**
     * Cancels pending and ongoing announcements for [sessionId].
     * If [sessionId] is 0L, cancels the currently active announcement session regardless of ID.
     */
    fun cancelAnnouncement(sessionId: Long = 0L) {
        if (sessionId != 0L) {
            cancelledSessionIds.add(sessionId)
        }
        if (sessionId == 0L || currentSessionId == sessionId) {
            Log.d(TAG, "cancelAnnouncement: stopping TTS for session=$sessionId (currentSessionId=$currentSessionId)")
            stopSpeaking()
        }
    }

    /**
     * Announce [text] through Bluetooth SCO.
     *
     * Prerequisites (caller must ensure):
     * - SCO is connected via AudioRouter.connectBluetoothAudio()
     * - AudioRouter.prepareForAnnouncement() was called (sets MODE_IN_COMMUNICATION + max volume)
     * 
     * Volume is controlled entirely by AudioRouter's stream volume settings.
     * TTS plays at full amplitude (1.0); actual loudness comes from the
     * VOICE_CALL and MUSIC stream levels set by AudioRouter.
     */
    suspend fun announce(
        text: String,
        sessionId: Long = 0L,
        isTerminalOrCancelled: () -> Boolean = { false }
    ) {
        if (sessionId != 0L) {
            currentSessionId = sessionId
        }

        if (isSessionCancelled(sessionId) || isTerminalOrCancelled()) {
            Log.d(TAG, "Announcement skipped before init: session=$sessionId cancelled or terminal")
            return
        }

        if (!ttsReady.await()) {
            Log.w(TAG, "TTS not initialized; skipping announcement")
            return
        }

        if (isSessionCancelled(sessionId) || isTerminalOrCancelled()) {
            Log.d(TAG, "Announcement skipped after init: session=$sessionId cancelled or terminal")
            return
        }

        tts.setAudioAttributes(scoAttributes)
        tts.setSpeechRate(settings.speechRatePct / 100f)

        Log.d(TAG, "Speaking [session=$sessionId]: '$text'")
        withTimeoutOrNull(MAX_UTTERANCE_MS) {
            speakAndAwait(text, sessionId, isTerminalOrCancelled)
        }
    }

    private suspend fun speakAndAwait(
        text: String,
        sessionId: Long,
        isTerminalOrCancelled: () -> Boolean
    ) = suspendCancellableCoroutine<Unit> { cont ->
        if (isSessionCancelled(sessionId) || isTerminalOrCancelled()) {
            Log.d(TAG, "speakAndAwait aborted before tts.speak for session=$sessionId")
            cont.resume(Unit)
            return@suspendCancellableCoroutine
        }

        val id = "chs-" + utteranceSeq.incrementAndGet()

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "TTS started: $utteranceId [session=$sessionId]")
                if (isSessionCancelled(sessionId) || isTerminalOrCancelled()) {
                    Log.d(TAG, "TTS started but session was cancelled; stopping immediately")
                    runCatching { tts.stop() }
                    if (cont.isActive) cont.resume(Unit)
                    return
                }
                CallDebugTracker.onTtsStarted(text)
            }

            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "TTS done: $utteranceId")
                if (utteranceId == id && cont.isActive) cont.resume(Unit)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                Log.d(TAG, "TTS onStop: $utteranceId (interrupted=$interrupted)")
                if (utteranceId == id && cont.isActive) cont.resume(Unit)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.w(TAG, "TTS error (deprecated): $utteranceId")
                if (utteranceId == id && cont.isActive) cont.resume(Unit)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "TTS error $errorCode for $utteranceId")
                if (utteranceId == id && cont.isActive) cont.resume(Unit)
            }
        })

        // Route through STREAM_VOICE_CALL at full amplitude
        @Suppress("DEPRECATION")
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_VOICE_CALL)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
        Log.d(TAG, "tts.speak() result = $result (SUCCESS=0)")

        if (result != TextToSpeech.SUCCESS && cont.isActive) {
            Log.w(TAG, "tts.speak() failed immediately with result=$result")
            cont.resume(Unit)
        }

        cont.invokeOnCancellation {
            runCatching { tts.stop() }
        }
    }

    fun stopSpeaking() {
        runCatching {
            if (::tts.isInitialized) {
                tts.stop()
            }
        }
    }

    fun shutdown() {
        stopSpeaking()
        runCatching {
            if (::tts.isInitialized) {
                tts.shutdown()
            }
        }
    }

    companion object {
        private const val TAG = "AnnouncementMgr"
        private const val MAX_UTTERANCE_MS = 10_000L
    }
}
