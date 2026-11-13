package com.deskmail.watch

import android.content.Context
import android.os.PowerManager

/**
 * Keeps the device awake while something is done about news from a server, and only then: the
 * waiting itself needs no such thing (a sleeping device is woken by what arrives on the line).
 *
 * Two holds. [stir] is taken the moment a server says something, on the thread that heard it, and
 * lets go by itself: it bridges the moment until the work that follows has its own. [hold] and
 * [release] are that work's, counted; a hold that is never released lets go by itself as well, so
 * that no fault here keeps a device awake.
 */
class Awake(context: Context) {
    private val power = context.applicationContext.getSystemService(PowerManager::class.java)
    private val bridge = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "deskmail:watch-stir").apply { setReferenceCounted(false) }
    private val work = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "deskmail:watch-work").apply { setReferenceCounted(false) }
    private var holders = 0

    /** A server said something: awake for the moment it takes to begin doing something about it. */
    fun stir() {
        runCatching { bridge.acquire(STIR_MS) }
    }

    /** Work begins that the device is to stay awake for. */
    @Synchronized
    fun hold() {
        holders++
        runCatching { work.acquire(LONGEST_MS) }
    }

    /** That work is done. */
    @Synchronized
    fun release() {
        if (holders > 0) holders--
        if (holders == 0) runCatching { if (work.isHeld) work.release() }
    }

    /** Whether work is being kept awake for right now. */
    val held: Boolean
        @Synchronized get() = holders > 0 && work.isHeld

    private companion object {
        const val STIR_MS = 30_000L

        /** No sync of an inbox takes longer; one that does goes on when the device next wakes. */
        const val LONGEST_MS = 3 * 60_000L
    }
}
