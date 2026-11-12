package com.deskmail.ui.mail

import com.deskmail.demo.DemoMail
import com.deskmail.mail.MailView
import com.deskmail.ui.Routes
import com.deskmail.ui.mailView
import com.deskmail.ui.navBadges
import desktopkit.components.TableSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** What the lists show, without drawing them. */
class MailListLogicTest {

    private val mail = DemoMail(Instant.parse("2026-11-12T09:30:00Z"))

    @Test
    fun theUnifiedInboxHoldsTheInboxesOfEveryAccountNewestFirst() {
        val inbox = mail.messages(MailView.Inbox)
        assertEquals(setOf(11L, 21L), inbox.map { it.folderId }.toSet())
        assertEquals(inbox.sortedByDescending { it.sentAt }, inbox)
    }

    @Test
    fun gmailsAllMailDoesNotCountTwice() {
        // every Gmail message is in "All Mail" once more
        assertTrue(mail.messages(MailView.Folder(24)).any { it.unread })
        val unread = mail.messages(MailView.Unread)
        assertEquals(unread.size, unread.map { it.subject to it.sentAt }.toSet().size)
        assertTrue(unread.none { it.folderId == 24L })
        // nor does what is in Spam
        assertTrue(unread.none { it.folderId == 15L })
    }

    @Test
    fun readingAMessageTakesItOffTheBadges() {
        val before = navBadges(mail.folders)
        val first = mail.messages(MailView.Folder(11)).first { it.unread }
        mail.setUnread(first.id, false)
        val after = navBadges(mail.folders)
        assertEquals(before.getValue(Routes.INBOX).toInt() - 1, after.getValue(Routes.INBOX).toInt())
        assertEquals(before.getValue(Routes.folder(11)).toInt() - 1, after.getValue(Routes.folder(11)).toInt())
        assertEquals(before.getValue(Routes.UNREAD).toInt() - 1, after.getValue(Routes.UNREAD).toInt())
    }

    @Test
    fun filtersNarrowTogether() {
        val inbox = mail.messages(MailView.Inbox)
        assertEquals(inbox, filterMessages(inbox, emptySet()))
        val unread = filterMessages(inbox, setOf(MailFilter.Unread))
        assertTrue(unread.isNotEmpty() && unread.all { it.unread })
        val both = filterMessages(inbox, setOf(MailFilter.Unread, MailFilter.Attachments))
        assertTrue(both.isNotEmpty() && both.all { it.unread && it.attachments.isNotEmpty() })
        assertTrue(both.size < unread.size)
    }

    @Test
    fun theTableSortsByItsColumns() {
        val inbox = mail.messages(MailView.Inbox)
        val oldestFirst = sortMessages(inbox, TableSort(MessageColumns.DATE, ascending = true))
        assertEquals(inbox.map { it.sentAt }.sorted(), oldestFirst.map { it.sentAt })
        val bySender = sortMessages(inbox, TableSort(MessageColumns.FROM, ascending = true))
        assertEquals(bySender.map { it.fromName.lowercase() }.sorted(), bySender.map { it.fromName.lowercase() })
        val unreadFirst = sortMessages(inbox, TableSort(MessageColumns.UNREAD, ascending = true))
        assertTrue(unreadFirst.first().unread)
        assertFalse(unreadFirst.last().unread)
    }

    @Test
    fun routesNameTheirMail() {
        assertEquals(MailView.Inbox, mailView(Routes.INBOX))
        assertEquals(MailView.Folder(17), mailView(Routes.folder(17)))
        assertNull(mailView(Routes.SETTINGS))
        assertNull(mailView("folder/x"))
    }

    @Test
    fun aPlainTextMessageIsEscapedIntoThePage() {
        val page = MessageDocument.build(html = null, text = "a < b && \"c\" > d\n<script>alert(1)</script>")
        assertTrue(page.contains("a &lt; b &amp;&amp; &quot;c&quot; &gt; d"))
        assertFalse(page.contains("<script>"))
        assertTrue(page.contains("class=\"plain\""))
    }

    @Test
    fun thePageLetsNothingInFromTheNetworkUnlessImagesAreAllowed() {
        val blocked = MessageDocument.build(html = "<p>x</p>", text = "x")
        assertTrue(blocked.contains("default-src 'none'"))
        assertTrue(blocked.contains("img-src data: cid:\""))
        assertFalse(blocked.contains("https:"))
        val allowed = MessageDocument.build(html = "<p>x</p>", text = "x", remoteImages = true)
        assertTrue(allowed.contains("img-src data: cid: https:\""))
        assertTrue(allowed.contains("default-src 'none'"))
    }
}
