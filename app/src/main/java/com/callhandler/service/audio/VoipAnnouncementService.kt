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
import com.callhandler.service.settings.SettingsManager
import com.callhandler.service.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Lightweight foreground service that announces VoIP calls
 * (WhatsApp, Instagram, Snapchat, etc.) through Bluetooth earphones.
 *
 * Unlike [com.callhandler.service.core.CallHandlerService], this service:
 * - Has NO voice commands (can't answer VoIP calls programmatically)
 * - Has NO overlay
 * - Has NO state machine
 * - Simply announces and stops itself
 *
 * Flow: start → FGS notification → connect BT SCO → TTS → stop.
 */
class VoipAnnouncementService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settings: SettingsManager
    private lateinit var audioRouter: AudioRouter
    private lateinit var announcer: AnnouncementManager

    private var announceJob: Job? = null
    private var isAnnouncing = false

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(this)
        audioRouter = AudioRouter(this)
        announcer = AnnouncementManager(this, settings)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_ANNOUNCEMENT_TEXT)

        if (text == null) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        // If already announcing, ignore duplicate
        if (isAnnouncing) {
            Log.d(TAG, "Already announcing, ignoring: $text")
            return START_NOT_STICKY
        }

        startForegroundCompat()

        isAnnouncing = true
        announceJob = scope.launch {
            try {
                announceViaBluetooth(text)
            } finally {
                isAnnouncing = false
                stopSelfSafely()
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Announces VoIP call text through Bluetooth earphones.
     * 
     * IMPORTANT: VoIP apps (WhatsApp) handle their own ringtone routing.
     * We must complete the announcement quickly and fully restore audio state
     * so the app's ringtone can play normally without volume shifts.
     */
    private suspend fun announceViaBluetooth(text: String) {
        if (!audioRouter.isBluetoothAudioConnected()) {
            Log.w(TAG, "No Bluetooth audio — skipping VoIP announcement")
            return
        }

        val scoOk = audioRouter.connectBluetoothAudio()
        if (!scoOk) {
            Log.w(TAG, "SCO connection failed — skipping VoIP announcement")
            return
        }

        Log.i(TAG, "Announcing VoIP: '$text' at ${settings.announcementVolumePct}% volume")

        try {
            val shouldPlay = audioRouter.prepareForAnnouncement(
                settings.announcementVolumePct
            )
            if (shouldPlay) {
                announcer.announce(text)
            } else {
                Log.d(TAG, "Announcement volume is 0% — skipping VoIP TTS")
            }
        } finally {
            // CRITICAL: Fully restore audio state immediately after announcement
            // so VoIP app (WhatsApp) can take over audio routing for its ringtone
            audioRouter.restoreAfterAnnouncement()
            audioRouter.disconnectBluetoothAudio()
            Log.d(TAG, "Audio state fully restored — VoIP app can now handle ringtone routing")
        }
    }

    private fun stopSelfSafely() {
        announceJob?.cancel()
        announcer.stopSpeaking()
        audioRouter.restoreAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        announceJob?.cancel()
        announcer.stopSpeaking()
        announcer.shutdown()
        audioRouter.restoreAll()
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

        const val EXTRA_ANNOUNCEMENT_TEXT = "extra_announcement_text"

        /**
         * Start the service to announce a VoIP call.
         *
         * @param text Full announcement string, e.g. "WhatsApp voice call from John"
         */
        fun announce(context: Context, text: String) {
            val intent = Intent(context, VoipAnnouncementService::class.java).apply {
                putExtra(EXTRA_ANNOUNCEMENT_TEXT, text)
            }
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.e(TAG, "Failed to start VoIP announcement: ${it.message}") }
        }
    }
}
