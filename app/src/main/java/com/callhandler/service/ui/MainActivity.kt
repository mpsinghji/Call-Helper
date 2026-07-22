package com.callhandler.service.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceFragmentCompat
import com.callhandler.service.R
import com.callhandler.service.audio.AnnouncementManager
import com.callhandler.service.audio.AudioRouter
import com.callhandler.service.identity.CallNotificationListener
import com.callhandler.service.settings.SettingsManager
import kotlinx.coroutines.launch

/**
 * Hosts the settings screen, permission walkthrough, and a BT announcement
 * test button.
 */
class MainActivity : AppCompatActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermissionState()
        }

    private lateinit var grantButton: Button
    private var testAnnouncer: AnnouncementManager? = null
    private var testAudioRouter: AudioRouter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.rationaleText).setText(R.string.perm_rationale)

        grantButton = findViewById(R.id.grantPermissionsButton)
        grantButton.setOnClickListener { requestRuntimePermissions() }

        findViewById<Button>(R.id.notificationAccessButton).setOnClickListener {
            openNotificationListenerSettings()
        }

        findViewById<Button>(R.id.overlayPermissionButton).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        findViewById<Button>(R.id.testBluetoothButton).setOnClickListener {
            testBluetoothAnnouncement()
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    override fun onDestroy() {
        testAnnouncer?.shutdown()
        testAudioRouter?.restoreAll()
        super.onDestroy()
    }

    // -------------------------------------------------- BT announcement test

    private fun testBluetoothAnnouncement() {
        val router = testAudioRouter ?: AudioRouter(this).also { testAudioRouter = it }

        if (!router.isBluetoothAudioConnected()) {
            Toast.makeText(
                this,
                getString(R.string.test_bt_not_connected),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val settings = SettingsManager(this)
        val tts = testAnnouncer ?: AnnouncementManager(this, settings).also { testAnnouncer = it }

        Toast.makeText(this, getString(R.string.test_bt_starting), Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val scoOk = router.connectBluetoothAudio()
            if (!scoOk) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.test_bt_sco_failed),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            try {
                router.setAnnouncementVolume(settings.announcementVolumePct)
                tts.announce(getString(R.string.test_bt_announcement_text))
            } finally {
                router.restoreBluetoothVolume()
                router.disconnectBluetoothAudio()
            }

            Toast.makeText(
                this@MainActivity,
                getString(R.string.test_bt_done),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ----------------------------------------------------------- permissions

    private fun requestRuntimePermissions() {
        permissionLauncher.launch(getRuntimePermissions().toTypedArray())
    }

    private fun getRuntimePermissions(): List<String> = buildList {
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ANSWER_PHONE_CALLS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun allRuntimePermissionsGranted(): Boolean =
        getRuntimePermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun refreshPermissionState() {
        val runtimeGranted = allRuntimePermissionsGranted()
        grantButton.isEnabled = !runtimeGranted

        val listenerEnabled = NotificationManagerCompat
            .getEnabledListenerPackages(this)
            .contains(packageName)
        findViewById<Button>(R.id.notificationAccessButton).isEnabled = !listenerEnabled

        val overlayEnabled = Settings.canDrawOverlays(this)
        findViewById<Button>(R.id.overlayPermissionButton).isEnabled = !overlayEnabled

        val completedSteps = listOf(runtimeGranted, listenerEnabled, overlayEnabled).count { it }
        findViewById<TextView>(R.id.statusText).text =
            getString(R.string.setup_status, completedSteps, 3)
    }

    private fun openNotificationListenerSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        runCatching {
            intent.putExtra(
                "android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME",
                ComponentName(this, CallNotificationListener::class.java).flattenToString()
            )
        }
        runCatching { startActivity(intent) }
            .onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
        }
    }
}
