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

    /** Announce through Bluetooth earphones when connected. */
    val bluetoothEnabled: Boolean
        get() = prefs.getBoolean(KEY_BLUETOOTH, true)

    /** Announce through the loudspeaker when Bluetooth is unavailable. */
    val speakerEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPEAKER, true)

    /** Listen for Answer/Reject/etc. during cellular calls. */
    val voiceCommandsEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_COMMANDS, true)

    /** Announce WhatsApp voice/video calls (no voice commands). */
    val whatsappEnabled: Boolean
        get() = prefs.getBoolean(KEY_WHATSAPP, true)

    /** Repeat the announcement while the phone keeps ringing. */
    val repeatEnabled: Boolean
        get() = prefs.getBoolean(KEY_REPEAT, true)

    /** Seconds between repeated announcements (3..30). */
    val repeatIntervalSec: Int
        get() = prefs.getInt(KEY_REPEAT_INTERVAL, 8).coerceIn(3, 30)

    /** Maximum number of announcements per call (1..10). */
    val maxRepeats: Int
        get() = prefs.getInt(KEY_MAX_REPEATS, 3).coerceIn(1, 10)

    /** TTS speech rate in percent (50..200; 100 = normal). */
    val speechRatePct: Int
        get() = prefs.getInt(KEY_SPEECH_RATE, 100).coerceIn(50, 200)

    /** Percent of the normal ring volume to keep while speaking (0..80). */
    val duckLevelPct: Int
        get() = prefs.getInt(KEY_DUCK_LEVEL, 20).coerceIn(0, 80)

    companion object {
        const val KEY_BLUETOOTH = "pref_bluetooth_enabled"
        const val KEY_SPEAKER = "pref_speaker_enabled"
        const val KEY_VOICE_COMMANDS = "pref_voice_commands_enabled"
        const val KEY_WHATSAPP = "pref_whatsapp_enabled"
        const val KEY_REPEAT = "pref_repeat_enabled"
        const val KEY_REPEAT_INTERVAL = "pref_repeat_interval_sec"
        const val KEY_MAX_REPEATS = "pref_max_repeats"
        const val KEY_SPEECH_RATE = "pref_speech_rate_pct"
        const val KEY_DUCK_LEVEL = "pref_duck_level_pct"
    }
}
