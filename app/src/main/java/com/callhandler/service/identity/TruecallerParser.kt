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
            isSpam -> "Spam call"
            !name.isNullOrBlank() -> name
            !label.isNullOrBlank() && !isGenericLabel(label) -> label
            else -> null
        }

    private fun isGenericLabel(lbl: String): Boolean {
        val lower = lbl.lowercase()
        return lower.contains("someone you may know") ||
                lower.contains("identified by truecaller") ||
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
 * Includes fallback heuristics if IDs change in future Truecaller updates.
 */
object TruecallerParser {

    private const val TAG = "TruecallerParser"

    private const val ID_NAME_OR_NUMBER = "com.truecaller:id/nameOrNumber"
    private const val ID_PHONE_NUMBER = "com.truecaller:id/phoneNumber"
    private const val ID_LABEL = "com.truecaller:id/label"
    private const val ID_TAG = "com.truecaller:id/tag"
    private const val ID_TITLE = "com.truecaller:id/title"
    private const val ID_CALL_TYPE = "com.truecaller:id/callTypeAndTime"

    // Action button text to ignore as names
    private val IGNORED_TEXTS = setOf(
        "call", "block", "save", "whatsapp", "pay", "change",
        "view profile", "truecaller", "missed call", "incoming call",
        "decline", "answer", "sms", "message", "copy",
        // Truecaller UI placeholder / feature text (not caller names)
        "search numbers, names & more",
        "search numbers names and more",
        "search", "get truecaller",
        "verify your identity", "identify callers",
        "block spam calls", "who called me"
    )

    private val SPAM_KEYWORDS = listOf(
        "spam", "fraud", "scam", "telemarketer", "telemarketing",
        "suspicious", "robocall", "harassment", "sales"
    )

    /**
     * Inspects an accessibility node hierarchy and extracts Truecaller caller information.
     */
    fun parse(root: AccessibilityNodeInfo?): TruecallerParsedInfo? {
        if (root == null) return null

        var extractedName: String? = null
        var extractedNumber: String? = null
        var extractedLabel: String? = null
        var isSpam = false

        // 1. Direct ID lookups (Fastest & most accurate)
        val nameNodes = root.findAccessibilityNodeInfosByViewId(ID_NAME_OR_NUMBER)
        if (!nameNodes.isNullOrEmpty()) {
            for (node in nameNodes) {
                val text = node.text?.toString()?.trim()
                if (!text.isNullOrBlank() && !isIgnored(text)) {
                    // Verify it's not just a phone number repeated in the name field
                    if (!isPurePhoneNumber(text)) {
                        extractedName = text
                        break
                    } else if (extractedNumber == null) {
                        extractedNumber = text
                    }
                }
            }
            nameNodes.forEach { it.recycle() }
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

        val labelNodes = root.findAccessibilityNodeInfosByViewId(ID_LABEL)
        if (!labelNodes.isNullOrEmpty()) {
            for (node in labelNodes) {
                val text = node.text?.toString()?.trim()
                if (!text.isNullOrBlank()) {
                    extractedLabel = text
                    if (SPAM_KEYWORDS.any { text.lowercase().contains(it) }) {
                        isSpam = true
                    }
                    break
                }
            }
            labelNodes.forEach { it.recycle() }
        }

        // 2. If name was not found by direct ID, traverse hierarchy as fallback
        if (extractedName == null) {
            val fallback = searchFallback(root)
            if (fallback != null) {
                if (extractedName == null) extractedName = fallback.name
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

    private fun searchFallback(node: AccessibilityNodeInfo, depth: Int = 0): TruecallerParsedInfo? {
        if (depth > 25) return null

        var foundName: String? = null
        var foundNumber: String? = null
        var foundLabel: String? = null
        var isSpam = false

        val text = node.text?.toString()?.trim()
        val viewId = node.viewIdResourceName?.orEmpty()

        if (!text.isNullOrBlank() && !isIgnored(text)) {
            val lower = text.lowercase()
            if (SPAM_KEYWORDS.any { lower.contains(it) }) {
                isSpam = true
                foundLabel = text
            } else if (isPurePhoneNumber(text)) {
                foundNumber = text
            } else if (text.length in 2..50 && foundName == null) {
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

    private fun isIgnored(text: String): Boolean {
        val trimmed = text.trim().lowercase()
        return IGNORED_TEXTS.contains(trimmed) ||
                trimmed.startsWith("missed call") ||
                trimmed.startsWith("incoming call") ||
                trimmed.startsWith("outgoing call") ||
                trimmed.length > 70 ||
                // Truecaller UI patterns (never real caller names)
                trimmed.contains("& more") ||
                trimmed.contains("search numbers")
    }

    private fun isPurePhoneNumber(text: String): Boolean {
        // Strip common phone formatting characters: +, -, spaces, parentheses
        val digitsOnly = text.replace("[^0-9]".toRegex(), "")
        return digitsOnly.length >= 7 && (digitsOnly.length.toFloat() / text.length.toFloat()) > 0.6f
    }
}
