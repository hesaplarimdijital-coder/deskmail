package com.deskmail.core.mail

import java.io.Closeable

/**
 * A folder the server tells news of as it happens, on a connection of the watch's own.
 *
 * One thread waits ([await]); others end the wait, to have it begun anew ([refresh]) or for good
 * ([close]). What the server says meanwhile reaches whoever asked for the watch.
 */
interface FolderWatch : Closeable {
    /**
     * Waits on the folder until another thread ends the wait with [refresh]; calling this again
     * begins the wait anew. Blocks for as long as that takes.
     *
     * @throws MailException when the connection is lost, or the watch was closed.
     */
    fun await()

    /**
     * Ends the wait and asks the server for a sign of life. A server ends a wait that is left
     * alone for half an hour, and a line that went dead says nothing: begun anew in time, the
     * wait goes on, and a dead line is found out. Blocks until the server has answered or is
     * given up on.
     *
     * @throws MailException when the server no longer answers: the watch is of no use any more.
     */
    fun refresh()

    /**
     * Ends the watch and its connection; whoever waits on it finds out. It can take a moment (a
     * server that is being asked something is waited for): not for the main thread.
     */
    override fun close()
}
