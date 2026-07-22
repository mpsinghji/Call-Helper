package com.callhandler.service.voice

import android.util.Log

/** Commands recognized for regular cellular calls. */
enum class VoiceCommand {
    ANSWER, REJECT, SILENT, SPEAKER;

    companion object {
        private const val TAG = "VoiceCommand"

        /**
         * Maps a recognized phrase to a command.
         */
        fun fromPhrase(raw: String): VoiceCommand? {
            val phrase = raw.lowercase()
                .replace("[^a-z ]".toRegex(), "")
                .trim()

            return when {
                phrase.containsAny("speaker", "hands free", "handsfree",
                    "loudspeaker") -> SPEAKER

                phrase.containsAny("answer", "accept", "pick up", "pickup",
                    "take the call", "ansar", "anser", "ant", "enter", "and sir") -> ANSWER

                phrase.containsAny("reject", "decline", "ignore", "hang up",
                    "dismiss") -> REJECT

                phrase.containsAny("silent", "silence", "mute", "quiet",
                    "shut up") -> SILENT

                else -> {
                    if (phrase.isNotEmpty()) {
                        Log.d(TAG, "Unknown phrase: '$phrase'")
                    }
                    null
                }
            }
        }

        private fun String.containsAny(vararg keys: String): Boolean =
            keys.any { this.contains(it) }
    }
}
