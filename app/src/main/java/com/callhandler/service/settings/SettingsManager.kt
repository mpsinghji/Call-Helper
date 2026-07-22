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

    companion object {
        const val KEY_VOICE_COMMANDS = "pref_voice_commands_enabled"
    }
}
