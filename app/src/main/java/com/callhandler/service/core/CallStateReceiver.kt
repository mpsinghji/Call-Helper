package com.callhandler.service.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Receives PHONE_STATE broadcasts and forwards them to [CallHandlerService].
 *
 * For RINGING events, the service is started via [FgsStarterActivity] — a
 * transparent trampoline activity that briefly makes the app TOP. This
 * guarantees the foreground service can start with MICROPHONE type on
 * Android 14+, even on self-calls where the broadcast-receiver context
 * alone doesn't provide sufficient FGS type exemptions.
 */
class CallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                // Start via trampoline activity so the app is TOP when
                // startForegroundService is called → FGS mic type is granted.
                val trampolineIntent = FgsStarterActivity.createIntent(
                    context = context,
                    serviceAction = CallHandlerService.ACTION_RINGING,
                    number = number,
                    identitySource = CallHandlerService.IDENTITY_SOURCE_PHONE_STATE
                )
                runCatching { context.startActivity(trampolineIntent) }
                    .onFailure {
                        // Fallback: try starting the service directly
                        Log.w(TAG, "Trampoline activity failed, falling back to direct start: ${it.message}")
                        val serviceIntent = Intent(context, CallHandlerService::class.java).apply {
                            action = CallHandlerService.ACTION_RINGING
                            if (number != null) putExtra(CallHandlerService.EXTRA_NUMBER, number)
                            putExtra(
                                CallHandlerService.EXTRA_IDENTITY_SOURCE,
                                CallHandlerService.IDENTITY_SOURCE_PHONE_STATE
                            )
                        }
                        runCatching { context.startForegroundService(serviceIntent) }
                            .onFailure { e2 ->
                                Log.e(TAG, "Foreground service start failed: ${e2.message}. " +
                                        "Check battery optimization and background execution restrictions.")
                            }
                    }
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                val serviceIntent = Intent(context, CallHandlerService::class.java).apply {
                    action = CallHandlerService.ACTION_ANSWERED
                }
                runCatching { context.startService(serviceIntent) }
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                val serviceIntent = Intent(context, CallHandlerService::class.java).apply {
                    action = CallHandlerService.ACTION_ENDED
                }
                runCatching { context.startService(serviceIntent) }
            }
        }
    }

    companion object {
        private const val TAG = "CallStateReceiver"
    }
}