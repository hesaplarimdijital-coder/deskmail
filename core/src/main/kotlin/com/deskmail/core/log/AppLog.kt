package com.deskmail.core.log

import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The app's log: what it did and what went wrong, as lines of text the user can read on the log
 * screen and share when something breaks.
 *
 * - The latest [capacity] lines are kept in memory; every line is also handed to [sink] (the file).
 * - Every line is redacted first ([LogRedactor]): passwords and tokens never reach it.
 * - A line reads `2026-11-12 14:32:05.123 I sync: 3 new messages`: the device's local time, the
 *   level (I, W, E), who says it, and what. Further lines of one entry (a stack trace) are indented.
 *
 * Safe to call from any thread.
 */
class AppLog(
    private val capacity: Int = 2000,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val now: () -> Instant = { Instant.now() },
) {
    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Receives every line as it is written, already redacted; the app appends it to the log file. */
    @Volatile
    var sink: ((String) -> Unit)? = null

    fun info(tag: String, message: String) = write('I', tag, message, null)

    fun warn(tag: String, message: String, error: Throwable? = null) = write('W', tag, message, error)

    fun error(tag: String, message: String, error: Throwable? = null) = write('E', tag, message, error)

    /** The lines in memory, oldest first. */
    fun lines(): List<String> = synchronized(lock) { lines.toList() }

    /** Puts [earlier] (the end of the log file of earlier runs) before what was written since the start. */
    fun preload(earlier: List<String>) {
        synchronized(lock) {
            val room = (capacity - lines.size).coerceAtLeast(0)
            earlier.takeLast(room).asReversed().forEach { lines.addFirst(it) }
        }
        notifyListeners()
    }

    fun clear() {
        synchronized(lock) { lines.clear() }
        notifyListeners()
    }

    /** Calls [listener] after every change, on the thread that made it; returns what removes it again. */
    fun addListener(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    private fun write(level: Char, tag: String, message: String, error: Throwable?) {
        val text = if (error == null) message else message + "\n" + describe(error)
        val body = LogRedactor.redact(text).lines()
        val head = TIME.format(now().atZone(zone())) + " " + level + " " + tag + ": " + body.first()
        val entry = listOf(head) + body.drop(1).map { CONTINUATION + it }
        synchronized(lock) {
            entry.forEach { lines.addLast(it) }
            while (lines.size > capacity) lines.removeFirst()
        }
        sink?.let { write -> entry.forEach(write) }
        notifyListeners()
    }

    private fun notifyListeners() = listeners.forEach { it() }

    companion object {
        /** What further lines of one entry start with. */
        const val CONTINUATION = "    "

        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

        /** How many frames of a stack trace are written; the cause chain is followed. */
        private const val FRAMES = 16

        /** The level of a line as [write] wrote it ('I', 'W', 'E'), or null for a continuation or foreign line. */
        fun levelOf(line: String): Char? {
            // "yyyy-MM-dd HH:mm:ss.SSS L tag: …"
            if (line.length < 26 || line[23] != ' ' || line[25] != ' ' || !line[0].isDigit()) return null
            return line[24].takeIf { it == 'I' || it == 'W' || it == 'E' }
        }

        /** An exception as log lines: what it is, where it was thrown, and what caused it. */
        fun describe(error: Throwable): String {
            val out = StringWriter()
            PrintWriter(out).use { writer ->
                var current: Throwable? = error
                var depth = 0
                while (current != null && depth < 4) {
                    writer.println((if (depth == 0) "" else "caused by: ") + current)
                    current.stackTrace.take(FRAMES).forEach { writer.println("  at $it") }
                    if (current.stackTrace.size > FRAMES) writer.println("  … ${current.stackTrace.size - FRAMES} more")
                    current = current.cause?.takeIf { it !== current }
                    depth++
                }
            }
            return out.toString().trimEnd()
        }
    }
}
