package com.callhandler.service.settings

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Typed accessor over the app's SharedPreferences. Keys mirror
 * res/xml/preferences.xml, so the settings UI and the service always agree.
 */
class SettingsManager(context: Context) {

    private val prefs =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    /** Listen for Answer/Reject/Silent/Speaker during cellular calls. */
    val voiceCommandsEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_COMMANDS, true)

    /** Announce caller ID through Bluetooth earphones when connected. */
    val announcementEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANNOUNCEMENT, true)

    /** TTS speech rate in percent (50..200; 100 = normal). */
    val speechRatePct: Int
        get() = prefs.getInt(KEY_SPEECH_RATE, 100).coerceIn(50, 200)

    /** Announcement volume percent of the VOICE_CALL stream max (100..200). */
    val announcementVolumePct: Int
        get() = prefs.getInt(KEY_ANNOUNCEMENT_VOLUME, 200).coerceIn(100, 200)

    /** Announce VoIP calls (WhatsApp, Instagram, etc.) through Bluetooth. */
    val voipAnnouncementEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOIP_ANNOUNCEMENT, true)

    companion object {
        const val KEY_VOICE_COMMANDS = "pref_voice_commands_enabled"
        const val KEY_ANNOUNCEMENT = "pref_announcement_enabled"
        const val KEY_SPEECH_RATE = "pref_speech_rate_pct"
        const val KEY_ANNOUNCEMENT_VOLUME = "pref_announcement_volume_pct"
        const val KEY_VOIP_ANNOUNCEMENT = "pref_voip_announcement_enabled"
    }
}
