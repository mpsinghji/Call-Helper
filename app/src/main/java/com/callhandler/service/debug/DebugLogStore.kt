package com.callhandler.service.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory circular buffer for debug log entries.
 *
 * Thread-safe singleton accessible from both [TruecallerAccessibilityService]
 * and [DebugConsoleActivity]. Entries are observable via [logs] StateFlow.
 */
object DebugLogStore {

    private const val MAX_ENTRIES = 500

    data class Entry(
        val timestamp: Long = System.currentTimeMillis(),
        val tag: String,
        val message: String,
        val isRaw: Boolean = false
    ) {
        private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        fun formatted(): String = if (isRaw || tag.isBlank()) message else "[${fmt.format(Date(timestamp))}] $tag: $message"
    }

    private val _logs = MutableStateFlow<List<Entry>>(emptyList())
    /** Observable log entries for UI. Most recent entry is last. */
    val logs: StateFlow<List<Entry>> = _logs

    /**
     * Append a log entry. Safe to call from any thread.
     * Drops oldest entries when [MAX_ENTRIES] is exceeded.
     */
    fun log(tag: String, message: String) {
        append(Entry(tag = tag, message = message, isRaw = false))
    }

    /**
     * Append a pre-formatted raw block (such as timeline dividers and summaries).
     */
    fun logRaw(message: String) {
        append(Entry(tag = "", message = message, isRaw = true))
    }

    private fun append(entry: Entry) {
        _logs.update { current ->
            val updated = current + entry
            if (updated.size > MAX_ENTRIES) {
                updated.drop(updated.size - MAX_ENTRIES)
            } else {
                updated
            }
        }
    }

    /** Clear all log entries. */
    fun clear() {
        _logs.value = emptyList()
    }
}
