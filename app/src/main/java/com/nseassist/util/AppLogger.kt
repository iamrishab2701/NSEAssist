package com.nseassist.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app session logger. Captures network calls, navigation events, and key
 * app actions into an in-memory buffer viewable on the Log Viewer screen.
 *
 * Everything also forwards to Android logcat so it still appears in AS Logcat.
 * Buffer auto-trims at MAX_ENTRIES to avoid memory pressure.
 */
object AppLogger {

    enum class Level(val label: String) { D("D"), I("I"), W("W"), E("E") }

    data class Entry(
        val id: Long,
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String,
    )

    private const val MAX_ENTRIES = 3_000
    private val counter = java.util.concurrent.atomic.AtomicLong(0)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(level: Level, tag: String, message: String) {
        // Forward to Android logcat
        when (level) {
            Level.D -> Log.d(tag, message)
            Level.I -> Log.i(tag, message)
            Level.W -> Log.w(tag, message)
            Level.E -> Log.e(tag, message)
        }
        val entry = Entry(counter.incrementAndGet(), System.currentTimeMillis(), level, tag, message)
        val current = _entries.value
        _entries.value = if (current.size >= MAX_ENTRIES) current.drop(1) + entry else current + entry
    }

    fun d(tag: String, msg: String) = log(Level.D, tag, msg)
    fun i(tag: String, msg: String) = log(Level.I, tag, msg)
    fun w(tag: String, msg: String) = log(Level.W, tag, msg)
    fun e(tag: String, msg: String) = log(Level.E, tag, msg)

    fun clear() { _entries.value = emptyList() }

    fun export(): String = buildString {
        _entries.value.forEach { e ->
            appendLine("${timeFmt.format(Date(e.timestamp))} ${e.level.label}/${e.tag}: ${e.message}")
        }
    }
}
