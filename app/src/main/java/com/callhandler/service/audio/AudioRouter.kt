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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor
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
 * - Dual-stream volume control for announcements (VOICE_CALL + MUSIC)
 *   because some devices route TTS through STREAM_MUSIC despite being
 *   configured for STREAM_VOICE_CALL
 * - Post-answer volume reapplication (Android resets volume when it
 *   creates its own Bluetooth connection for the real call)
 * - Ringer silencing and speakerphone toggling
 *
 * STREAM_RING (speaker ringtone) is NEVER touched.
 */
class AudioRouter(private val context: Context) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE)
                as? BluetoothManager)?.adapter ?: BluetoothAdapter.getDefaultAdapter()
    }

    private var scoConnected = false
    private var savedAudioMode = -1
    private var savedMusicVolume = -1   // STREAM_MUSIC is temporarily boosted for TTS, then restored
    private var audioFocusRequest: AudioFocusRequest? = null

    // Call-volume hold (post-answer SCO re-initialization guard)
    private var volumeHoldActive = false
    private var volumeHoldTarget = -1
    private var volumeHoldStartedAt = 0L
    private var volumeHoldLastActivityAt = 0L
    private var volumeHoldFocusRequest: AudioFocusRequest? = null
    private var volumeHoldModeListener: AudioManager.OnModeChangedListener? = null
    private var volumeHoldPoller: Runnable? = null
    private val volumeHoldHandler = Handler(Looper.getMainLooper())

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
     * 1. Request audio focus (GAIN_TRANSIENT).
     * 2. Save the current audio mode and STREAM_MUSIC volume.
     * 3. Switch to MODE_IN_COMMUNICATION — routes STREAM_VOICE_CALL through
     *    BT SCO. Without it, TTS may go to the loudspeaker.
     * 4. Calculate the target volume index from [sliderPct] (0–100)
     *    as a proportion of each stream's max index.
     * 5. Set BOTH STREAM_VOICE_CALL and STREAM_MUSIC to the target volume.
     *    This is belt-and-suspenders: some devices route TTS through
     *    STREAM_MUSIC despite our USAGE_VOICE_COMMUNICATION attributes.
     *
     * After the announcement, [finishAnnouncement] restores:
     *   - Audio mode (MODE_IN_COMMUNICATION → previous)
     *   - STREAM_MUSIC (restored to original — we don't want to permanently
     *     change the user's media volume)
     *
     * STREAM_VOICE_CALL is intentionally NOT restored — whatever the slider
     * sets becomes the in-call volume for the rest of the conversation.
     *
     * STREAM_RING is NEVER touched.
     *
     * Must be paired with [finishAnnouncement].
     *
     * @return `true` if TTS should play, `false` if [sliderPct] is 0 (skip).
     */
    fun prepareForAnnouncement(sliderPct: Int): Boolean {
        // 1. Request audio focus to prevent ducking
        requestAnnouncementFocus()

        // 2. Save current audio mode
        if (savedAudioMode < 0) {
            savedAudioMode = audioManager.mode
        }

        // 3. Activate SCO routing
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }
        Log.d(TAG, "Audio mode -> MODE_IN_COMMUNICATION (was $savedAudioMode)")

        // 4. Calculate proportional volume for each stream
        val clamped = sliderPct.coerceIn(0, 100)
        val fraction = clamped / 100f

        val vcMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        val vcTarget = Math.round(vcMax * fraction).coerceIn(0, vcMax)

        if (vcTarget == 0) {
            Log.d(TAG, "Announcement volume slider at 0% — skipping announcement")
            finishAnnouncement()
            return false
        }

        val musicMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val musicCurrent = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val musicTarget = Math.round(musicMax * fraction).coerceIn(0, musicMax)

        // Save STREAM_MUSIC so we can restore it after TTS
        if (savedMusicVolume < 0) {
            savedMusicVolume = musicCurrent
        }

        // 5. Set both streams
        Log.d(TAG, "VOICE_CALL volume -> $vcTarget / $vcMax (slider=$clamped%)")
        setStreamSafely(AudioManager.STREAM_VOICE_CALL, vcTarget)

        Log.d(TAG, "MUSIC volume -> $musicTarget / $musicMax (was $musicCurrent, slider=$clamped%)")
        setStreamSafely(AudioManager.STREAM_MUSIC, musicTarget)

        return true
    }

    /**
     * Restore audio mode and STREAM_MUSIC after a TTS announcement.
     *
     * - Audio mode: MODE_IN_COMMUNICATION → previous (required for routing)
     * - STREAM_MUSIC: restored to pre-announcement value (temporary boost)
     * - STREAM_VOICE_CALL: intentionally NOT restored (slider volume persists
     *   into the real phone call)
     * - Audio focus: abandoned
     *
     * Safe to call even if [prepareForAnnouncement] wasn't called.
     * Safe to call repeatedly.
     */
    fun finishAnnouncement() {
        if (savedAudioMode >= 0) {
            Log.d(TAG, "Audio mode -> $savedAudioMode (restoring)")
            runCatching { audioManager.mode = savedAudioMode }
            savedAudioMode = -1
        }
        if (savedMusicVolume >= 0) {
            Log.d(TAG, "MUSIC volume -> $savedMusicVolume (restoring to pre-announcement)")
            setStreamSafely(AudioManager.STREAM_MUSIC, savedMusicVolume)
            savedMusicVolume = -1
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

    // --------------------------------------------------- call volume hold

    /**
     * Hold VOICE_CALL volume at the slider value through Android's SCO
     * re-initialization after the call is answered.
     *
     * Android recreates the BT HFP link for the real call at a variable,
     * OEM-dependent time and applies its own volume (~50%), often after any
     * fixed post-answer delay. Instead of racing it, this hold re-applies the
     * target volume reactively until the OS stops interfering:
     *
     *  - Audio-focus changes: the dialer grabbing the call audio path
     *  - AudioManager mode changes: the OS flipping to MODE_IN_CALL
     *  - A [HOLD_TICK_MS] poll: detects OS clamps and the user's volume keys
     *
     * Self-terminates on:
     *  - The target holding undisturbed for [HOLD_SETTLED_MS]
     *  - [MAX_HOLD_MS] ceiling
     *  - Permanent audio-focus loss
     *
     * Uses a plain Handler on the main looper (not coroutines) so it survives
     * the service stopping right after ANSWERED.
     *
     * Idempotent: calls [stopCallVolumeHold] first.
     */
    fun startCallVolumeHold(pct: Int) {
        stopCallVolumeHold()

        val clamped = pct.coerceIn(0, 100)
        val maxIdx = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        val target = Math.round(maxIdx * (clamped / 100f)).coerceIn(0, maxIdx)
        if (target <= 0) {
            Log.d(TAG, "CallVolumeHold: target is 0 — skipping")
            return
        }

        volumeHoldTarget = target
        volumeHoldActive = true
        volumeHoldStartedAt = SystemClock.uptimeMillis()
        volumeHoldLastActivityAt = volumeHoldStartedAt
        Log.i(TAG, "CallVolumeHold: start pct=$clamped target=$target/$maxIdx")

        // Focus observer: when the dialer takes the call audio path, re-apply.
        val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    Log.d(TAG, "CallVolumeHold: permanent focus loss — stopping")
                    stopCallVolumeHold()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                AudioManager.AUDIOFOCUS_GAIN -> refreshVolumeHold("FOCUS($change)")
            }
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        volumeHoldFocusRequest = request
        val focusResult = runCatching { audioManager.requestAudioFocus(request) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        Log.d(TAG, "CallVolumeHold: focus request result=$focusResult (GRANTED=1)")

        // Mode observer: OS flips to MODE_IN_CALL when the real call connects.
        volumeHoldModeListener = AudioManager.OnModeChangedListener { mode ->
            if (volumeHoldActive) refreshVolumeHold("MODE($mode)")
        }
        runCatching {
            audioManager.addOnModeChangedListener(
                Executor { r -> r.run() },
                volumeHoldModeListener!!
            )
        }.onFailure { e -> Log.w(TAG, "CallVolumeHold: mode listener failed: ${e.message}") }

        // Poller: catches OS clamps and the user's volume keys.
        val poller = object : Runnable {
            override fun run() {
                if (!volumeHoldActive) return
                val now = SystemClock.uptimeMillis()
                if (now - volumeHoldStartedAt >= MAX_HOLD_MS) {
                    Log.d(TAG, "CallVolumeHold: $MAX_HOLD_MS ceiling reached — stopping")
                    stopCallVolumeHold()
                    return
                }
                val cur = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                if (cur != volumeHoldTarget) {
                    refreshVolumeHold("POLL(cur=$cur)")
                } else if (now - volumeHoldLastActivityAt >= HOLD_SETTLED_MS) {
                    Log.d(TAG, "CallVolumeHold: held $HOLD_SETTLED_MS ms undisturbed — stopping")
                    stopCallVolumeHold()
                    return
                }
                volumeHoldHandler.postDelayed(this, HOLD_TICK_MS)
            }
        }
        volumeHoldPoller = poller
        volumeHoldHandler.post(poller)
    }

    /** Re-apply the target volume and refresh the "no interference" window. */
    private fun refreshVolumeHold(trigger: String) {
        if (!volumeHoldActive) return
        volumeHoldLastActivityAt = SystemClock.uptimeMillis()
        val maxIdx = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        val target = volumeHoldTarget.coerceIn(0, maxIdx)
        Log.d(
            TAG,
            "CallVolumeHold: re-applied VOICE_CALL $cur -> $target / $maxIdx " +
                "(trigger=$trigger, mode=${audioManager.mode})"
        )
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, target, 0) }
    }

    /** Stop the hold and release all listeners. Safe to call repeatedly. */
    fun stopCallVolumeHold() {
        if (!volumeHoldActive && volumeHoldPoller == null &&
            volumeHoldFocusRequest == null && volumeHoldModeListener == null
        ) {
            return
        }
        volumeHoldActive = false
        volumeHoldHandler.removeCallbacksAndMessages(null)
        volumeHoldPoller = null
        volumeHoldFocusRequest?.let { req ->
            runCatching { audioManager.abandonAudioFocusRequest(req) }
            volumeHoldFocusRequest = null
        }
        volumeHoldModeListener?.let { listener ->
            runCatching { audioManager.removeOnModeChangedListener(listener) }
            volumeHoldModeListener = null
        }
        volumeHoldTarget = -1
        Log.d(TAG, "CallVolumeHold: stopped")
    }

    /** Clean up audio routing. Safe to call repeatedly. */
    fun cleanupAudio() {
        finishAnnouncement()
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

        // Call-volume hold timing
        private const val HOLD_TICK_MS = 700L
        private const val HOLD_SETTLED_MS = 3000L
        private const val MAX_HOLD_MS = 10_000L
    }
}