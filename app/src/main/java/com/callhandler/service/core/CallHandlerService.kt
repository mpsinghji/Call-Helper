package com.callhandler.service.core

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.callhandler.service.App
import com.callhandler.service.R
import com.callhandler.service.audio.AnnouncementManager
import com.callhandler.service.audio.AudioRouter
import com.callhandler.service.identity.CallerIdentityManager
import com.callhandler.service.settings.SettingsManager
import com.callhandler.service.ui.MainActivity
import com.callhandler.service.voice.VoiceCommand
import com.callhandler.service.voice.VoiceCommandManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.graphics.PixelFormat
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Foreground orchestrator for one incoming-call session.
 */
class CallHandlerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settings: SettingsManager
    private lateinit var stateMachine: CallStateMachine
    private lateinit var audioRouter: AudioRouter
    private lateinit var announcer: AnnouncementManager
    private lateinit var voiceCommands: VoiceCommandManager
    private lateinit var identityManager: CallerIdentityManager
    private lateinit var telecom: TelecomHelper

    private var sessionJob: Job? = null

    private var overlayView: View? = null
    private var callSource: CallSource = CallSource.CELLULAR
    private var currentIdentity: CallerIdentity = CallerIdentity.unknown(null)

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(this)
        stateMachine = CallStateMachine { from, to ->
            Log.d(TAG, "State: $from -> $to")
        }
        audioRouter = AudioRouter(this, settings)
        announcer = AnnouncementManager(this, settings)
        telecom = TelecomHelper(this)
        identityManager = CallerIdentityManager(this)
        voiceCommands = VoiceCommandManager(this, settings) { command ->
            onVoiceCommand(command)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RINGING -> {
                showMicExemptionOverlay()
                startForegroundCompat()
                onRinging(intent.getStringExtra(EXTRA_NUMBER))
            }

            ACTION_WHATSAPP_RINGING -> {
                startForegroundCompat()
                if (settings.whatsappEnabled) {
                    onWhatsAppRinging(intent)
                } else if (!stateMachine.isRinging) {
                    stopSelfSafely()
                }
            }

            ACTION_ANSWERED -> onCallAnswered()

            ACTION_ENDED, ACTION_WHATSAPP_DISMISSED -> onCallEnded()

            ACTION_TRUECALLER_UPDATE -> {
                val name = intent.getStringExtra(EXTRA_CALLER_NAME)
                if (name != null) identityManager.onTruecallerName(name)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- ringing

    private fun onRinging(number: String?) {
        if (stateMachine.isRinging) {
            // Duplicate RINGING broadcast — on Android 10+ the number often
            // arrives only in this second broadcast. Always forward it; the
            // identity manager de-duplicates.
            if (number != null) {
                scope.launch(Dispatchers.IO) {
                    identityManager.onNumberAvailable(number)
                }
            }
            return
        }
        if (!stateMachine.transitionTo(CallState.RINGING)) return

        callSource = CallSource.CELLULAR

        if (settings.voiceCommandsEnabled) {
            voiceCommands.startContinuous()
        }

        sessionJob = scope.launch {
            // Subscribe to identity updates BEFORE resolving, so nothing
            // published during the wait can be missed (StateFlow also
            // replays the latest value to late subscribers).
            launch {
                identityManager.identity.filterNotNull().collect { updated ->
                    if (updated.displayName != null &&
                        updated.displayName != currentIdentity.displayName
                    ) {
                        Log.i(TAG, "Identity update: ${updated.displayName} (${updated.source})")
                        currentIdentity = updated
                    }
                }
            }

            currentIdentity = identityManager.resolveIdentity(
                number = number,
                waitMs = TRUECALLER_WAIT_MS
            )

            runAnnouncementLoop()
        }
    }

    private fun onWhatsAppRinging(intent: Intent) {
        val callerName = intent.getStringExtra(EXTRA_CALLER_NAME)
        val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)

        if (stateMachine.isRinging) return
        if (!stateMachine.transitionTo(CallState.RINGING)) return

        // No voice commands for WhatsApp — no public API to act on the call.
        callSource = if (isVideo) CallSource.WHATSAPP_VIDEO else CallSource.WHATSAPP_VOICE
        currentIdentity = CallerIdentity(
            number = null,
            displayName = callerName,
            source = if (callerName != null) IdentitySource.CONTACT else IdentitySource.UNKNOWN
        )

        sessionJob = scope.launch {
            runAnnouncementLoop()
        }
    }

    // ------------------------------------------------------ announcement loop

    private suspend fun runAnnouncementLoop() {
        val maxRepeats = if (settings.repeatEnabled) settings.maxRepeats else 1
        val intervalMs = settings.repeatIntervalSec * 1000L
        var announcements = 0

        while (scope.isActive && stateMachine.isRinging && announcements < maxRepeats) {
            val route = audioRouter.selectRoute()
            if (route == AudioRouter.Route.NONE) {
                Log.w(TAG, "No route available — waiting for Bluetooth or speaker")
                delay(ROUTE_RECHECK_MS)
                continue
            }

            val name = currentIdentity.displayName ?: getString(R.string.unknown_caller)
            Log.i(TAG, "Announcing via $route: '$name' (${announcements + 1}/$maxRepeats)")

            // The recognizer must not run while TTS speaks — they fight over
            // audio focus and the recognizer dies/restarts in a visible loop.
            voiceCommands.pause()
            // Ducking applies ONLY while the announcement plays; full ring
            // volume comes back the moment it ends.
            audioRouter.beginAnnouncementWindow(route)
            val sco = route == AudioRouter.Route.BLUETOOTH &&
                    audioRouter.connectBluetoothAudio()
            try {
                announcer.announce(buildAnnouncement(name), viaBluetoothSco = sco)
                announcements++
            } finally {
                if (sco) audioRouter.disconnectBluetoothAudio()
                audioRouter.endAnnouncementWindow()
                voiceCommands.resume()
            }

            if (!stateMachine.isRinging) break
            delay(intervalMs)
        }

        // Max repeats reached: stay alive (listener keeps running) until the
        // call is answered, rejected, or ends.
        while (scope.isActive && stateMachine.isRinging) {
            delay(500)
        }
    }

    private fun buildAnnouncement(name: String): String = when (callSource) {
        CallSource.CELLULAR -> getString(R.string.announce_incoming_call, name)
        CallSource.WHATSAPP_VOICE -> getString(R.string.announce_whatsapp_voice, name)
        CallSource.WHATSAPP_VIDEO -> getString(R.string.announce_whatsapp_video, name)
    }

    // -------------------------------------------------------- voice commands

    private fun onVoiceCommand(command: VoiceCommand) {
        if (callSource != CallSource.CELLULAR) return
        if (!stateMachine.isRinging) return

        Log.i(TAG, "Voice command: $command")
        when (command) {
            VoiceCommand.ANSWER -> answerWithFallback(speakerAfter = false)

            VoiceCommand.SPEAKER -> answerWithFallback(speakerAfter = true)

            VoiceCommand.REJECT -> {
                if (!telecom.rejectCall()) {
                    // API 26-27 fallback: can't end the call, silence it instead.
                    audioRouter.silenceRinger()
                }
            }

            VoiceCommand.SILENT -> audioRouter.silenceRinger()

            VoiceCommand.VOLUME_UP -> audioRouter.adjustRingVolume(up = true)
            VoiceCommand.VOLUME_DOWN -> audioRouter.adjustRingVolume(up = false)
        }
    }

    /**
     * acceptRingingCall() silently fails on many devices, so if the phone
     * is still ringing shortly after, simulate a headset button press.
     * The session is torn down only when the OFFHOOK broadcast confirms.
     */
    private fun answerWithFallback(speakerAfter: Boolean) {
        announcer.stopSpeaking()
        telecom.answerCall()
        scope.launch {
            delay(ANSWER_FALLBACK_MS)
            if (stateMachine.isRinging) {
                Log.w(TAG, "Still ringing after acceptRingingCall — using headset-hook fallback")
                telecom.answerViaHeadsetHook()
            }
            repeat(10) {
                if (!stateMachine.isRinging) {
                    audioRouter.requestSpeakerphoneOnAnswer()
                    return@launch
                }
                delay(200)
            }
        }
    }

    // ------------------------------------------------------------- lifecycle

    private fun onCallAnswered() {
        if (!stateMachine.transitionTo(CallState.ANSWERED)) return
        stopSession()
        stopSelfSafely()
    }

    private fun onCallEnded() {
        if (stateMachine.current == CallState.IDLE) return // stale broadcast
        stateMachine.transitionTo(CallState.ENDED)
        stopSession()
        stopSelfSafely()
    }

    /** Stop announcements, recognition, and audio changes immediately. */
    private fun stopSession() {
        sessionJob?.cancel()
        sessionJob = null
        scope.coroutineContext.cancelChildren()
        announcer.stopSpeaking()
        voiceCommands.stopListening()
        hideMicExemptionOverlay()
        audioRouter.restoreAll()
        identityManager.reset()
        currentIdentity = CallerIdentity.unknown(null)
    }

    private fun stopSelfSafely() {
        stateMachine.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopSession()
        announcer.shutdown()
        voiceCommands.destroy()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ foreground
    /**
     * Android 14 denies the microphone FGS type (and mic input entirely)
     * to services started from the background. A visible overlay window +
     * SYSTEM_ALERT_WINDOW is a documented exemption, so we add an
     * invisible 1x1 px view while ringing and remove it at teardown.
     */
    private fun showMicExemptionOverlay() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission missing — voice commands will be mic-blocked. " +
                    "Grant 'Display over other apps' from the main screen.")
            return
        }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = View(this)
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching { wm.addView(view, params) }
            .onSuccess { overlayView = view }
            .onFailure { Log.w(TAG, "Overlay add failed: ${it.message}") }
    }

    private fun hideMicExemptionOverlay() {
        overlayView?.let { v ->
            runCatching {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
            }
        }
        overlayView = null
    }
    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val phone = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            val mic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else 0
            try {
                startForeground(NOTIFICATION_ID, notification, phone or mic)
            } catch (e: Exception) {
                Log.w(TAG, "FGS with mic type failed: ${e.message}; retrying without")
                try {
                    startForeground(NOTIFICATION_ID, notification, phone)
                } catch (e2: Exception) {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, App.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_announcing_title))
            .setContentText(getString(R.string.notif_announcing_text))
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "CallHandlerService"

        const val ACTION_RINGING = "com.callhandler.action.RINGING"
        const val ACTION_ANSWERED = "com.callhandler.action.ANSWERED"
        const val ACTION_ENDED = "com.callhandler.action.ENDED"
        const val ACTION_TRUECALLER_UPDATE = "com.callhandler.action.TRUECALLER_UPDATE"
        const val ACTION_WHATSAPP_RINGING = "com.callhandler.action.WHATSAPP_RINGING"
        const val ACTION_WHATSAPP_DISMISSED = "com.callhandler.action.WHATSAPP_DISMISSED"

        const val EXTRA_NUMBER = "extra_number"
        const val EXTRA_CALLER_NAME = "extra_caller_name"
        const val EXTRA_IS_VIDEO = "extra_is_video"

        private const val NOTIFICATION_ID = 42
        private const val TRUECALLER_WAIT_MS = 2500L
        private const val ROUTE_RECHECK_MS = 1500L
        private const val ANSWER_FALLBACK_MS = 700L
    }
}