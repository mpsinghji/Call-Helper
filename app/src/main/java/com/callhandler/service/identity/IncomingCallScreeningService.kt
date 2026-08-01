package com.callhandler.service.identity

import android.content.Intent
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import com.callhandler.service.core.CallHandlerService

/**
 * Supplies the incoming number through Android's call-screening role.
 * Calls are always allowed unchanged; this service never blocks or silences.
 */
class IncomingCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val isIncoming = callDetails.callDirection == Call.Details.DIRECTION_INCOMING
        val number = callDetails.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() }

        Log.i(TAG, "Call screening event: direction=${if (isIncoming) "INCOMING" else "OUTGOING"}, numberPresent=${number != null}")

        respondToCall(
            callDetails,
            CallResponse.Builder()
                .setDisallowCall(false)
                .setRejectCall(false)
                .setSilenceCall(false)
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .build()
        )
        Log.d(TAG, "Call screening response: ALLOW")

        if (isIncoming && number != null) {
            runCatching {
                startForegroundService(
                    Intent(this, CallHandlerService::class.java)
                        .setAction(CallHandlerService.ACTION_RINGING)
                        .putExtra(CallHandlerService.EXTRA_NUMBER, number)
                        .putExtra(
                            CallHandlerService.EXTRA_IDENTITY_SOURCE,
                            CallHandlerService.IDENTITY_SOURCE_CALL_SCREENING
                        )
                )
            }.onFailure {
                Log.e(TAG, "Failed to forward screening number: ${it.message}")
            }
        }
    }

    companion object {
        private const val TAG = "IncomingCallScreening"
    }
}
