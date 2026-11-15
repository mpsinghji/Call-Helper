package com.callhandler.service.audio

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.callhandler.service.App
import com.callhandler.service.R
import com.callhandler.service.debug.CallDebugTracker
import com.callhandler.service.settings.SettingsManager
import com.callhandler.service.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight foreground service that announces VoIP calls
 * (WhatsApp, Instagram, Snapchat, etc.) through Bluetooth earphones.
 *
 * Unlike [com.callhandler.service.core.CallHandlerService], this service:
 * - Has NO voice commands (can't answer VoIP calls programmatically)
 * - Has NO overlay
 * - Has NO state machine
 * - Announces and safely stops itself or on call decline/dismissal
 *
 * Flow: start → FGS notification → connect BT SCO → TTS → stop.
 */
class VoipAnnouncementService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settings: SettingsManager
    private lateinit var audioRouter: AudioRouter
    private lateinit var announcer: AnnouncementManager

    private var announceJob: Job? = null
    @Volatile
    private var isAnnouncing = false
    private val isStopping = AtomicBoolean(false)

    @Volatile
    private var currentSessionId: Long = 0L
    @Volatile
    private var currentNotificationKey: String? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        settings = SettingsManager(this)
        audioRouter = AudioRouter(this)
        announcer = AnnouncementManager(this, settings)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP_ANNOUNCEMENT) {
            val reqSessionId = intent.getLongExtra(EXTRA_SESSION_ID, 0L)
            val reqKey = intent.getStringExtra(EXTRA_NOTIFICATION_KEY)
            Log.d(TAG, "ACTION_STOP_ANNOUNCEMENT received: reqSession=$reqSessionId, reqKey=$reqKey (currentSession=$currentSessionId)")
            cancelIfMatches(reqSessionId, reqKey)
            return START_NOT_STICKY
        }

        val text = intent?.getStringExtra(EXTRA_ANNOUNCEMENT_TEXT)
        val sessionId = intent?.getLongExtra(EXTRA_SESSION_ID, 0L) ?: 0L
        val notificationKey = intent?.getStringExtra(EXTRA_NOTIFICATION_KEY)

        if (text == null) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        // If already announcing a DIFFERENT call, cancel the old one first
        if (isAnnouncing) {
            if (sessionId != 0L && sessionId == currentSessionId) {
                Log.d(TAG, "Already announcing session $sessionId, ignoring duplicate: $text")
                return START_NOT_STICKY
            } else {
                Log.i(TAG, "Cancelling previous announcement (session=$currentSessionId) for new session $sessionId")
                cancelAnnouncementInternal()
            }
        }

        isStopping.set(false)
        currentSessionId = sessionId
        currentNotificationKey = notificationKey
        isAnnouncing = true

        startForegroundCompat()

        announceJob = scope.launch {
            try {
                announceViaBluetooth(text, sessionId)
            } finally {
                isAnnouncing = false
                stopSelfSafely()
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Cancels announcement if [sessionId] or [notificationKey] matches this session.
     */
    fun cancelIfMatches(sessionId: Long?, notificationKey: String?) {
        val matchesSession = (sessionId != null && sessionId != 0L && (sessionId == currentSessionId || currentSessionId == 0L))
        val matchesKey = (notificationKey != null && notificationKey == currentNotificationKey)
        val shouldCancel = ((sessionId == null || sessionId == 0L) && notificationKey == null) || matchesSession || matchesKey

        if (shouldCancel) {
            Log.i(TAG, "Cancelling VoIP announcement for session=$sessionId, key=$notificationKey")
            cancelAnnouncementInternal()
            stopSelfSafely()
        }
    }

    private fun cancelAnnouncementInternal() {
        val sid = currentSessionId
        announcer.cancelAnnouncement(sid)
        announcer.stopSpeaking()
        announceJob?.cancel()
        announceJob = null
        audioRouter.cleanupAudio()
    }

    /**
     * Announces VoIP call text through Bluetooth earphones.
     * 
     * IMPORTANT: VoIP apps (WhatsApp) handle their own ringtone routing.
     * We must complete the announcement quickly and fully restore audio state
     * so the app's ringtone can play normally without volume shifts.
     */
    private suspend fun announceViaBluetooth(text: String, sessionId: Long) {
        if (announcer.isSessionCancelled(sessionId) || !currentCoroutineContext().isActive) {
            Log.w(TAG, "VoIP announcement cancelled before start (session=$sessionId)")
            return
        }

        if (!audioRouter.isBluetoothAudioConnected()) {
            Log.w(TAG, "No Bluetooth audio — skipping VoIP announcement")
            CallDebugTracker.onAnnouncementBlocked("NO_BLUETOOTH_AUDIO")
            return
        }

        val scoOk = audioRouter.connectBluetoothAudio()
        if (!scoOk) {
            Log.w(TAG, "SCO connection failed — skipping VoIP announcement")
            CallDebugTracker.onAnnouncementBlocked("SCO_CONNECTION_FAILED")
            return
        }

        if (announcer.isSessionCancelled(sessionId) || !currentCoroutineContext().isActive) {
            Log.w(TAG, "VoIP announcement cancelled after SCO connect (session=$sessionId)")
            return
        }

        Log.i(TAG, "Announcing VoIP [session=$sessionId]: '$text' at ${settings.announcementVolumePct}% volume")

        val repeatEnabled = settings.repeatAnnouncementEnabled
        val maxRepeats = if (repeatEnabled) MAX_ANNOUNCEMENT_REPEATS else 1
        var repeatCount = 0

        try {
            val shouldPlay = audioRouter.prepareForAnnouncement(
                settings.announcementVolumePct
            )
            if (!shouldPlay) {
                Log.d(TAG, "Announcement volume is 0% — skipping VoIP TTS")
                CallDebugTracker.onAnnouncementBlocked("VOLUME_ZERO")
                return
            }

            while (repeatCount < maxRepeats) {
                // Terminal-state and cancellation checks before starting TTS and before each repeat segment
                if (announcer.isSessionCancelled(sessionId) || !currentCoroutineContext().isActive) {
                    Log.d(TAG, "VoIP announcement stopped before utterance (session=$sessionId, repeat=$repeatCount)")
                    break
                }

                announcer.announce(text, sessionId) {
                    announcer.isSessionCancelled(sessionId)
                }

                repeatCount++

                // Before next repeat segment: check again and delay
                if (repeatCount < maxRepeats && repeatEnabled) {
                    if (announcer.isSessionCancelled(sessionId) || !currentCoroutineContext().isActive) {
                        break
                    }
                    delay(REPEAT_INTERVAL_MS)
                }
            }
        } finally {
            // Restore audio mode + abandon focus then disconnect SCO
            // so VoIP app can handle its own ringtone routing
            audioRouter.finishAnnouncement()
            audioRouter.disconnectBluetoothAudio()
            Log.d(TAG, "Audio mode restored, SCO disconnected — VoIP app can now handle ringtone routing")
        }
    }

    private fun stopSelfSafely() {
        if (!isStopping.compareAndSet(false, true)) return
        val sid = currentSessionId
        announceJob?.cancel()
        announcer.cancelAnnouncement(sid)
        announcer.stopSpeaking()
        audioRouter.cleanupAudio()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (activeInstance == this) activeInstance = null
        val sid = currentSessionId
        announceJob?.cancel()
        announcer.cancelAnnouncement(sid)
        announcer.stopSpeaking()
        announcer.shutdown()
        audioRouter.cleanupAudio()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                )
            } catch (e: Exception) {
                Log.w(TAG, "FGS phoneCall type failed: ${e.message}")
                startForeground(NOTIFICATION_ID, notification)
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
            .setContentTitle(getString(R.string.voip_notif_title))
            .setContentText(getString(R.string.voip_notif_text))
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "VoipAnnouncement"
        private const val NOTIFICATION_ID = 43  // different from CallHandlerService (42)

        const val ACTION_STOP_ANNOUNCEMENT = "com.callhandler.service.ACTION_STOP_VOIP_ANNOUNCEMENT"
        const val EXTRA_ANNOUNCEMENT_TEXT = "extra_announcement_text"
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_NOTIFICATION_KEY = "extra_notification_key"

        private const val REPEAT_INTERVAL_MS = 3000L
        private const val MAX_ANNOUNCEMENT_REPEATS = 3

        @Volatile
        var activeInstance: VoipAnnouncementService? = null
            private set

        /**
         * Start the service to announce a VoIP call.
         *
         * @param text Full announcement string, e.g. "WhatsApp voice call from John"
         */
        fun announce(
            context: Context,
            text: String,
            sessionId: Long = 0L,
            notificationKey: String? = null
        ) {
            val intent = Intent(context, VoipAnnouncementService::class.java).apply {
                putExtra(EXTRA_ANNOUNCEMENT_TEXT, text)
                putExtra(EXTRA_SESSION_ID, sessionId)
                if (notificationKey != null) putExtra(EXTRA_NOTIFICATION_KEY, notificationKey)
            }
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.e(TAG, "Failed to start VoIP announcement: ${it.message}") }
        }

        /**
         * Stop the VoIP announcement service immediately.
         */
        fun stopAnnouncement(
            context: Context,
            sessionId: Long? = null,
            notificationKey: String? = null
        ) {
            // 1. Direct in-process fast path for 0ms immediate cancellation
            activeInstance?.cancelIfMatches(sessionId, notificationKey)

            // 2. Service intent path to ensure Android service lifecycle is updated
            val intent = Intent(context, VoipAnnouncementService::class.java).apply {
                action = ACTION_STOP_ANNOUNCEMENT
                if (sessionId != null) putExtra(EXTRA_SESSION_ID, sessionId)
                if (notificationKey != null) putExtra(EXTRA_NOTIFICATION_KEY, notificationKey)
            }
            runCatching { context.startService(intent) }
        }
    }
}
