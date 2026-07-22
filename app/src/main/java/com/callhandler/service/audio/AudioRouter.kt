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
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Bluetooth SCO management and call-audio helpers.
 *
 * Handles:
 * - SCO connection for mic (voice commands) and TTS (announcements)
 * - BT earphone volume ducking during announcements (speaker untouched)
 * - Ringer silencing and speakerphone toggling via voice commands
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
    private var savedAudioMode = -1
    private var savedRingVolume = -1

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
        
        // Save current audio mode
        if (savedAudioMode < 0) {
            savedAudioMode = audioManager.mode
            Log.d(TAG, "Saved audio mode: $savedAudioMode")
        }
        
        // Try MODE_IN_CALL first (standard call mode that routes to BT)
        // This should allow both ringtone on speaker and TTS on BT
        audioManager.mode = AudioManager.MODE_IN_CALL
        Log.d(TAG, "Set audio mode to MODE_IN_CALL for BT announcement")
        
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            connectViaCommunicationDevice()
        } else {
            connectViaLegacySco()
        }
        scoConnected = ok
        
        if (!ok && savedAudioMode >= 0) {
            // Failed to connect, restore audio mode
            audioManager.mode = savedAudioMode
            savedAudioMode = -1
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
        
        // Restore original audio mode
        if (savedAudioMode >= 0) {
            Log.d(TAG, "Restoring audio mode to: $savedAudioMode")
            audioManager.mode = savedAudioMode
            savedAudioMode = -1
        }
    }

    // ------------------------------------------ BT earphone volume for announcement

    /**
     * Temporarily reduce ringtone volume during announcement so the caller ID is audible.
     * Reduces STREAM_RING to 30% of current volume.
     * Call restoreRingtoneVolume() after announcement to restore.
     */
    fun reduceRingtoneForAnnouncement() {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        if (savedRingVolume < 0 && current > 0) {
            savedRingVolume = current
            val reduced = (current * 0.3f).roundToInt().coerceAtLeast(1)
            Log.d(TAG, "Reducing RING volume temporarily: $current -> $reduced (30%) for announcement")
            setStreamSafely(AudioManager.STREAM_RING, reduced)
        }
    }

    /**
     * Restore ringtone volume after announcement.
     */
    fun restoreRingtoneVolume() {
        if (savedRingVolume >= 0) {
            Log.d(TAG, "Restoring RING volume to $savedRingVolume")
            setStreamSafely(AudioManager.STREAM_RING, savedRingVolume)
            savedRingVolume = -1
        }
    }

    /**
     * Set VOICE_CALL stream to maximum volume for announcements.
     * Saves the current volume for later restoration.
     * 
     * This OVERRIDES the user's configured percentage and always uses 200% (max volume).
     * This ensures announcements are always loud and clearly audible in Bluetooth earphones.
     * 
     * This only affects the BT earphone audio (SCO channel).
     */
    fun setAnnouncementVolume(volumePct: Int) {
        // Save VOICE_CALL volume
        val current = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        if (savedVoiceCallVolume < 0) {
            savedVoiceCallVolume = current
            Log.d(TAG, "Saved current VOICE_CALL volume: $current")
        }
        
        // Log RING volume to verify it's not being changed
        val ringVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        val ringMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
        Log.d(TAG, "RING volume: $ringVolume / $ringMax (speaker ringtone)")
        
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        
        // OVERRIDE: Always use maximum volume (200% = max) for announcements
        val target = max
        
        Log.d(TAG, "Setting announcement volume: $target / $max (MAXIMUM - 200% override) [original: $current]")
        Log.d(TAG, "User requested $volumePct% but forcing to 200% (max) for clarity")
        
        setStreamSafely(AudioManager.STREAM_VOICE_CALL, target)
        
        // Verify the volume was set
        val actualSet = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        if (actualSet != target) {
            Log.w(TAG, "Volume mismatch! Requested $target but got $actualSet")
        } else {
            Log.d(TAG, "✓ Volume set successfully to MAXIMUM ($actualSet)")
        }
    }

    /**
     * Restore the VOICE_CALL volume to its level before ducking.
     */
    fun restoreBluetoothVolume() {
        if (savedVoiceCallVolume >= 0) {
            Log.d(TAG, "Restoring VOICE_CALL volume to $savedVoiceCallVolume")
            setStreamSafely(AudioManager.STREAM_VOICE_CALL, savedVoiceCallVolume)
            savedVoiceCallVolume = -1
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
        restoreBluetoothVolume()
        restoreRingtoneVolume()
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