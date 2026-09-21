package com.callhandler.service.debug

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.callhandler.service.core.CallHandlerService
import com.callhandler.service.identity.TruecallerParsedInfo
import com.callhandler.service.identity.TruecallerParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Accessibility observer for Truecaller's incoming-call overlay.
 *
 * Scoped to `com.truecaller`. When Truecaller renders its overlay screen
 * (`acsContainer` / `NeoPACSActivity`), this service parses the node hierarchy
 * using [TruecallerParser] to extract caller identity, and forwards it to
 * [CallHandlerService] / [com.callhandler.service.identity.CallerIdentityManager].
 *
 * This service NEVER answers/rejects calls or plays audio itself.
 */
class TruecallerAccessibilityService : AccessibilityService() {

    @Volatile
    var observing = true
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            packageNames = arrayOf(TRUECALLER_PACKAGE)
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 50
        } ?: serviceInfo

        log("SERVICE", "AccessibilityService connected")
        Log.i(TAG, "TruecallerAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !observing) return
        if (event.packageName?.toString() != TRUECALLER_PACKAGE) return

        val eventTypeName = AccessibilityEvent.eventTypeToString(event.eventType)
        log("EVENT", "type=$eventTypeName pkg=${event.packageName} class=${event.className}")

        // 1. Traverse and log the source node
        val source = event.source
        var parsed: TruecallerParsedInfo? = null
        if (source != null) {
            log("EVENT", "--- Source node tree ---")
            traverseNode(source, depth = 0)

            // Parse for caller identity
            parsed = TruecallerParser.parse(source)
            if (parsed != null) {
                handleParsedCallerInfo(parsed)
            }
            source.recycle()
        }

        // 2. Only check rootInActiveWindow if source didn't yield a caller-ID result
        if (parsed == null) {
            val root = try { rootInActiveWindow } catch (_: Exception) { null }
            if (root != null) {
                parsed = TruecallerParser.parse(root)
                if (parsed != null) {
                    handleParsedCallerInfo(parsed)
                }
                root.recycle()
            }
        }
    }

    private fun handleParsedCallerInfo(info: TruecallerParsedInfo) {
        val announcement = info.announcementName ?: return
        val now = SystemClock.uptimeMillis()

        // Debounce: Suppress duplicate events for the same caller within DEBOUNCE_WINDOW_MS
        if (announcement == lastParsedAnnouncement && (now - lastParsedTimestamp < DEBOUNCE_WINDOW_MS)) {
            Log.d(TAG, "Suppressed duplicate Truecaller caller-ID: '$announcement'")
            return
        }

        lastParsedAnnouncement = announcement
        lastParsedTimestamp = now

        _lastParsedCallerInfo.value = info
        log("CALLER_ID", "TRUECALLER OVERLAY MATCH -> $announcement")
        Log.i(TAG, "Parsed Truecaller info: $announcement")

        // Notify active listener or service
        onCallerInfoDetected?.invoke(info)

        // Forward to CallHandlerService if running
        runCatching {
            val intent = Intent(this, CallHandlerService::class.java).apply {
                action = CallHandlerService.ACTION_TRUECALLER_UPDATE
                putExtra(CallHandlerService.EXTRA_CALLER_NAME, announcement)
            }
            startService(intent)
        }
    }

    override fun onInterrupt() {
        log("SERVICE", "AccessibilityService interrupted")
        Log.w(TAG, "onInterrupt")
    }

    override fun onDestroy() {
        instance = null
        log("SERVICE", "AccessibilityService destroyed")
        Log.i(TAG, "TruecallerAccessibilityService destroyed")
        super.onDestroy()
    }

    // -------------------------------------------------- public control API

    fun startObserving() {
        observing = true
        log("CONTROL", "Observation STARTED")
        Log.i(TAG, "Observation started")
    }

    fun stopObserving() {
        observing = false
        log("CONTROL", "Observation STOPPED")
        Log.i(TAG, "Observation stopped")
    }

    /**
     * Inspect all currently interactive windows right now for Truecaller caller information.
     */
    fun inspectCurrentWindowsForCaller(): TruecallerParsedInfo? {
        val windowList = try { windows } catch (_: Exception) { null }
        if (windowList != null) {
            for (window in windowList) {
                val root = window.root ?: continue
                val pkg = root.packageName?.toString()
                if (pkg == TRUECALLER_PACKAGE) {
                    val parsed = TruecallerParser.parse(root)
                    root.recycle()
                    if (parsed != null) return parsed
                } else {
                    root.recycle()
                }
            }
        }

        val activeRoot = try { rootInActiveWindow } catch (_: Exception) { null }
        if (activeRoot != null) {
            val pkg = activeRoot.packageName?.toString()
            if (pkg == TRUECALLER_PACKAGE) {
                val parsed = TruecallerParser.parse(activeRoot)
                activeRoot.recycle()
                if (parsed != null) return parsed
            } else {
                activeRoot.recycle()
            }
        }

        return null
    }

    /**
     * Immediately dump all currently visible accessibility windows to logs.
     */
    fun dumpCurrentWindows() {
        log("DUMP", "=== DUMP CURRENT WINDOWS ===")
        Log.i(TAG, "Dumping current windows")

        val windowList = try {
            this.windows
        } catch (e: Exception) {
            log("DUMP", "ERROR: Cannot retrieve windows: ${e.message}")
            Log.e(TAG, "Cannot retrieve windows", e)
            return
        }

        if (windowList.isNullOrEmpty()) {
            log("DUMP", "No windows available")
            return
        }

        log("DUMP", "Total windows: ${windowList.size}")

        for ((i, window) in windowList.withIndex()) {
            val typeName = when (window.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION -> "APPLICATION"
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "INPUT_METHOD"
                AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM"
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "ACCESSIBILITY_OVERLAY"
                else -> "UNKNOWN(${window.type})"
            }

            val bounds = Rect()
            window.getBoundsInScreen(bounds)

            log("DUMP", "--- Window[$i] id=${window.id} type=$typeName " +
                    "layer=${window.layer} focused=${window.isFocused} " +
                    "active=${window.isActive} bounds=$bounds")

            val root = window.root
            if (root != null) {
                val pkg = root.packageName?.toString()
                log("DUMP", "  rootPackage=$pkg")

                if (pkg == TRUECALLER_PACKAGE) {
                    log("DUMP", "  *** TRUECALLER WINDOW FOUND — traversing ***")
                    traverseNode(root, depth = 1)
                    val parsed = TruecallerParser.parse(root)
                    if (parsed != null) {
                        log("DUMP", "  >>> PARSED RESULT: name='${parsed.name}', number='${parsed.phoneNumber}', label='${parsed.label}', spam=${parsed.isSpam}")
                    }
                } else {
                    log("DUMP", "  (skipping non-Truecaller window: $pkg)")
                }
                root.recycle()
            } else {
                log("DUMP", "  root=null")
            }
        }

        log("DUMP", "=== DUMP COMPLETE ===")
    }

    // -------------------------------------------------- node traversal

    private fun traverseNode(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > MAX_DEPTH) {
            log("NODE", "${indent(depth)}... (max depth reached)")
            return
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val sb = StringBuilder()
        sb.append(indent(depth))
        sb.append("class=${node.className}")

        if (!node.text.isNullOrEmpty()) {
            sb.append(" text=\"${node.text}\"")
        }
        if (!node.contentDescription.isNullOrEmpty()) {
            sb.append(" desc=\"${node.contentDescription}\"")
        }
        if (!node.viewIdResourceName.isNullOrEmpty()) {
            sb.append(" id=${node.viewIdResourceName}")
        }

        sb.append(" bounds=$bounds")
        sb.append(" visible=${node.isVisibleToUser}")
        sb.append(" click=${node.isClickable}")
        sb.append(" enabled=${node.isEnabled}")
        sb.append(" focus=${node.isFocusable}")
        sb.append(" children=${node.childCount}")

        log("NODE", sb.toString())
        Log.d(TAG, sb.toString())

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, depth + 1)
            child.recycle()
        }
    }

    private fun indent(depth: Int): String = "  ".repeat(depth)

    private fun log(tag: String, message: String) {
        DebugLogStore.log(tag, message)
    }

    companion object {
        private const val TAG = "TruecallerA11y"
        const val TRUECALLER_PACKAGE = "com.truecaller"
        private const val MAX_DEPTH = 30
        private const val DEBOUNCE_WINDOW_MS = 4000L

        @Volatile
        private var lastParsedAnnouncement: String? = null
        @Volatile
        private var lastParsedTimestamp: Long = 0L

        fun resetDeduplication() {
            lastParsedAnnouncement = null
            lastParsedTimestamp = 0L
        }

        @Volatile
        var instance: TruecallerAccessibilityService? = null
            private set

        val isServiceConnected: Boolean
            get() = instance != null

        private val _lastParsedCallerInfo = MutableStateFlow<TruecallerParsedInfo?>(null)
        val lastParsedCallerInfo: StateFlow<TruecallerParsedInfo?> = _lastParsedCallerInfo

        @Volatile
        var onCallerInfoDetected: ((TruecallerParsedInfo) -> Unit)? = null
    }
}
