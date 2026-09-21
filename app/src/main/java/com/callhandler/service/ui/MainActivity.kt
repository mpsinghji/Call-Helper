package com.callhandler.service.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceFragmentCompat
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.callhandler.service.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

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
