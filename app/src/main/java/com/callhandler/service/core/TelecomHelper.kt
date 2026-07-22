package com.callhandler.service.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat

/**
 * Call-control actions for voice commands.
 *
 * acceptRingingCall() silently no-ops on many newer devices, so the service
 * follows it up with [answerViaHeadsetHook] — a simulated wired-headset
 * button press, which the telecom stack treats as "answer" while ringing.
 */
class TelecomHelper(private val context: Context) {

    private val telecomManager: TelecomManager? =
        context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

    /** First-choice answer path. Returns true if the call was attempted. */
    @Suppress("DEPRECATION", "MissingPermission")
    fun answerCall(): Boolean {
        val tm = telecomManager ?: return false
        if (!hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)) return false
        return runCatching {
            tm.acceptRingingCall()
            true
        }.getOrDefault(false)
    }

    /** Fallback answer path: simulate a headset button press. */
    fun answerViaHeadsetHook() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching {
            am.dispatchMediaKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK)
            )
            am.dispatchMediaKeyEvent(
                KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK)
            )
        }
    }

    /** Silences the current ringer. Only affects the current call. */
    fun silenceRinger() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { telecomManager?.silenceRinger() }
        }
    }

    /**
     * Rejects the ringing call. API 28+ only; on 26-27 the caller falls
     * back to silencing the ringer.
     */
    @Suppress("DEPRECATION", "MissingPermission")
    fun rejectCall(): Boolean {
        val tm = telecomManager ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        if (!hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)) return false
        return runCatching {
            tm.endCall()
        }.getOrDefault(false)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
}