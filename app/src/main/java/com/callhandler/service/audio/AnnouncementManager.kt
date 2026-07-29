package com.callhandler.service.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.callhandler.service.settings.SettingsManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
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

    /** Audio attributes for the SCO / phone-call channel. */
    private val scoAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private lateinit var tts: TextToSpeech

    init {
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
    }

    /**
     * Announce [text] through Bluetooth SCO.
     *
     * Prerequisites (caller must ensure):
     * - SCO is connected via AudioRouter.connectBluetoothAudio()
     * - AudioRouter.prepareForAnnouncement() was called (sets MODE_IN_COMMUNICATION + max volume)
     * 
     * This method applies the user's volume percentage (100-200%) as a TTS audio boost:
     * - 100% = normal speech volume (1.0x)
     * - 150% = 1.5x boost
     * - 200% = 2.0x boost (maximum)
     * 
     * The system VOICE_CALL stream is already at max, so this TTS boost
     * creates ACTUALLY LOUDER output beyond the normal maximum.
     */
    suspend fun announce(text: String) {
        if (!ttsReady.await()) {
            Log.w(TAG, "TTS not initialized; skipping announcement")
            return
        }

        tts.setAudioAttributes(scoAttributes)
        tts.setSpeechRate(settings.speechRatePct / 100f)

        // Apply volume boost: 100% = 1.0, 200% = 2.0
        // This MULTIPLIES the audio amplitude, making it actually louder
        val volumeBoost = (settings.announcementVolumePct / 100f).coerceIn(1.0f, 2.0f)
        
        Log.d(TAG, "Speaking: '$text' at ${settings.announcementVolumePct}% (TTS boost: ${volumeBoost}x)")
        withTimeoutOrNull(MAX_UTTERANCE_MS) {
            speakAndAwait(text, volumeBoost)
        }
    }

    private suspend fun speakAndAwait(text: String, volumeBoost: Float) =
        suspendCancellableCoroutine<Unit> { cont ->
            val id = "chs-" + utteranceSeq.incrementAndGet()

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "TTS started: $utteranceId at ${volumeBoost}x volume")
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "TTS done: $utteranceId")
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

            // Create enhanced params with volume boost + stream routing
            @Suppress("DEPRECATION")
            val enhancedParams = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_VOICE_CALL)
                // KEY_PARAM_VOLUME: 0.0 = silent, 1.0 = normal, 2.0 = double amplitude (LOUDER)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volumeBoost)
            }

            // Pass enhanced params Bundle with STREAM_VOICE_CALL + volume boost
            val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, enhancedParams, id)
            Log.d(TAG, "tts.speak() result = $result (SUCCESS=0)")

            if (result != TextToSpeech.SUCCESS && cont.isActive) {
                Log.w(TAG, "tts.speak() failed immediately with result=$result")
                cont.resume(Unit)
            }

            cont.invokeOnCancellation { tts.stop() }
        }

    fun stopSpeaking() {
        runCatching { tts.stop() }
    }

    fun shutdown() {
        stopSpeaking()
        runCatching { tts.shutdown() }
    }

    companion object {
        private const val TAG = "AnnouncementMgr"
        private const val MAX_UTTERANCE_MS = 10_000L
    }
}
