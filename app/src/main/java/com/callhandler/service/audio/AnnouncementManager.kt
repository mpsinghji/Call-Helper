package com.callhandler.service.audio

import android.content.Context
import android.media.AudioAttributes
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
 * Uses USAGE_VOICE_COMMUNICATION so the TTS output routes through
 * the Bluetooth phone-call audio channel (SCO) and never to the
 * loudspeaker.
 */
class AnnouncementManager(
    context: Context,
    private val settings: SettingsManager
) {
    private val appContext = context.applicationContext
    private val ttsReady = CompletableDeferred<Boolean>()
    private val utteranceSeq = AtomicInteger()

    /** Audio attributes that route through the SCO / phone-call channel. */
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
     * Caller must have connected SCO before calling this.
     * Always uses USAGE_VOICE_COMMUNICATION so output goes through the
     * phone-call BT channel, never the loudspeaker.
     */
    suspend fun announce(text: String) {
        if (!ttsReady.await()) {
            Log.w(TAG, "TTS not initialized; skipping announcement")
            return
        }

        tts.setAudioAttributes(scoAttributes)
        tts.setSpeechRate(settings.speechRatePct / 100f)

        withTimeoutOrNull(MAX_UTTERANCE_MS) {
            speakAndAwait(text)
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

            if (result != TextToSpeech.SUCCESS && cont.isActive) {
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
