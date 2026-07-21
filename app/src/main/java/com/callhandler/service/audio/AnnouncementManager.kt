package com.callhandler.service.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
 * Speaks caller announcements using TextToSpeech.
 *
 * Two output modes:
 *  - viaBluetoothSco = true: speak on the phone-call channel
 *    (USAGE_VOICE_COMMUNICATION) so it reaches Bluetooth earphones even
 *    while ringing suspends the media channel.
 *  - viaBluetoothSco = false: speak as assistant/media audio, which plays
 *    on the loudspeaker.
 */
class AnnouncementManager(
    context: Context,
    private val settings: SettingsManager
) {
    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val ttsReady = CompletableDeferred<Boolean>()
    private val utteranceSeq = AtomicInteger()
    private var focusRequest: AudioFocusRequest? = null

    private val speakerAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

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
     * Speak [text]; suspends until the utterance finishes, errors, or
     * [MAX_UTTERANCE_MS] elapses. Cancellation stops speech.
     */
    suspend fun announce(text: String, viaBluetoothSco: Boolean) {
        if (!ttsReady.await()) {
            Log.w(TAG, "TTS not initialized; skipping announcement")
            return
        }

        val attributes = if (viaBluetoothSco) scoAttributes else speakerAttributes
        tts.setAudioAttributes(attributes)
        tts.setSpeechRate(settings.speechRatePct / 100f)

        requestFocus(attributes)
        try {
            withTimeoutOrNull(MAX_UTTERANCE_MS) { speakAndAwait(text) }
        } finally {
            abandonFocus()
        }
    }

    private suspend fun speakAndAwait(text: String) =
        suspendCancellableCoroutine<Unit> { cont ->
            val id = "chs-" + utteranceSeq.incrementAndGet()

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.w(TAG, "TTS error $errorCode")
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }
            })

            val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            if (result != TextToSpeech.SUCCESS && cont.isActive) cont.resume(Unit)

            cont.invokeOnCancellation { tts.stop() }
        }

    /** Immediately stop any in-flight speech. */
    fun stopSpeaking() {
        runCatching { tts.stop() }
    }

    fun shutdown() {
        stopSpeaking()
        runCatching { tts.shutdown() }
    }

    private fun requestFocus(attributes: AudioAttributes) {
        val request = AudioFocusRequest.Builder(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        )
            .setAudioAttributes(attributes)
            .build()
        focusRequest = request
        runCatching { audioManager.requestAudioFocus(request) }
    }

    private fun abandonFocus() {
        focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }

    companion object {
        private const val TAG = "AnnouncementManager"
        private const val MAX_UTTERANCE_MS = 10_000L
    }
}