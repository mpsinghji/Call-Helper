package com.callhandler.service.voice

import android.util.Log

/** Commands recognized for regular cellular calls. */
enum class VoiceCommand {
    ANSWER,
    REJECT,
    SILENT,
    SPEAKER;

    companion object {
        private const val TAG = "VoiceCommand"

        /** Maps a recognized phrase to a command. */
        fun fromPhrase(raw: String): VoiceCommand? {
            val phrase = raw.lowercase().replace("[^a-z ]".toRegex(), "").trim()

            return when {
                phrase.containsAny("speaker", "hands free", "handsfree", "loudspeaker") -> SPEAKER

                phrase.containsAny(
                    "answer", "answer call", "answer it", "answer phone", "accept", "accept call", "pick up", "pickup", "pick it up", "take call", "take the call", "receive call", "attend call", "yes", "yeah", "yep", "okay", "ok", "hello", "ansar", "anser", "answer", "ansa", "ansir", "answered", "answering", "answer the call", "and sir", "and ser", "and sar", "enter", "ant", "answera", "ancer", "ancer call", "ensor", "ansel", "anserr", "anther", "uncer", "answer up", "answer now") -> ANSWER

                phrase.containsAny("reject", "decline", "ignore", "hang up", "dismiss") -> REJECT

                phrase.containsAny("silent", "silence", "mute", "quiet", "shut up") -> SILENT

                else -> {
                    if (phrase.isNotEmpty()) {
                        Log.d(TAG, "Unknown phrase: '$phrase'")
                    }
                    null
                }
            }
        }

        private fun String.containsAny(vararg keys: String): Boolean = keys.any {
            this.contains(it)
        }
    }
}

