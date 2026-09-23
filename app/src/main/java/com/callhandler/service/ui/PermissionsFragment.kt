package com.callhandler.service.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.callhandler.service.R
import com.callhandler.service.identity.CallNotificationListener
import com.google.android.material.progressindicator.LinearProgressIndicator

class PermissionsFragment : Fragment() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermissionState()
        }

    private lateinit var statusText: TextView
    private lateinit var setupProgressBar: LinearProgressIndicator
    private lateinit var setupSubtext: TextView

    private lateinit var badgeRuntime: TextView
    private lateinit var grantButton: Button

    private lateinit var badgeNotification: TextView
    private lateinit var notificationAccessButton: Button

    private lateinit var badgeOverlay: TextView
    private lateinit var overlayPermissionButton: Button

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_permissions, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        statusText = view.findViewById(R.id.statusText)
        setupProgressBar = view.findViewById(R.id.setupProgressBar)
        setupSubtext = view.findViewById(R.id.setupSubtext)

        badgeRuntime = view.findViewById(R.id.badgeRuntime)
        grantButton = view.findViewById(R.id.grantPermissionsButton)
        grantButton.setOnClickListener { requestRuntimePermissions() }

        badgeNotification = view.findViewById(R.id.badgeNotification)
        notificationAccessButton = view.findViewById(R.id.notificationAccessButton)
        notificationAccessButton.setOnClickListener { openNotificationListenerSettings() }

        badgeOverlay = view.findViewById(R.id.badgeOverlay)
        overlayPermissionButton = view.findViewById(R.id.overlayPermissionButton)
        overlayPermissionButton.setOnClickListener { openOverlaySettings() }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    fun refreshPermissionState() {
        if (!isAdded) return
        val context = context ?: return

        val runtimeGranted = allRuntimePermissionsGranted()
        updateBadgeState(
            badge = badgeRuntime,
            button = grantButton,
            isGranted = runtimeGranted,
            grantedButtonText = getString(R.string.badge_granted),
            normalButtonText = getString(R.string.btn_grant_permissions)
        )

        val listenerEnabled = NotificationManagerCompat
            .getEnabledListenerPackages(context)
            .contains(context.packageName)
        updateBadgeState(
            badge = badgeNotification,
            button = notificationAccessButton,
            isGranted = listenerEnabled,
            grantedButtonText = getString(R.string.badge_granted),
            normalButtonText = getString(R.string.btn_notification_access)
        )

        val overlayEnabled = Settings.canDrawOverlays(context)
        updateBadgeState(
            badge = badgeOverlay,
            button = overlayPermissionButton,
            isGranted = overlayEnabled,
            grantedButtonText = getString(R.string.badge_granted),
            normalButtonText = getString(R.string.btn_overlay_permission)
        )

        val completedSteps = listOf(runtimeGranted, listenerEnabled, overlayEnabled).count { it }
        val totalSteps = 3

        statusText.text = getString(R.string.setup_status, completedSteps, totalSteps)
        setupProgressBar.max = totalSteps
        setupProgressBar.progress = completedSteps

        if (completedSteps == totalSteps) {
            setupSubtext.setText(R.string.perm_all_granted)
        } else {
            setupSubtext.setText(R.string.perm_action_required)
        }

        (activity as? MainActivity)?.updatePermissionBadge()
    }

    private fun updateBadgeState(
        badge: TextView,
        button: Button,
        isGranted: Boolean,
        grantedButtonText: String,
        normalButtonText: String,
        isOptional: Boolean = false
    ) {
        val context = requireContext()
        if (isGranted) {
            badge.text = getString(R.string.badge_granted)
            badge.setBackgroundResource(R.drawable.bg_badge_granted)
            badge.setTextColor(ContextCompat.getColor(context, R.color.status_granted))
            button.isEnabled = false
            button.text = grantedButtonText
        } else {
            if (isOptional) {
                badge.text = getString(R.string.badge_optional)
                badge.setBackgroundResource(R.drawable.bg_badge_optional)
                badge.setTextColor(ContextCompat.getColor(context, R.color.status_optional))
            } else {
                badge.text = getString(R.string.badge_required)
                badge.setBackgroundResource(R.drawable.bg_badge_required)
                badge.setTextColor(ContextCompat.getColor(context, R.color.status_required))
            }
            button.isEnabled = true
            button.text = normalButtonText
        }
    }

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

    private fun allRuntimePermissionsGranted(): Boolean {
        val context = context ?: return false
        return getRuntimePermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }



    private fun openNotificationListenerSettings() {
        val context = requireContext()
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        runCatching {
            intent.putExtra(
                "android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME",
                ComponentName(context, CallNotificationListener::class.java).flattenToString()
            )
        }
        runCatching { startActivity(intent) }
            .onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }
    }

    private fun openOverlaySettings() {
        val context = requireContext()
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        )
    }
}
