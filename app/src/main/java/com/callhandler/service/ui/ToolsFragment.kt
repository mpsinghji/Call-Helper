package com.callhandler.service.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.callhandler.service.R
import com.callhandler.service.audio.AnnouncementManager
import com.callhandler.service.audio.AudioRouter
import com.callhandler.service.debug.DebugConsoleActivity
import com.callhandler.service.settings.SettingsManager
import kotlinx.coroutines.launch

class ToolsFragment : Fragment() {

    private var testAnnouncer: AnnouncementManager? = null
    private var testAudioRouter: AudioRouter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_tools, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<Button>(R.id.testBluetoothButton).setOnClickListener {
            testBluetoothAnnouncement()
        }

        view.findViewById<Button>(R.id.debugConsoleButton).setOnClickListener {
            startActivity(Intent(requireContext(), DebugConsoleActivity::class.java))
        }

        val versionText = view.findViewById<android.widget.TextView>(R.id.aboutVersionText)
        try {
            val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            val vName = pInfo.versionName ?: "1.0"
            @Suppress("DEPRECATION")
            val vCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                pInfo.versionCode.toLong()
            }
            versionText.text = getString(R.string.about_app_version, vName, vCode)
        } catch (_: Exception) {
            versionText.text = getString(R.string.about_app_version, "1.0", 1)
        }
    }

    override fun onDestroyView() {
        testAnnouncer?.shutdown()
        testAnnouncer = null
        testAudioRouter?.cleanupAudio()
        testAudioRouter = null
        super.onDestroyView()
    }

    private fun testBluetoothAnnouncement() {
        val context = context ?: return
        val router = testAudioRouter ?: AudioRouter(context).also { testAudioRouter = it }

        if (!router.isBluetoothAudioConnected()) {
            Toast.makeText(
                context,
                getString(R.string.test_bt_not_connected),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val settings = SettingsManager(context)
        val tts = testAnnouncer ?: AnnouncementManager(context, settings).also { testAnnouncer = it }

        Toast.makeText(context, getString(R.string.test_bt_starting), Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch {
            val scoOk = router.connectBluetoothAudio()
            if (!scoOk) {
                Toast.makeText(
                    context,
                    getString(R.string.test_bt_sco_failed),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            try {
                val shouldPlay = router.prepareForAnnouncement(
                    settings.announcementVolumePct
                )
                if (shouldPlay) {
                    tts.announce(getString(R.string.test_bt_announcement_text))
                } else {
                    Toast.makeText(
                        context,
                        getString(R.string.test_bt_volume_zero),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                router.finishAnnouncement()
                router.disconnectBluetoothAudio()
            }

            Toast.makeText(
                context,
                getString(R.string.test_bt_done),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
