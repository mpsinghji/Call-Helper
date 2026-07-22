package com.callhandler.service.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceFragmentCompat
import com.callhandler.service.R

/**
 * Hosts the settings screen and walks the user through the permissions the
 * service needs: runtime permissions plus overlay access.
 */
class MainActivity : AppCompatActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermissionState()
        }

    private lateinit var grantButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.rationaleText).setText(R.string.perm_rationale)

        grantButton = findViewById(R.id.grantPermissionsButton)
        grantButton.setOnClickListener { requestRuntimePermissions() }

        findViewById<Button>(R.id.overlayPermissionButton).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
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

    private fun requestRuntimePermissions() {
        permissionLauncher.launch(getRuntimePermissions().toTypedArray())
    }

    private fun getRuntimePermissions(): List<String> = buildList {
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ANSWER_PHONE_CALLS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun allRuntimePermissionsGranted(): Boolean {
        return getRuntimePermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun refreshPermissionState() {
        val runtimeGranted = allRuntimePermissionsGranted()
        grantButton.isEnabled = !runtimeGranted

        val overlayEnabled = Settings.canDrawOverlays(this)
        findViewById<Button>(R.id.overlayPermissionButton).isEnabled = !overlayEnabled

        val completedSteps = listOf(runtimeGranted, overlayEnabled).count { it }
        findViewById<TextView>(R.id.statusText).text = getString(R.string.setup_status, completedSteps, 2)
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
        }
    }
}
