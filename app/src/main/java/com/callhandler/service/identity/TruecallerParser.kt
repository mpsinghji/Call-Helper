package com.callhandler.service.identity

import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

/**
 * Parsed caller data from Truecaller's accessibility tree.
 *
 * @param name         Caller name extracted from Truecaller (e.g. "Sukhvinder Singh")
 * @param phoneNumber  Phone number (e.g. "098171 62931")
 * @param label        Context tag or label (e.g. "Someone you may know", "Spam", "Business")
 * @param isSpam       True if classified as spam/fraud
 */
data class TruecallerParsedInfo(
    val name: String?,
    val phoneNumber: String?,
    val label: String?,
    val isSpam: Boolean
) {
    /**
     * Best display name to announce.
     * Priority: Name -> Spam/Fraud Warning -> Business/Label -> null
     */
    val announcementName: String?
        get() = when {
            isSpam && !name.isNullOrBlank() -> "Spam call from $name"
            isSpam && !label.isNullOrBlank() && !isGenericLabel(label) -> label
            isSpam -> "Spam call"
            !name.isNullOrBlank() -> name
            !label.isNullOrBlank() && !isGenericLabel(label) -> label
            else -> null
        }

    private fun isGenericLabel(lbl: String): Boolean {
        val lower = lbl.lowercase()
        return lower.contains("someone you may know") ||
                lower.contains("identified by truecaller") ||
                lower.contains("first time caller") ||
                lower.contains("likely a business") ||
                lower.contains("community suggestion") ||
                lower.contains("view profile") ||
                lower.contains("& more") ||
                lower.contains("search numbers")
    }
}

/**
 * Robust parser for Truecaller's UI accessibility nodes.
 *
 * Uses confirmed IDs discovered from real device dumps:
 * - `com.truecaller:id/nameOrNumber`: Caller name
 * - `com.truecaller:id/phoneNumber`: Phone number
 * - `com.truecaller:id/label`: Category / label tag
 * - `com.truecaller:id/acsContainer` / `com.truecaller:id/mainContainer`: Screen root
 *
 * Excludes normal Truecaller app screens (onboarding, home tabs, call history, ads)
 * and verifies caller-ID overlay context before accepting names.
 */
object TruecallerParser {

    private const val TAG = "TruecallerParser"

    // Primary Truecaller overlay view IDs
    private const val ID_NAME_OR_NUMBER = "com.truecaller:id/nameOrNumber"
    private const val ID_PHONE_NUMBER = "com.truecaller:id/phoneNumber"
    private const val ID_LABEL = "com.truecaller:id/label"
    private const val ID_TAG = "com.truecaller:id/tag"
    private const val ID_TITLE = "com.truecaller:id/title"
    private const val ID_CALL_TYPE = "com.truecaller:id/callTypeAndTime"

    // Known Truecaller overlay containers
    private val OVERLAY_CONTAINER_IDS = setOf(
        "com.truecaller:id/acsContainer",
        "com.truecaller:id/pacs_root",
        "com.truecaller:id/pacsContainer",
        "com.truecaller:id/popup_container",
        "com.truecaller:id/caller_id_root",
        "com.truecaller:id/caller_id_container",
        "com.truecaller:id/floating_window",
        "com.truecaller:id/after_call_root",
        "com.truecaller:id/mainContainer"
    )

    // IDs characteristic of onboarding, navigation bars, search headers, or ads
    private val EXCLUDED_VIEW_ID_SUBSTRINGS = listOf(
        "onboarding", "wizard", "welcome", "intro", "permission",
        "bottom_navigation", "navigation_bar", "tab_bar", "tab_layout",
        "search_src_text", "search_bar", "search_field",
        "ad_container", "adview", "native_ad", "banner_ad"
    )

    // Screen-level phrases identifying normal in-app UI (not a caller-ID overlay)
    private val EXCLUDED_SCREEN_PHRASES = listOf(
        "protect your family",
        "set up and manage spam protection",
        "stay safe from fraudsters",
        "welcome to truecaller",
        "terms of service",
        "privacy policy",
        "set as default",
        "verify your phone number",
        "verify your number",
        "start free trial",
        "search numbers, names & more",
        "search numbers names and more",
        "identify callers and block spam",
        "create your profile",
        "upgrade to premium"
    )

    // Common action buttons / navigation items to ignore as caller names
    private val IGNORED_TEXTS = setOf(
        "call", "block", "save", "whatsapp", "pay", "change",
        "view profile", "truecaller", "missed call", "incoming call", "outgoing call",
        "decline", "answer", "sms", "message", "copy",
        "get started", "skip", "continue", "next", "accept", "cancel", "done", "close",
        "search", "search numbers", "get truecaller", "upgrade", "premium",
        "verify your identity", "identify callers", "block spam calls", "who called me",
        "calls", "messages", "contacts", "assistant"
    )

    private val SPAM_KEYWORDS = listOf(
        "spam", "fraud", "scam", "telemarketer", "telemarketing",
        "suspicious", "robocall", "harassment", "sales"
    )

    private val HISTORY_TIME_MARKERS = listOf(
        "yesterday", "today", "days ago", "hours ago", "mins ago", "just now"
    )

    /**
     * Inspects an accessibility node hierarchy and extracts Truecaller caller information.
     */
    fun parse(root: AccessibilityNodeInfo?): TruecallerParsedInfo? {
        if (root == null) return null

        // 1. Negative screen verification: Reject onboarding, home, search, history, ads
        if (isNonOverlayScreen(root)) {
            Log.d(TAG, "Rejected screen: detected as normal Truecaller in-app UI / onboarding / history")
            return null
        }

        var extractedName: String? = null
        var extractedNumber: String? = null
        var extractedLabel: String? = null
        var isSpam = false

        // 2. Direct ID lookups (Fastest & most accurate)
        val nameNodes = root.findAccessibilityNodeInfosByViewId(ID_NAME_OR_NUMBER)
        if (!nameNodes.isNullOrEmpty()) {
            for (node in nameNodes) {
                val rawText = node.text?.toString()?.trim() ?: continue
                if (rawText.isBlank() || isIgnored(rawText) || isCarrierOrLocationOrLabel(rawText)) continue

                val (nameCandidate, spamFlag) = extractCleanNameAndSpam(rawText)
                if (spamFlag) isSpam = true

                if (nameCandidate != null && !isIgnored(nameCandidate) && !isCarrierOrLocationOrLabel(nameCandidate)) {
                    if (!isPurePhoneNumber(nameCandidate)) {
                        extractedName = nameCandidate
                        break
                    } else if (extractedNumber == null) {
                        extractedNumber = nameCandidate
                    }
                }
            }
            nameNodes.forEach { it.recycle() }
        }

        // Direct ID lookup for com.truecaller:id/title (Used by modern Truecaller overlays)
        if (extractedName == null) {
            val titleNodes = root.findAccessibilityNodeInfosByViewId(ID_TITLE)
            if (!titleNodes.isNullOrEmpty()) {
                for (node in titleNodes) {
                    val rawText = node.text?.toString()?.trim() ?: continue
                    if (rawText.isBlank() || isIgnored(rawText) || isCarrierOrLocationOrLabel(rawText)) continue

                    val (nameCandidate, spamFlag) = extractCleanNameAndSpam(rawText)
                    if (spamFlag) isSpam = true

                    if (nameCandidate != null && !isIgnored(nameCandidate) && !isCarrierOrLocationOrLabel(nameCandidate)) {
                        if (!isPurePhoneNumber(nameCandidate)) {
                            extractedName = nameCandidate
                            break
                        } else if (extractedNumber == null) {
                            extractedNumber = nameCandidate
                        }
                    }
                }
                titleNodes.forEach { it.recycle() }
            }
        }

        val phoneNodes = root.findAccessibilityNodeInfosByViewId(ID_PHONE_NUMBER)
        if (!phoneNodes.isNullOrEmpty()) {
            for (node in phoneNodes) {
                val text = node.text?.toString()?.trim()
                if (!text.isNullOrBlank()) {
                    extractedNumber = text
                    break
                }
            }
            phoneNodes.forEach { it.recycle() }
        }

        if (extractedNumber == null) {
            extractedNumber = extractPhoneNumberFromCarrierNodes(root)
        }

        val labelNodes = root.findAccessibilityNodeInfosByViewId(ID_LABEL)
        if (!labelNodes.isNullOrEmpty()) {
            for (node in labelNodes) {
                val text = node.text?.toString()?.trim()
                if (!text.isNullOrBlank() && text.length <= 40) {
                    extractedLabel = text
                    if (SPAM_KEYWORDS.any { text.lowercase().contains(it) }) {
                        isSpam = true
                    }
                    break
                }
            }
            labelNodes.forEach { it.recycle() }
        }

        // 3. Fallback traversal: ONLY if direct IDs were absent AND screen has overlay indicators
        if (extractedName == null && hasOverlayContext(root)) {
            val fallback = searchFallback(root)
            if (fallback != null) {
                extractedName = fallback.name
                if (extractedNumber == null) extractedNumber = fallback.phoneNumber
                if (extractedLabel == null) extractedLabel = fallback.label
                if (fallback.isSpam) isSpam = true
            }
        }

        if (extractedName != null || extractedNumber != null || isSpam) {
            Log.i(
                TAG,
                "Truecaller parsed -> name='$extractedName', number='$extractedNumber', label='$extractedLabel', spam=$isSpam"
            )
            return TruecallerParsedInfo(
                name = extractedName,
                phoneNumber = extractedNumber,
                label = extractedLabel,
                isSpam = isSpam
            )
        }

        return null
    }

    /**
     * Checks whether the node tree belongs to an onboarding, home, call history, or settings screen.
     */
    private fun isNonOverlayScreen(root: AccessibilityNodeInfo): Boolean {
        var hasOnboardingIdOrText = false
        var hasNavigationBars = false
        var historyTimestampCount = 0

        fun inspect(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 20 || hasOnboardingIdOrText || hasNavigationBars) return

            val viewId = node.viewIdResourceName?.lowercase().orEmpty()
            val text = node.text?.toString()?.trim()?.lowercase().orEmpty()

            if (EXCLUDED_VIEW_ID_SUBSTRINGS.any { viewId.contains(it) }) {
                if (viewId.contains("onboarding") || viewId.contains("wizard") || viewId.contains("intro")) {
                    hasOnboardingIdOrText = true
                    return
                }
                if (viewId.contains("navigation") || viewId.contains("tab")) {
                    hasNavigationBars = true
                    return
                }
            }

            if (text.isNotBlank()) {
                if (EXCLUDED_SCREEN_PHRASES.any { text.contains(it) }) {
                    hasOnboardingIdOrText = true
                    return
                }
                if (HISTORY_TIME_MARKERS.any { text == it || text.startsWith("$it,") || text.endsWith(" ago") }) {
                    historyTimestampCount++
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                inspect(child, depth + 1)
                child.recycle()
            }
        }

        inspect(root, 0)
        return hasOnboardingIdOrText || hasNavigationBars || historyTimestampCount >= 2
    }

    /**
     * Confirms whether this node tree has evidence of a caller-ID overlay container.
     */
    private fun hasOverlayContext(root: AccessibilityNodeInfo): Boolean {
        for (containerId in OVERLAY_CONTAINER_IDS) {
            val matching = root.findAccessibilityNodeInfosByViewId(containerId)
            if (!matching.isNullOrEmpty()) {
                matching.forEach { it.recycle() }
                return true
            }
        }
        return false
    }

    private fun searchFallback(node: AccessibilityNodeInfo, depth: Int = 0): TruecallerParsedInfo? {
        if (depth > 20) return null

        var foundName: String? = null
        var foundNumber: String? = null
        var foundLabel: String? = null
        var isSpam = false

        val text = node.text?.toString()?.trim()
        val isClickable = node.isClickable
        val className = node.className?.toString().orEmpty()
        val isButton = isClickable || className.contains("Button", ignoreCase = true)

        if (!text.isNullOrBlank() && !isIgnored(text) && !isButton && !isCarrierOrLocationOrLabel(text)) {
            val lower = text.lowercase()
            val (cleanName, spamFlag) = extractCleanNameAndSpam(text)

            if (spamFlag) {
                isSpam = true
                if (cleanName != null) {
                    foundName = cleanName
                } else {
                    foundLabel = text
                }
            } else if (SPAM_KEYWORDS.any { lower == it || lower.startsWith("$it ") } && text.length <= 30) {
                // Short spam badge/tag only (not long sentences like "Protect your family from scams")
                isSpam = true
                foundLabel = text
            } else if (isPurePhoneNumber(text)) {
                foundNumber = text
            } else if (text.length in 2..40 && foundName == null && isValidCallerName(text)) {
                foundName = text
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childResult = searchFallback(child, depth + 1)
            child.recycle()

            if (childResult != null) {
                if (foundName == null && childResult.name != null) foundName = childResult.name
                if (foundNumber == null && childResult.phoneNumber != null) foundNumber = childResult.phoneNumber
                if (foundLabel == null && childResult.label != null) foundLabel = childResult.label
                if (childResult.isSpam) isSpam = true
            }
        }

        if (foundName != null || foundNumber != null || isSpam) {
            return TruecallerParsedInfo(foundName, foundNumber, foundLabel, isSpam)
        }
        return null
    }

    private val KNOWN_NON_NAME_LABELS = setOf(
        "first time caller",
        "likely a business",
        "community suggestion",
        "identified by truecaller",
        "someone you may know",
        "view profile",
        "search numbers",
        "driver",
        "new",
        "delivery",
        "personal",
        "business"
    )

    private val CARRIER_KEYWORDS = listOf(
        "airtel", "jio", "vodafone", "idea", "vi", "bsnl", "mtnl", "verizon", "at&t", "t-mobile"
    )

    private fun isCarrierOrLocationOrLabel(text: String): Boolean {
        val lower = text.lowercase().trim()
        if (KNOWN_NON_NAME_LABELS.contains(lower)) return true
        if (CARRIER_KEYWORDS.any { lower == it || lower.startsWith("$it ") || lower.contains(" $it ") }) return true
        if (lower.contains("india") || lower.contains("state")) return true
        if (lower.contains("·")) return true
        return false
    }

    private fun extractPhoneNumberFromCarrierNodes(root: AccessibilityNodeInfo): String? {
        fun search(node: AccessibilityNodeInfo, depth: Int): String? {
            if (depth > 15) return null
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrBlank() && text.contains("·")) {
                val candidate = text.substringAfter("·").trim()
                if (isPurePhoneNumber(candidate)) return candidate
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val res = search(child, depth + 1)
                child.recycle()
                if (res != null) return res
            }
            return null
        }
        return search(root, 0)
    }

    private fun extractCleanNameAndSpam(text: String): Pair<String?, Boolean> {
        val lower = text.lowercase()
        val prefix = "spam call from "
        return if (lower.startsWith(prefix)) {
            val clean = text.substring(prefix.length).trim()
            Pair(clean.takeIf { it.isNotBlank() }, true)
        } else {
            Pair(text, false)
        }
    }

    private fun isValidCallerName(text: String): Boolean {
        // Disallow sentences with common verbs/marketing words
        val lower = text.lowercase()
        val invalidTokens = listOf(
            "protect", "manage", "ensure", "fraudster", "scam",
            "family", "subscription", "features", "settings",
            "welcome", "yesterday", "today"
        )
        return invalidTokens.none { lower.contains(it) }
    }

    private fun isIgnored(text: String): Boolean {
        val trimmed = text.trim().lowercase()
        return IGNORED_TEXTS.contains(trimmed) ||
                trimmed.startsWith("missed call") ||
                trimmed.startsWith("incoming call") ||
                trimmed.startsWith("outgoing call") ||
                trimmed.length > 50 ||
                trimmed.contains("& more") ||
                trimmed.contains("search numbers") ||
                trimmed.contains("protect your family")
    }

    private fun isPurePhoneNumber(text: String): Boolean {
        // Strip common phone formatting characters: +, -, spaces, parentheses
        val digitsOnly = text.replace("[^0-9]".toRegex(), "")
        return digitsOnly.length >= 7 && (digitsOnly.length.toFloat() / text.length.toFloat()) > 0.6f
    }
}
