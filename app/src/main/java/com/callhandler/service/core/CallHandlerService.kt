package com.callhandler.service.core

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.callhandler.service.App
import com.callhandler.service.R
import com.callhandler.service.audio.AudioRouter
import com.callhandler.service.settings.SettingsManager
import com.callhandler.service.ui.MainActivity
import com.callhandler.service.voice.VoiceCommand
import com.callhandler.service.voice.VoiceCommandManager
import com.callhandler.service.voice.VoiceListenerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground orchestrator for one incoming-call session.
 *
 * Listens for voice commands (answer, reject, speaker, silent) during
 * incoming cellular calls. Shows a small floating overlay with a mic
 * icon indicating the recognizer state.
 */
class CallHandlerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settings: SettingsManager
    private lateinit var stateMachine: CallStateMachine
    private lateinit var audioRouter: AudioRouter
    private lateinit var voiceCommands: VoiceCommandManager
    private lateinit var telecom: TelecomHelper

    private var sessionJob: Job? = null
    private var overlayView: View? = null
    private var micIconView: ImageView? = null
    private var overlayStateJob: Job? = null
    private var speakerRequested = false

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(this)
        stateMachine = CallStateMachine { from, to ->
            Log.d(TAG, "State: $from -> $to")
        }
        audioRouter = AudioRouter(this)
        telecom = TelecomHelper(this)
        voiceCommands = VoiceCommandManager(
            context = this,
            settings = settings,
            onCommand = { command, _ -> onVoiceCommand(command) },
            onUnrecognizedPhrases = { phrases ->
                if (phrases.isEmpty()) {
                    Log.d(TAG, "Recognizer returned NO phrases")
                } else {
                    phrases.forEachIndexed { index, phrase ->
                        Log.d(TAG, "Unrecognized[$index] = '$phrase'")
                    }
                }
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RINGING -> {
                startForegroundCompat()
                showStatusOverlay()
                onRinging()
            }

            ACTION_ANSWERED -> onCallAnswered()

            ACTION_ENDED -> onCallEnded()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- ringing

    private fun onRinging() {
        if (stateMachine.isRinging) return
        if (!stateMachine.transitionTo(CallState.RINGING)) return

        if (settings.voiceCommandsEnabled) {
            Log.d(TAG, "Voice commands: STARTING")
            voiceCommands.startContinuous()

            // If Bluetooth is connected, establish SCO so mic routes through earphones
            if (audioRouter.isBluetoothAudioConnected()) {
                sessionJob = scope.launch {
                    val scoOk = audioRouter.connectBluetoothAudio()
                    Log.d(TAG, "Bluetooth SCO for mic: $scoOk")
                }
            }
        }

        // Observe listener state to update overlay icon
        overlayStateJob = scope.launch {
            voiceCommands.listenerState.collect { state ->
                updateOverlayMicIcon(state)
            }
        }
    }

    // -------------------------------------------------------- voice commands

    private fun onVoiceCommand(command: VoiceCommand) {
        if (!stateMachine.isRinging) return

        Log.i(TAG, "Voice command: $command")
        when (command) {
            VoiceCommand.ANSWER -> answerWithFallback(speakerAfter = false)

            VoiceCommand.SPEAKER -> {
                speakerRequested = true
                answerWithFallback(speakerAfter = true)
            }

            VoiceCommand.REJECT -> {
                if (!telecom.rejectCall()) {
                    // API 26-27 fallback: can't end the call, silence it instead.
                    telecom.silenceRinger()
                }
            }

            VoiceCommand.SILENT -> {
                // Silence the ringer like pressing the power button
                telecom.silenceRinger()
                audioRouter.silenceRinger()
            }
        }
    }

    /**
     * acceptRingingCall() silently fails on many devices, so if the phone
     * is still ringing shortly after, simulate a headset button press.
     * The session is torn down only when the OFFHOOK broadcast confirms.
     */
    private fun answerWithFallback(speakerAfter: Boolean) {
        telecom.answerCall()
        scope.launch {
            delay(ANSWER_FALLBACK_MS)
            if (stateMachine.isRinging) {
                Log.w(TAG, "Still ringing after acceptRingingCall — using headset-hook fallback")
                telecom.answerViaHeadsetHook()
            }
            repeat(10) {
                if (!stateMachine.isRinging) {
                    if (speakerAfter) {
                        audioRouter.requestSpeakerphoneOnAnswer()
                    }
                    return@launch
                }
                delay(200)
            }
        }
    }

    // ------------------------------------------------------------- lifecycle

    private fun onCallAnswered() {
        if (!stateMachine.transitionTo(CallState.ANSWERED)) return

        if (speakerRequested) {
            scope.launch {
                delay(500)
                Log.d(TAG, "Activating speakerphone post-answer")
                audioRouter.requestSpeakerphoneOnAnswer()
            }
        }

        stopSession()
        stopSelfSafely()
    }

    private fun onCallEnded() {
        if (stateMachine.current == CallState.IDLE) return // stale broadcast
        stateMachine.transitionTo(CallState.ENDED)
        stopSession()
        stopSelfSafely()
    }

    /** Stop voice recognition and audio changes immediately. */
    private fun stopSession() {
        sessionJob?.cancel()
        sessionJob = null
        overlayStateJob?.cancel()
        overlayStateJob = null
        scope.coroutineContext.cancelChildren()
        Log.d(TAG, "Voice commands: STOPPING")
        voiceCommands.stopListening()
        hideStatusOverlay()
        audioRouter.restoreAll()
        speakerRequested = false
    }

    private fun stopSelfSafely() {
        stateMachine.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopSession()
        voiceCommands.destroy()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    // --------------------------------------------------------- status overlay

    /**
     * Shows a small draggable floating pill overlay with:
     * - A phone icon (app is active / listening to call state)
     * - A mic icon that updates based on SpeechRecognizer state
     *
     * This overlay also satisfies the Android 14 SYSTEM_ALERT_WINDOW
     * exemption for background-started mic foreground services.
     */
    private fun showStatusOverlay() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(this)) {
            Log.w(
                TAG, "Overlay permission missing — voice commands may be mic-blocked. " +
                        "Grant 'Display over other apps' from the main screen."
            )
            return
        }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_status, null)
        micIconView = view.findViewById(R.id.overlayMicIcon)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 100
        }

        // Make the overlay draggable
        view.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // Gravity is END, so moving right means decreasing x
                        params.x = initialX - (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        runCatching { wm.updateViewLayout(view, params) }
                        return true
                    }
                }
                return false
            }
        })

        runCatching { wm.addView(view, params) }
            .onSuccess { overlayView = view }
            .onFailure { Log.w(TAG, "Overlay add failed: ${it.message}") }
    }

    private fun hideStatusOverlay() {
        overlayView?.let { v ->
            runCatching {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
            }
        }
        overlayView = null
        micIconView = null
    }

    /** Update the mic icon in the overlay based on the recognizer state. */
    private fun updateOverlayMicIcon(state: VoiceListenerState) {
        val icon = micIconView ?: return
        when (state) {
            VoiceListenerState.LISTENING -> {
                icon.setImageResource(R.drawable.ic_mic_listening)
                icon.contentDescription = getString(R.string.overlay_mic_listening)
            }
            VoiceListenerState.PAUSED -> {
                icon.setImageResource(R.drawable.ic_mic_paused)
                icon.contentDescription = getString(R.string.overlay_mic_paused)
            }
            VoiceListenerState.OFF -> {
                icon.setImageResource(R.drawable.ic_mic_off)
                icon.contentDescription = getString(R.string.overlay_mic_off)
            }
        }
    }

    // ------------------------------------------------------------ foreground

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
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
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

        private const val NOTIFICATION_ID = 42
        private const val ANSWER_FALLBACK_MS = 700L
    }
}
