package com.deskmail.ui.mail

import com.deskmail.mail.MailMessage

/**
 * A list of messages as its rows: one for each conversation where conversations are kept together
 * ([group]), one for each message otherwise ([single]). A conversation's row is its newest message
 * in the list; it is unread while any of them is, starred while any is, and says how many it stands
 * for ([MailMessage.threadCount]). [members] holds what each such row stands for, by the row's id:
 * what is done to the row from the list (archive, delete, move, read) is done to all of them.
 */
class Threads(val rows: List<MailMessage>, val members: Map<Long, List<Long>>) {

    /** The messages the row [id] stands for: itself, or the conversation's in the list. */
    fun of(id: Long): List<Long> = members[id] ?: listOf(id)

    companion object {
        fun single(messages: List<MailMessage>) = Threads(messages, emptyMap())

        /** [messages] (newest first) with each conversation as one row, where its newest message stands. */
        fun group(messages: List<MailMessage>): Threads {
            val byThread = LinkedHashMap<String, MutableList<MailMessage>>()
            for (message in messages) byThread.getOrPut(message.thread.ifEmpty { "#" + message.id }) { ArrayList() } += message
            val members = HashMap<Long, List<Long>>()
            val rows = byThread.values.map { conversation ->
                val newest = conversation.first()
                if (conversation.size == 1) {
                    newest
                } else {
                    members[newest.id] = conversation.map { it.id }
                    newest.copy(
                        threadCount = conversation.size,
                        unread = conversation.any { it.unread },
                        starred = conversation.any { it.starred },
                        attachmentCount = conversation.sumOf { it.attachmentCount },
                    )
                }
            }
            return Threads(rows, members)
        }
    }
}
