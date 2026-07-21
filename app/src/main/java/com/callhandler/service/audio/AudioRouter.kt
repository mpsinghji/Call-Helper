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
import androidx.core.content.ContextCompat
import com.callhandler.service.settings.SettingsManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Route selection, ringtone ducking, and Bluetooth SCO management.
 *
 * Key insight: during RINGING the phone suspends the Bluetooth media
 * channel (A2DP), so TTS played "as media" never reaches the earphones.
 * Announcements over Bluetooth must use the phone-call channel (SCO),
 * which we open just for the announcement and close right after.
 */
class AudioRouter(
    private val context: Context,
    private val settings: SettingsManager
) {
    enum class Route { BLUETOOTH, SPEAKER, NONE }

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var originalRingVolume = -1
    private var originalMusicVolume = -1
    private var originalVoiceCallVolume = -1
    private var ringDucked = false
    private var ringerSilenced = false
    private var scoConnected = false

    fun selectRoute(): Route = when {
        settings.bluetoothEnabled && isBluetoothAudioConnected() -> Route.BLUETOOTH
        settings.speakerEnabled -> Route.SPEAKER
        else -> Route.NONE
    }

    fun isBluetoothAudioConnected(): Boolean {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
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

        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
                as? BluetoothManager)?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        val a = adapter ?: return false
        return runCatching {
            a.getProfileConnectionState(BluetoothProfile.HEADSET) ==
                    BluetoothHeadset.STATE_CONNECTED ||
                    a.getProfileConnectionState(BluetoothProfile.A2DP) ==
                    BluetoothProfile.STATE_CONNECTED
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------ SCO channel

    /**
     * Open the Bluetooth phone-call audio channel so TTS (and the mic for
     * voice commands) go through the earphones. Suspends until connected
     * or [SCO_TIMEOUT_MS] passes. Returns false on failure — caller should
     * then announce on the speaker instead.
     */
    suspend fun connectBluetoothAudio(): Boolean {
        if (scoConnected) return true
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            connectViaCommunicationDevice()
        } else {
            connectViaLegacySco()
        }
        scoConnected = ok
        return ok
    }

    private fun connectViaCommunicationDevice(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val device = audioManager.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        } ?: return false
        return runCatching { audioManager.setCommunicationDevice(device) }
            .getOrDefault(false)
    }

    private suspend fun connectViaLegacySco(): Boolean {
        if (audioManager.isBluetoothScoOn) return true
        return withTimeoutOrNull(SCO_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        val state = i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                        if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
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

    // ------------------------------------------------------ announce window

    /**
     * Duck the ringtone and make sure the stream carrying the TTS is
     * audible: STREAM_VOICE_CALL for Bluetooth SCO, STREAM_MUSIC for
     * speaker. Undone by [endAnnouncementWindow].
     */
    fun beginAnnouncementWindow(route: Route) {
        if (!ringDucked && !ringerSilenced) {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_RING)
            if (current > 0) {
                originalRingVolume = current
                val target = (current * settings.duckLevelPct / 100f).roundToInt()
                // Never fully silence — keep at least one volume step so the
                // ring stays audible under the announcement.
                setStreamSafely(AudioManager.STREAM_RING, max(1, target))
                ringDucked = true
            }
        }

        val stream = if (route == Route.BLUETOOTH) {
            AudioManager.STREAM_VOICE_CALL
        } else {
            AudioManager.STREAM_MUSIC
        }

        val ringVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        val maxRing = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)

        val maxTarget = audioManager.getStreamMaxVolume(stream)
        val cur = audioManager.getStreamVolume(stream)

        val target =
            ((ringVolume.toFloat() / maxRing) * maxTarget).roundToInt()


        if (stream == AudioManager.STREAM_MUSIC && originalMusicVolume < 0) {
            originalMusicVolume = cur
        }
        if (stream == AudioManager.STREAM_VOICE_CALL && originalVoiceCallVolume < 0) {
            originalVoiceCallVolume = cur
        }
        setStreamSafely(stream, target)

    }

    fun endAnnouncementWindow() {
        if (ringDucked && !ringerSilenced && originalRingVolume >= 0) {
            setStreamSafely(AudioManager.STREAM_RING, originalRingVolume)
        }
        ringDucked = false
        if (originalMusicVolume >= 0) {
            setStreamSafely(AudioManager.STREAM_MUSIC, originalMusicVolume)
            originalMusicVolume = -1
        }
        if (originalVoiceCallVolume >= 0) {
            setStreamSafely(AudioManager.STREAM_VOICE_CALL, originalVoiceCallVolume)
            originalVoiceCallVolume = -1
        }
    }

    // -------------------------------------------------------- voice actions

    fun silenceRinger() {
        if (originalRingVolume < 0) {
            originalRingVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        }
        setStreamSafely(AudioManager.STREAM_RING, 0)
        ringerSilenced = true
    }

    fun adjustRingVolume(up: Boolean) {
        val direction = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        runCatching {
            audioManager.adjustStreamVolume(AudioManager.STREAM_RING, direction, 0)
        }
        originalRingVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
    }

    fun requestSpeakerphoneOnAnswer() {
        runCatching {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        }
    }

    /** Restore every audio setting we touched. Safe to call repeatedly. */
    fun restoreAll() {
        disconnectBluetoothAudio()
        if (!ringerSilenced && originalRingVolume >= 0) {
            setStreamSafely(AudioManager.STREAM_RING, originalRingVolume)
        }
        if (originalMusicVolume >= 0) {
            setStreamSafely(AudioManager.STREAM_MUSIC, originalMusicVolume)
        }
        if (originalVoiceCallVolume >= 0) {
            setStreamSafely(AudioManager.STREAM_VOICE_CALL, originalVoiceCallVolume)
        }
        ringDucked = false
        ringerSilenced = false
        originalRingVolume = -1
        originalMusicVolume = -1
        originalVoiceCallVolume = -1
    }

    private fun setStreamSafely(stream: Int, volume: Int) {
        runCatching {
            // Throws SecurityException under Do-Not-Disturb without policy
            // access — ignore in that case.
            audioManager.setStreamVolume(stream, volume, 0)
        }
    }

    companion object {
        private const val ANNOUNCE_LEVEL = 0.75f
        private const val SCO_TIMEOUT_MS = 3000L
    }
}