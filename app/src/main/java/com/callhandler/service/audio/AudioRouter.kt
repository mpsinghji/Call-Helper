package com.callhandler.service.audio

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Bluetooth SCO management and call-audio helpers.
 *
 * Handles:
 * - SCO connection for mic (voice commands) and TTS (announcements)
 * - Audio mode switching so TTS actually routes through BT SCO
 * - VOICE_CALL stream volume control for announcement loudness
 * - Ringer silencing and speakerphone toggling
 *
 * STREAM_RING (speaker ringtone) is NEVER touched by this class.
 */
class AudioRouter(private val context: Context) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE)
                as? BluetoothManager)?.adapter ?: BluetoothAdapter.getDefaultAdapter()
    }

    private var scoConnected = false
    private var savedVoiceCallVolume = -1
    private var savedMediaVolume = -1
    private var savedAudioMode = -1
    private var audioFocusRequest: AudioFocusRequest? = null

    fun isBluetoothAudioConnected(): Boolean {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        val hasConnectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else true

        if (hasConnectPermission) {
            Log.d(TAG, "HEADSET state = ${
                bluetoothAdapter?.getProfileConnectionState(BluetoothProfile.HEADSET)
            }")
        }

        val viaAudioManager = devices.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        if (viaAudioManager) return true
        return isHeadsetProfileConnected()
    }

    private fun isHeadsetProfileConnected(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) return false

        val a = bluetoothAdapter ?: return false
        return runCatching {
            a.getProfileConnectionState(BluetoothProfile.HEADSET) ==
                    BluetoothHeadset.STATE_CONNECTED ||
                    a.getProfileConnectionState(BluetoothProfile.A2DP) ==
                    BluetoothProfile.STATE_CONNECTED
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------ SCO channel

    suspend fun connectBluetoothAudio(): Boolean {
        if (scoConnected) return true
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            connectViaCommunicationDevice()
        } else {
            connectViaLegacySco()
        }
        scoConnected = ok
        if (ok) {
            // Minimal delay for audio subsystem routing (100ms for fast announcement start)
            delay(100)
        }
        return ok
    }

    private fun connectViaCommunicationDevice(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val device = audioManager.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        } ?: return false
        Log.d(TAG, "Communication device selected: $device")
        return runCatching { audioManager.setCommunicationDevice(device) }
            .getOrDefault(false)
    }

    private suspend fun connectViaLegacySco(): Boolean {
        if (audioManager.isBluetoothScoOn) return true
        Log.d(TAG, "Connecting Bluetooth SCO (legacy)...")
        return withTimeoutOrNull(SCO_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        val state = i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                        if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                            Log.d(TAG, "Bluetooth SCO connected")
                            runCatching { context.unregisterReceiver(this) }
                            if (cont.isActive) cont.resume(true)
                        }
                    }
                }
                context.registerReceiver(
                    receiver,
                    IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                )
                cont.invokeOnCancellation {
                    runCatching { context.unregisterReceiver(receiver) }
                }
                runCatching {
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                }.onFailure {
                    runCatching { context.unregisterReceiver(receiver) }
                    if (cont.isActive) cont.resume(false)
                }
            }
        } ?: false
    }

    fun disconnectBluetoothAudio() {
        if (!scoConnected) return
        Log.d(TAG, "Bluetooth SCO disconnected")
        scoConnected = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        } else {
            runCatching {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }
        }
    }

    // ------------------------------------------ Announcement audio preparation

    /**
     * Prepare the audio subsystem for a BT announcement:
     *
     * 1. Request audio focus (GAIN_TRANSIENT) to prevent other apps from
     *    being ducked by the system while MODE_IN_COMMUNICATION is active.
     * 2. Save the current audio mode, VOICE_CALL volume, and media volume.
     * 3. Switch to MODE_IN_COMMUNICATION — routes STREAM_VOICE_CALL through
     *    BT SCO. Without it, TTS may go to the loudspeaker.
     * 4. Set STREAM_VOICE_CALL to maximum.
     * 5. If [maxVolume] is true, also set STREAM_MUSIC to maximum.
     *
     * STREAM_RING is NEVER touched — the speaker ringtone stays as-is.
     *
     * Must be paired with [restoreAfterAnnouncement].
     */
    fun prepareForAnnouncement(volumePct: Int, maxVolume: Boolean = false) {
        // 1. Request audio focus to prevent ducking
        requestAnnouncementFocus()

        // 2. Save current state
        if (savedAudioMode < 0) {
            savedAudioMode = audioManager.mode
        }
        if (savedVoiceCallVolume < 0) {
            savedVoiceCallVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        }
        if (maxVolume && savedMediaVolume < 0) {
            savedMediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }

        // 3. Activate SCO routing — this is the key to making TTS go through BT
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }
        Log.d(TAG, "Audio mode -> MODE_IN_COMMUNICATION (was $savedAudioMode)")

        // 4. Set VOICE_CALL stream to MAXIMUM volume for loudest output
        val vcMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        Log.d(TAG, "VOICE_CALL volume -> MAX ($vcMax)")
        setStreamSafely(AudioManager.STREAM_VOICE_CALL, vcMax)

        // 5. Optionally set MUSIC stream to max (works on wired, BT A2DP, BT SCO)
        if (maxVolume) {
            val musicMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            Log.d(TAG, "MUSIC volume -> MAX ($musicMax) [was $savedMediaVolume, maxVolume=true]")
            setStreamSafely(AudioManager.STREAM_MUSIC, musicMax)
        }
    }

    /**
     * Restore the audio mode, VOICE_CALL volume, and media volume to
     * pre-announcement values. Safe to call even if [prepareForAnnouncement]
     * wasn't called. Safe to call repeatedly.
     */
    fun restoreAfterAnnouncement() {
        if (savedAudioMode >= 0) {
            Log.d(TAG, "Audio mode -> $savedAudioMode (restoring)")
            runCatching { audioManager.mode = savedAudioMode }
            savedAudioMode = -1
        }
        if (savedVoiceCallVolume >= 0) {
            Log.d(TAG, "VOICE_CALL volume -> $savedVoiceCallVolume (restoring)")
            setStreamSafely(AudioManager.STREAM_VOICE_CALL, savedVoiceCallVolume)
            savedVoiceCallVolume = -1
        }
        if (savedMediaVolume >= 0) {
            Log.d(TAG, "MUSIC volume -> $savedMediaVolume (restoring)")
            setStreamSafely(AudioManager.STREAM_MUSIC, savedMediaVolume)
            savedMediaVolume = -1
        }
        abandonAnnouncementFocus()
    }

    private fun requestAnnouncementFocus() {
        if (audioFocusRequest != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .build()
        val result = audioManager.requestAudioFocus(request)
        audioFocusRequest = request
        Log.d(TAG, "Audio focus requested: result=$result (GRANTED=1)")
    }

    private fun abandonAnnouncementFocus() {
        audioFocusRequest?.let { req ->
            runCatching { audioManager.abandonAudioFocusRequest(req) }
            audioFocusRequest = null
            Log.d(TAG, "Audio focus abandoned")
        }
    }

    // -------------------------------------------------------- voice actions

    /** Silence the ringer — same as pressing the power button during a ring. */
    fun silenceRinger() {
        runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
        }
    }

    fun requestSpeakerphoneOnAnswer() {
        runCatching {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        }
    }

    /** Restore every audio setting we touched. Safe to call repeatedly. */
    fun restoreAll() {
        restoreAfterAnnouncement()
        disconnectBluetoothAudio()
    }

    private fun setStreamSafely(stream: Int, volume: Int) {
        runCatching {
            audioManager.setStreamVolume(stream, volume, 0)
        }
    }

    companion object {
        private const val TAG = "AudioRouter"
        private const val SCO_TIMEOUT_MS = 3000L
    }
}