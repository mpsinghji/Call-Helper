package com.callhandler.service.voice

/** Commands recognized for regular cellular calls. */
enum class VoiceCommand {
    ANSWER, REJECT, SILENT, SPEAKER, VOLUME_UP, VOLUME_DOWN;

    companion object {
        /**
         * Maps a recognized phrase to a command. Checks multi-word phrases
         * first so "volume up" is not swallowed by a bare keyword match.
         */
        fun fromPhrase(raw: String): VoiceCommand? {
            val phrase = raw.lowercase().trim()
            return when {
                // volume first — most specific
                phrase.containsAny("volume up", "louder", "increase volume") -> VOLUME_UP
                phrase.containsAny("volume down", "quieter", "lower volume",
                    "decrease volume") -> VOLUME_DOWN

                phrase.containsAny("speaker", "hands-free", "hands free",
                    "loudspeaker") -> SPEAKER

                phrase.containsAny("answer", "accept", "pick up", "pickup",
                    "take the call") -> ANSWER

                phrase.containsAny("reject", "decline", "ignore", "hang up",
                    "dismiss") -> REJECT

                phrase.containsAny("silent", "silence", "mute", "quiet",
                    "shut up") -> SILENT

                else -> null
            }
        }

        private fun String.containsAny(vararg keys: String): Boolean =
            keys.any { this.contains(it) }
    }
}
