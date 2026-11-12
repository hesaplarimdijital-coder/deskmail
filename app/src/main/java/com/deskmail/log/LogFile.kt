package com.deskmail.log

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.Writer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The log on disk, in the app's own files: `deskmail.log`, and `deskmail.1.log` beside it, the
 * file before, once the first has grown past [maxBytes]. So the log of last night is still there
 * in the morning, and never takes more than twice [maxBytes].
 *
 * Every line is written and flushed on one background thread, in the order it was handed over; a
 * line that cannot be written is dropped (a full disk must not take the app down with it).
 */
class LogFile(private val directory: File, private val maxBytes: Long = 512 * 1024) {
    private val current = File(directory, "deskmail.log")
    private val previous = File(directory, "deskmail.1.log")

    // one thread at most, gone again after a few idle seconds
    private val io = ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
        Thread(task, "deskmail-log").apply { isDaemon = true }
    }
    private var writer: Writer? = null
    private var written = 0L

    /** Appends [line] in the background. */
    fun append(line: String) {
        io.execute { write(line) }
    }

    /** Hands [receive] the last [maxLines] lines on disk (earlier runs), in the background. */
    fun loadTail(maxLines: Int, receive: (List<String>) -> Unit) {
        io.execute { receive(read().takeLast(maxLines)) }
    }

    /** Waits until everything handed over so far is on disk; for the moments before the process dies. */
    fun flush() {
        try {
            io.submit {}.get(2, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // nothing to do about it here
        }
    }

    /** Writes everything on disk into [target], older file first, after [header]; for sharing. */
    fun exportTo(target: File, header: List<String> = emptyList()) {
        io.submit {
            target.parentFile?.mkdirs()
            target.bufferedWriter().use { out ->
                (header + read()).forEach { line ->
                    out.write(line)
                    out.write("\n")
                }
            }
        }.get(10, TimeUnit.SECONDS)
    }

    /** Deletes the log on disk. */
    fun clear() {
        io.execute {
            close()
            current.delete()
            previous.delete()
        }
    }

    private fun read(): List<String> = try {
        writer?.flush()
        listOf(previous, current).filter { it.isFile }.flatMap { it.readLines() }
    } catch (e: IOException) {
        emptyList()
    }

    private fun write(line: String) {
        try {
            val out = writer ?: open()
            out.write(line)
            out.write("\n")
            out.flush()
            written += line.length + 1
            if (written > maxBytes) rotate()
        } catch (e: IOException) {
            close()
        }
    }

    /** Opens the current file to be added to; what it already holds counts towards its size. */
    private fun open(): Writer {
        directory.mkdirs()
        written = current.length()
        return FileOutputStream(current, true).writer(Charsets.UTF_8).buffered().also { writer = it }
    }

    private fun rotate() {
        close()
        previous.delete()
        current.renameTo(previous)
    }

    private fun close() {
        try {
            writer?.close()
        } catch (e: IOException) {
            // closing a file that cannot be written
        }
        writer = null
        written = 0
    }
}
