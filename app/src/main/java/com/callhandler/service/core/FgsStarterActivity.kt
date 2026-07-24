package com.callhandler.service.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Invisible trampoline activity that starts [CallHandlerService] from a
 * foreground (TOP) context.
 *
 * On Android 14+ (API 34), starting a foreground service with
 * `FOREGROUND_SERVICE_TYPE_MICROPHONE` requires the app to be in an
 * "eligible state". A broadcast receiver (like [CallStateReceiver])
 * does NOT always provide this — especially on self-calls.
 *
 * By briefly launching this transparent activity, the app becomes TOP,
 * which is the highest-privilege state and always grants FGS mic type
 * exemption. The activity finishes immediately and is invisible.
 */
class FgsStarterActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Forward the service intent
        val serviceAction = intent.getStringExtra(EXTRA_SERVICE_ACTION)
        val number = intent.getStringExtra(EXTRA_NUMBER)

        if (serviceAction != null) {
            val serviceIntent = Intent(this, CallHandlerService::class.java).apply {
                action = serviceAction
                if (number != null) {
                    putExtra(CallHandlerService.EXTRA_NUMBER, number)
                }
            }

            runCatching {
                startForegroundService(serviceIntent)
                Log.i(TAG, "Started FGS from foreground activity context (action=$serviceAction)")
            }.onFailure {
                Log.e(TAG, "Failed to start FGS from activity: ${it.message}")
            }
        }

        // Finish immediately — invisible to the user
        finish()
        // Suppress any enter/exit animation
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val TAG = "FgsStarterActivity"
        private const val EXTRA_SERVICE_ACTION = "extra_service_action"
        private const val EXTRA_NUMBER = "extra_number"

        /**
         * Creates an intent to launch this trampoline activity, which will
         * then start [CallHandlerService] with the given action and extras.
         */
        fun createIntent(
            context: Context,
            serviceAction: String,
            number: String? = null
        ): Intent = Intent(context, FgsStarterActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                Intent.FLAG_ACTIVITY_NO_HISTORY
            )
            putExtra(EXTRA_SERVICE_ACTION, serviceAction)
            if (number != null) {
                putExtra(EXTRA_NUMBER, number)
            }
        }
    }
}
