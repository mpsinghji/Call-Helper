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
import android.widget.TextView
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
import com.callhandler.service.voice.VoiceListenerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Foreground orchestrator for one incoming-call session.
 *
 * - Listens for voice commands (answer, reject, speaker, silent)
 * - Announces caller ID through Bluetooth earphones only (never speaker)
 * - Shows a draggable floating overlay with mic-state icon
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
    private var micIconView: ImageView? = null
    private var debugTextView: TextView? = null
    private var overlayStateJob: Job? = null
    private var heardTextJob: Job? = null
    private var speakerRequested = false
    private var fgsMicGranted = false
    private var currentIdentity: CallerIdentity = CallerIdentity.unknown(null)

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(this)
        stateMachine = CallStateMachine { from, to ->
            Log.d(TAG, "State: $from -> $to")
        }
        audioRouter = AudioRouter(this)
        announcer = AnnouncementManager(this, settings)
        telecom = TelecomHelper(this)
        identityManager = CallerIdentityManager(this)
        voiceCommands = VoiceCommandManager(
            context = this,
            settings = settings,
            onCommand = { command, _ -> onVoiceCommand(command) },
            onUnrecognizedPhrases = { phrases ->
                if (phrases.isNotEmpty()) {
                    phrases.forEachIndexed { i, p -> Log.d(TAG, "Unrecognized[$i] = '$p'") }
                }
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")
        when (intent?.action) {
            ACTION_RINGING -> {
                Log.d(TAG, "ACTION_RINGING received")
                startForegroundCompat()
                Log.d(TAG, "FGS started, now showing overlay...")
                showStatusOverlay()
                Log.d(TAG, "Overlay show attempted, now processing ringing...")
                onRinging(intent.getStringExtra(EXTRA_NUMBER))
            }

            ACTION_ANSWERED -> {
                Log.d(TAG, "ACTION_ANSWERED received")
                onCallAnswered()
            }
            
            ACTION_ENDED -> {
                Log.d(TAG, "ACTION_ENDED received")
                onCallEnded()
            }

            ACTION_TRUECALLER_UPDATE -> {
                val name = intent.getStringExtra(EXTRA_CALLER_NAME)
                Log.d(TAG, "ACTION_TRUECALLER_UPDATE: name=$name")
                if (name != null) identityManager.onTruecallerName(name)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- ringing

    private fun onRinging(number: String?) {
        if (stateMachine.isRinging) {
            // Duplicate RINGING broadcast — forward the number if available.
            if (number != null) {
                scope.launch(Dispatchers.IO) {
                    identityManager.onNumberAvailable(number)
                }
            }
            return
        }
        if (!stateMachine.transitionTo(CallState.RINGING)) return

        // Start voice commands
        if (settings.voiceCommandsEnabled) {
            Log.d(TAG, "Voice commands: STARTING")
            voiceCommands.startContinuous()
        }

        // Observe listener state for overlay icon
        overlayStateJob = scope.launch {
            voiceCommands.listenerState.collect { updateOverlayMicIcon(it) }
        }

        // Observe heard text for debug display in overlay
        heardTextJob = scope.launch {
            voiceCommands.lastHeardText.collect { text ->
                debugTextView?.text = text
            }
        }

        // Session: identity resolution + BT announcement
        sessionJob = scope.launch {
            // Subscribe to identity updates
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

            // Resolve identity (contacts first, then wait for Truecaller)
            currentIdentity = identityManager.resolveIdentity(
                number = number,
                waitMs = TRUECALLER_WAIT_MS
            )

            // Announce through BT earphones only
            if (settings.announcementEnabled && audioRouter.isBluetoothAudioConnected()) {
                announceViaBluetooth(currentIdentity)
            }
        }
    }

    // --------------------------------------------------- BT-only announcement

    /**
     * Announces the caller through Bluetooth earphones.
     *
     * Steps:
     * 1. Pause voice commands (so TTS doesn't trigger recognition)
     * 2. Connect SCO (BT phone-call audio channel)
     * 3. prepareForAnnouncement: set MODE_IN_COMMUNICATION (routes
     *    VOICE_CALL through SCO) + set VOICE_CALL volume to configured level
     * 4. Speak via TTS through SCO (STREAM_VOICE_CALL)
     * 5. restoreAfterAnnouncement: restore audio mode + volume
     * 6. Resume voice commands
     *
     * STREAM_RING (speaker ringtone) is NEVER touched.
     */
    private suspend fun announceViaBluetooth(identity: CallerIdentity) {
        if (!stateMachine.isRinging) return

        val scoOk = audioRouter.connectBluetoothAudio()
        if (!scoOk) {
            Log.w(TAG, "SCO connection failed — skipping announcement (never plays on speaker)")
            return
        }

        val name = identity.displayName ?: getString(R.string.unknown_caller)
        val text = getString(R.string.announce_incoming_call, name)
        Log.i(TAG, "Announcing via Bluetooth: '$name' at ${settings.announcementVolumePct}% volume")

        voiceCommands.pause()
        try {
            // Set MODE_IN_COMMUNICATION + VOICE_CALL volume
            audioRouter.prepareForAnnouncement(settings.announcementVolumePct)

            announcer.announce(text)
        } finally {
            // Restore audio mode + volume
            audioRouter.restoreAfterAnnouncement()
            if (settings.voiceCommandsEnabled) {
                voiceCommands.resume(viaBluetooth = true)
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
                    telecom.silenceRinger()
                }
            }

            VoiceCommand.SILENT -> {
                telecom.silenceRinger()
                audioRouter.silenceRinger()
            }
        }
    }

    private fun answerWithFallback(speakerAfter: Boolean) {
        announcer.stopSpeaking()
        telecom.answerCall()
        scope.launch {
            delay(ANSWER_FALLBACK_MS)
            if (stateMachine.isRinging) {
                Log.w(TAG, "Still ringing — using headset-hook fallback")
                telecom.answerViaHeadsetHook()
            }
            repeat(10) {
                if (!stateMachine.isRinging) {
                    if (speakerAfter) audioRouter.requestSpeakerphoneOnAnswer()
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
                audioRouter.requestSpeakerphoneOnAnswer()
            }
        }

        stopSession()
        stopSelfSafely()
    }

    private fun onCallEnded() {
        if (stateMachine.current == CallState.IDLE) return
        stateMachine.transitionTo(CallState.ENDED)
        stopSession()
        stopSelfSafely()
    }

    private fun stopSession() {
        sessionJob?.cancel()
        sessionJob = null
        overlayStateJob?.cancel()
        overlayStateJob = null
        heardTextJob?.cancel()
        heardTextJob = null
        scope.coroutineContext.cancelChildren()
        announcer.stopSpeaking()
        voiceCommands.stopListening()
        hideStatusOverlay()
        audioRouter.restoreAll()
        identityManager.reset()
        currentIdentity = CallerIdentity.unknown(null)
        speakerRequested = false
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

    // --------------------------------------------------------- status overlay

    private fun showStatusOverlay() {
        Log.d(TAG, "showStatusOverlay() called")
        
        if (overlayView != null) {
            Log.d(TAG, "Overlay already exists, skipping")
            return
        }
        
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "⚠️ OVERLAY PERMISSION DENIED - Check Settings > Apps > Call Handler > Display over other apps")
            return
        }
        
        Log.d(TAG, "Overlay permission: OK")

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        Log.d(TAG, "Inflating overlay layout...")
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_status, null)
        micIconView = view.findViewById(R.id.overlayMicIcon)
        debugTextView = view.findViewById(R.id.overlayDebugText)
        
        Log.d(TAG, "Overlay views: micIcon=${micIconView != null}, debugText=${debugTextView != null}")

        // Show mic-blocked warning immediately if FGS mic type failed
        if (!fgsMicGranted) {
            debugTextView?.text = "⚠ Mic unavailable"
            Log.d(TAG, "Set mic warning text (FGS mic not granted)")
        }

        val (savedX, savedY) = loadOverlayPosition()
        Log.d(TAG, "Loaded overlay position: x=$savedX, y=$savedY")

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = savedX
            y = savedY
        }

        // Draggable with position persistence
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
                        params.x = initialX - (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        runCatching { wm.updateViewLayout(view, params) }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        saveOverlayPosition(params.x, params.y)
                        return true
                    }
                }
                return false
            }
        })

        Log.d(TAG, "Adding overlay to WindowManager...")
        runCatching { wm.addView(view, params) }
            .onSuccess { 
                overlayView = view
                Log.i(TAG, "✓✓✓ OVERLAY SUCCESSFULLY DISPLAYED at ($savedX, $savedY) ✓✓✓")
            }
            .onFailure { e ->
                Log.e(TAG, "✗✗✗ OVERLAY ADD FAILED: ${e.message} ✗✗✗", e)
            }
    }

    private fun hideStatusOverlay() {
        Log.d(TAG, "hideStatusOverlay() called, overlayView=${overlayView != null}")
        overlayView?.let { v ->
            Log.d(TAG, "Removing overlay from WindowManager...")
            runCatching {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
            }.onSuccess {
                Log.d(TAG, "✓ Overlay removed successfully")
            }.onFailure { e ->
                Log.w(TAG, "✗ Overlay removal failed: ${e.message}")
            }
        }
        overlayView = null
        micIconView = null
        debugTextView = null
    }

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

    private fun saveOverlayPosition(x: Int, y: Int) {
        getSharedPreferences(OVERLAY_PREFS, MODE_PRIVATE)
            .edit().putInt("overlay_x", x).putInt("overlay_y", y).apply()
    }

    private fun loadOverlayPosition(): Pair<Int, Int> {
        val prefs = getSharedPreferences(OVERLAY_PREFS, MODE_PRIVATE)
        return Pair(
            prefs.getInt("overlay_x", DEFAULT_OVERLAY_X),
            prefs.getInt("overlay_y", DEFAULT_OVERLAY_Y)
        )
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
                fgsMicGranted = true
                Log.i(TAG, "FGS started with phoneCall|microphone type")
            } catch (e: Exception) {
                fgsMicGranted = false
                Log.w(TAG, "FGS with mic type failed: ${e.message}; retrying without")
                Log.e(TAG, "\u26a0 Voice commands will NOT work this session \u2014 mic FGS type denied by system")
                try {
                    startForeground(NOTIFICATION_ID, notification, phone)
                } catch (e2: Exception) {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        } else {
            fgsMicGranted = true
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
        private const val OVERLAY_PREFS = "overlay_prefs"
        private const val DEFAULT_OVERLAY_X = 16
        private const val DEFAULT_OVERLAY_Y = 400

        const val ACTION_RINGING = "com.callhandler.action.RINGING"
        const val ACTION_ANSWERED = "com.callhandler.action.ANSWERED"
        const val ACTION_ENDED = "com.callhandler.action.ENDED"
        const val ACTION_TRUECALLER_UPDATE = "com.callhandler.action.TRUECALLER_UPDATE"

        const val EXTRA_NUMBER = "extra_number"
        const val EXTRA_CALLER_NAME = "extra_caller_name"

        private const val NOTIFICATION_ID = 42
        private const val TRUECALLER_WAIT_MS = 2500L
        private const val ANSWER_FALLBACK_MS = 700L
    }
}
