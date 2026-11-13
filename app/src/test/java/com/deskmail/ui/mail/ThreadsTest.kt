package com.deskmail.ui.mail

import com.deskmail.mail.MailMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** A list's rows by conversation: the newest message of each stands for all of them. */
class ThreadsTest {

    private fun message(id: Long, thread: String, unread: Boolean = false, starred: Boolean = false, files: Int = 0) = MailMessage(
        id = id,
        accountId = 1,
        folderId = 11,
        fromName = "Ayşe",
        fromAddress = "ayse@posta.example",
        to = "Deniz",
        subject = "konu $id",
        sentAt = Instant.ofEpochSecond(1_000 - id),
        unread = unread,
        starred = starred,
        attachmentCount = files,
        thread = thread,
    )

    @Test
    fun aConversationIsOneRowWhereItsNewestStands() {
        // newest first, as the lists hand them out
        val messages = listOf(
            message(1, "1 <a>"),
            message(2, "1 <b>", starred = true),
            message(3, "1 <a>", unread = true, files = 2),
            message(4, ""),
            message(5, "1 <a>"),
            message(6, ""),
        )

        val threads = Threads.group(messages)

        assertEquals(listOf(1L, 2L, 4L, 6L), threads.rows.map { it.id })
        val first = threads.rows.first()
        assertEquals(3, first.threadCount)
        // unread while any of them is; the files of all of them
        assertTrue(first.unread)
        assertEquals(2, first.attachmentCount)
        assertEquals(listOf(1L, 3L, 5L), threads.of(1))
        // a conversation of one, and messages without a conversation, stand for themselves
        assertEquals(listOf(2L), threads.of(2))
        assertEquals(1, threads.rows[1].threadCount)
        assertEquals(listOf(4L), threads.of(4))
    }

    @Test
    fun withoutConversationsEveryMessageIsARow() {
        val messages = listOf(message(1, "1 <a>"), message(2, "1 <a>"))
        val threads = Threads.single(messages)
        assertEquals(messages, threads.rows)
        assertEquals(listOf(2L), threads.of(2))
    }
}
