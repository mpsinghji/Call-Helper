package com.callhandler.service.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Receives PHONE_STATE broadcasts and forwards them to [CallHandlerService].
 *
 * Note: on Android 12+ starting a foreground service from a background
 * receiver is blocked UNLESS the app is exempt from battery optimization —
 * make sure the app is set to "Unrestricted" battery usage.
 */
class CallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return

        val serviceIntent = Intent(context, CallHandlerService::class.java).apply {
            action = when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> CallHandlerService.ACTION_RINGING
                TelephonyManager.EXTRA_STATE_OFFHOOK -> CallHandlerService.ACTION_ANSWERED
                TelephonyManager.EXTRA_STATE_IDLE -> CallHandlerService.ACTION_ENDED
                else -> return
            }
        }

        if (state == TelephonyManager.EXTRA_STATE_RINGING) {
            runCatching { context.startForegroundService(serviceIntent) }
                .onFailure {
                    Log.e(TAG, "Foreground service start failed: ${it.message}. " +
                            "Check battery optimization and background execution restrictions.")
                }
        } else {
            runCatching { context.startService(serviceIntent) }
        }
    }

    companion object {
        private const val TAG = "CallStateReceiver"
    }
}