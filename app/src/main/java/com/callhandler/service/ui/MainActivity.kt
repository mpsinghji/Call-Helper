package com.callhandler.service.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceFragmentCompat
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.callhandler.service.R
import com.callhandler.service.update.UpdateManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Hosts the tabbed interface:
 * - Tab 0: Settings (Main app configuration)
 * - Tab 1: Permissions (Dedicated permission management & setup)
 * - Tab 2: Tools (Bluetooth announcement test & debug console)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
        setSupportActionBar(toolbar)

        tabLayout = findViewById(R.id.tabLayout)
        viewPager = findViewById(R.id.viewPager)

        val adapter = MainPagerAdapter(this)
        viewPager.adapter = adapter

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_settings)
                1 -> getString(R.string.tab_permissions)
                2 -> getString(R.string.tab_tools)
                else -> ""
            }
        }.attach()

        // Automatically check for GitHub release updates on startup
        startUpdateCheck(manualTrigger = false)
    }

    private var isCheckingUpdate = false
    private var downloadedUpdateFile: File? = null

    /**
     * Checks GitHub Releases for updates.
     * Displays a small floating card:
     * - If up-to-date: displays confirmation and fades away after 2 seconds.
     * - If update found: automatically downloads APK, updates progress, and launches installer prompt.
     */
    fun startUpdateCheck(manualTrigger: Boolean = false) {
        if (isCheckingUpdate) return
        isCheckingUpdate = true

        val cardUpdate = findViewById<View>(R.id.cardUpdate) ?: return
        val spinner = findViewById<ProgressBar>(R.id.updateSpinner) ?: return
        val icon = findViewById<ImageView>(R.id.updateStatusIcon) ?: return
        val tvStatus = findViewById<TextView>(R.id.tvUpdateStatus) ?: return
        val btnAction = findViewById<Button>(R.id.btnUpdateAction) ?: return
        val btnDismiss = findViewById<ImageButton>(R.id.btnDismissUpdate) ?: return
        val progressIndicator = findViewById<LinearProgressIndicator>(R.id.updateProgressBar) ?: return

        cardUpdate.animate().cancel()
        cardUpdate.alpha = 1f
        cardUpdate.visibility = View.VISIBLE
        spinner.visibility = View.VISIBLE
        icon.visibility = View.GONE
        btnAction.visibility = View.GONE
        btnDismiss.visibility = View.GONE
        progressIndicator.visibility = View.GONE
        tvStatus.text = getString(R.string.update_checking)

        val currentVersion = try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }

        lifecycleScope.launch {
            when (val result = UpdateManager.checkForUpdate(currentVersion)) {
                is UpdateManager.CheckResult.UpToDate -> {
                    spinner.visibility = View.GONE
                    icon.visibility = View.VISIBLE
                    icon.setImageResource(R.drawable.ic_check_circle)
                    tvStatus.text = getString(R.string.update_latest, currentVersion)

                    delay(2000)
                    cardUpdate.animate()
                        .alpha(0f)
                        .setDuration(400)
                        .withEndAction {
                            cardUpdate.visibility = View.GONE
                            isCheckingUpdate = false
                        }
                        .start()
                }

                is UpdateManager.CheckResult.Error -> {
                    if (manualTrigger) {
                        spinner.visibility = View.GONE
                        tvStatus.text = getString(R.string.update_error)
                        delay(2000)
                    }
                    cardUpdate.animate()
                        .alpha(0f)
                        .setDuration(400)
                        .withEndAction {
                            cardUpdate.visibility = View.GONE
                            isCheckingUpdate = false
                        }
                        .start()
                }

                is UpdateManager.CheckResult.UpdateAvailable -> {
                    spinner.visibility = View.GONE
                    progressIndicator.visibility = View.VISIBLE
                    progressIndicator.isIndeterminate = true
                    tvStatus.text = getString(R.string.update_available, result.info.versionName)

                    val downloadResult = UpdateManager.downloadApk(
                        context = this@MainActivity,
                        downloadUrl = result.info.downloadUrl,
                        targetFileName = result.info.assetName
                    ) { percent, bytesRead, totalBytes ->
                        if (percent >= 0) {
                            progressIndicator.isIndeterminate = false
                            progressIndicator.progress = percent
                            tvStatus.text = getString(R.string.update_downloading, result.info.versionName, percent)
                        } else {
                            progressIndicator.isIndeterminate = true
                            val mb = bytesRead / (1024 * 1024.0)
                            tvStatus.text = String.format("Downloading %.1f MB…", mb)
                        }
                    }

                    if (downloadResult.isSuccess) {
                        val apkFile = downloadResult.getOrThrow()
                        downloadedUpdateFile = apkFile
                        progressIndicator.visibility = View.GONE
                        icon.visibility = View.VISIBLE
                        icon.setImageResource(R.drawable.ic_system_update)
                        tvStatus.text = getString(R.string.update_downloaded, result.info.versionName)
                        btnAction.visibility = View.VISIBLE
                        btnAction.text = getString(R.string.update_btn_install)
                        btnDismiss.visibility = View.VISIBLE

                        btnAction.setOnClickListener {
                            UpdateManager.installApk(this@MainActivity, apkFile)
                        }
                        btnDismiss.setOnClickListener {
                            cardUpdate.visibility = View.GONE
                        }

                        // Auto prompt install immediately as requested by user
                        UpdateManager.installApk(this@MainActivity, apkFile)
                    } else {
                        progressIndicator.visibility = View.GONE
                        tvStatus.text = "Download failed. Tap to retry."
                        btnAction.visibility = View.VISIBLE
                        btnAction.text = "Retry"
                        btnAction.setOnClickListener {
                            isCheckingUpdate = false
                            startUpdateCheck(manualTrigger = true)
                        }
                        btnDismiss.visibility = View.VISIBLE
                        btnDismiss.setOnClickListener {
                            cardUpdate.visibility = View.GONE
                        }
                    }
                    isCheckingUpdate = false
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionBadge()
    }

    fun updatePermissionBadge() {
        val allRequiredGranted = areAllRequiredPermissionsGranted()
        val permTab = tabLayout.getTabAt(1) ?: return
        if (!allRequiredGranted) {
            val badge = permTab.orCreateBadge
            badge.isVisible = true
        } else {
            permTab.removeBadge()
        }
    }

    private fun areAllRequiredPermissionsGranted(): Boolean {
        val runtimePermissions = buildList {
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

        val runtimeGranted = runtimePermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        val notificationAccessGranted = NotificationManagerCompat
            .getEnabledListenerPackages(this)
            .contains(packageName)
        val overlayGranted = Settings.canDrawOverlays(this)

        return runtimeGranted && notificationAccessGranted && overlayGranted
    }

    private class MainPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 3

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> SettingsFragment()
                1 -> PermissionsFragment()
                2 -> ToolsFragment()
                else -> throw IllegalArgumentException("Invalid tab index: $position")
            }
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
        }
    }
}
