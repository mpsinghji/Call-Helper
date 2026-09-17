package com.callhandler.service.voice

import android.util.Log

/** Commands recognized for regular cellular calls. */
enum class VoiceCommand {
    SPEAKER,
    ANSWER,
    REJECT,
    SILENT;

    companion object {
        private const val TAG = "VoiceCommand"

        // --- SPEAKER keywords & aliases ---
        private val SPEAKER_PHRASES = setOf(
            "speaker phone", "speakerphone", "hands free", "handsfree",
            "loud speaker", "loudspeaker", "on speaker", "put on speaker"
        )
        private val SPEAKER_WORDS = setOf(
            "speaker", "speeker", "speakerphone", "loudspeaker", "handsfree",
            "speker", "speakr", "speakar"
        )

        // --- ANSWER keywords & aliases ---
        private val ANSWER_PHRASES = setOf(
            "pick up", "pick it up", "take call", "take the call",
            "receive call", "attend call", "answer call", "answer it",
            "answer phone", "answer the call", "answer now", "answer up",
            "accept call", "and sir", "and ser", "and sar", "sun set"
        )
        private val ANSWER_WORDS = setOf(
            "answer", "accept", "pickup", "yes", "yeah", "yep", "okay", "ok", "hello",
            // Phonetic & partial ASR variations
            "ansar", "anser", "ansa", "ansir", "answered", "answering", "answera",
            "ancer", "ensor", "ansel", "anserr", "anther", "uncer",
            "sunset", "unsaid", "onset", "unset", "anset", "unser", "hansa",
            "answ", "ans"
        )

        // --- REJECT keywords & aliases ---
        private val REJECT_PHRASES = setOf(
            "hang up", "reject call", "decline call", "cut call", "cut the call",
            "dont pick", "do not pick", "end call"
        )
        private val REJECT_WORDS = setOf(
            "reject", "decline", "ignore", "dismiss", "hangup",
            "rejected", "rejecting", "declined", "declining", "rejekt", "declin"
        )

        // --- SILENT keywords & aliases ---
        private val SILENT_PHRASES = setOf(
            "shut up", "be quiet", "keep quiet", "mute call", "silent call"
        )
        private val SILENT_WORDS = setOf(
            "silent", "silence", "mute", "quiet",
            "silenced", "silencing", "muted", "muting", "sylent", "silint"
        )

        /**
         * Fast command matcher that parses recognized speech into a [VoiceCommand].
         *
         * Pipeline:
         * 1. Normalize text (lowercase, strip punctuation, collapse whitespace)
         * 2. Check multi-word phrase aliases (priority: SPEAKER > ANSWER > REJECT > SILENT)
         * 3. Check individual whole words against alias sets
         * 4. Conservative Levenshtein fuzzy match (distance <= 1 on words >= 5 chars)
         *
         * Never uses broad substring rules like contains("re").
         */
        fun fromPhrase(raw: String): VoiceCommand? {
            val phrase = normalize(raw)
            if (phrase.isEmpty()) return null

            val words = phrase.split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty()) return null

            // 1. SPEAKER check (phrases + words + fuzzy)
            if (matchesSpeaker(phrase, words)) {
                Log.d(TAG, "Matched SPEAKER from: '$raw'")
                return SPEAKER
            }

            // 2. ANSWER check (phrases + words + fuzzy)
            if (matchesAnswer(phrase, words)) {
                Log.d(TAG, "Matched ANSWER from: '$raw'")
                return ANSWER
            }

            // 3. REJECT check (phrases + words + fuzzy)
            if (matchesReject(phrase, words)) {
                Log.d(TAG, "Matched REJECT from: '$raw'")
                return REJECT
            }

            // 4. SILENT check (phrases + words + fuzzy)
            if (matchesSilent(phrase, words)) {
                Log.d(TAG, "Matched SILENT from: '$raw'")
                return SILENT
            }

            Log.d(TAG, "Unknown phrase: '$phrase' (raw: '$raw')")
            return null
        }

        private fun normalize(raw: String): String {
            return raw.lowercase()
                .replace("[^a-z0-9\\s]".toRegex(), " ")
                .replace("\\s+".toRegex(), " ")
                .trim()
        }

        private fun matchesSpeaker(phrase: String, words: List<String>): Boolean {
            if (SPEAKER_PHRASES.any { containsWholePhrase(phrase, it) }) return true
            if (words.any { it in SPEAKER_WORDS }) return true
            return words.any { isFuzzyMatch(it, "speaker") || isFuzzyMatch(it, "loudspeaker") }
        }

        private fun matchesAnswer(phrase: String, words: List<String>): Boolean {
            if (ANSWER_PHRASES.any { containsWholePhrase(phrase, it) }) return true
            if (words.any { it in ANSWER_WORDS }) return true
            return words.any { isFuzzyMatch(it, "answer") || isFuzzyMatch(it, "accept") }
        }

        private fun matchesReject(phrase: String, words: List<String>): Boolean {
            if (REJECT_PHRASES.any { containsWholePhrase(phrase, it) }) return true
            if (words.any { it in REJECT_WORDS }) return true
            return words.any { isFuzzyMatch(it, "reject") || isFuzzyMatch(it, "decline") }
        }

        private fun matchesSilent(phrase: String, words: List<String>): Boolean {
            if (SILENT_PHRASES.any { containsWholePhrase(phrase, it) }) return true
            if (words.any { it in SILENT_WORDS }) return true
            return words.any { isFuzzyMatch(it, "silent") || isFuzzyMatch(it, "silence") || isFuzzyMatch(it, "quiet") }
        }

        /** Checks if [phrase] occurs in [text] as a complete whole-word phrase. */
        private fun containsWholePhrase(text: String, phrase: String): Boolean {
            if (text == phrase) return true
            val index = text.indexOf(phrase)
            if (index < 0) return false
            val startOk = index == 0 || text[index - 1] == ' '
            val endOk = index + phrase.length == text.length || text[index + phrase.length] == ' '
            return startOk && endOk
        }

        /** Conservative Levenshtein distance <= maxDistance for words of length >= 5. */
        private fun isFuzzyMatch(word: String, target: String, maxDistance: Int = 1): Boolean {
            if (word == target) return true
            if (Math.abs(word.length - target.length) > maxDistance) return false
            if (word.length < 5 || target.length < 5) return false
            return levenshtein(word, target) <= maxDistance
        }

        private fun levenshtein(s: String, t: String): Int {
            val m = s.length
            val n = t.length
            val d = Array(m + 1) { IntArray(n + 1) }

            for (i in 0..m) d[i][0] = i
            for (j in 0..n) d[0][j] = j

            for (i in 1..m) {
                for (j in 1..n) {
                    val cost = if (s[i - 1] == t[j - 1]) 0 else 1
                    d[i][j] = minOf(
                        d[i - 1][j] + 1,       // deletion
                        d[i][j - 1] + 1,       // insertion
                        d[i - 1][j - 1] + cost // substitution
                    )
                }
            }
            return d[m][n]
        }
    }
}
