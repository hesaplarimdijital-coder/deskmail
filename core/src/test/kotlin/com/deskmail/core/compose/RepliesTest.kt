package com.deskmail.core.compose

import com.deskmail.core.mail.MailAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class RepliesTest {

    private val ayse = MailAddress("Ayşe Yılmaz", "ayse@example.org")
    private val deniz = MailAddress("Deniz Aksoy", "deniz@example.org")
    private val elif = MailAddress("Elif Şahin", "elif@example.org")
    private val mehmet = MailAddress("Mehmet Demir", "mehmet@example.org")
    private val mine = setOf("deniz@example.org", "deniz@gmail.example")

    @Test
    fun aReplysSubjectSaysSoOnce() {
        assertEquals("Re: Cumartesi kahvaltı?", Replies.replySubject("Cumartesi kahvaltı?"))
        assertEquals("Re: Cumartesi kahvaltı?", Replies.replySubject("Re: Cumartesi kahvaltı?"))
        assertEquals("RE: Plan", Replies.replySubject(" RE: Plan "))
        assertEquals("Ynt: Plan", Replies.replySubject("Ynt: Plan"))
        assertEquals("YNT: Plan", Replies.replySubject("YNT: Plan"))
        assertEquals("Re[2]: Plan", Replies.replySubject("Re[2]: Plan"))
        // a word that only begins like one is no reply
        assertEquals("Re: Rehber hakkında", Replies.replySubject("Rehber hakkında"))
        assertEquals("Re:", Replies.replySubject(""))
    }

    @Test
    fun aForwardedSubjectSaysSoOnce() {
        assertEquals("Fwd: Plan", Replies.forwardSubject("Plan"))
        assertEquals("Fwd: Plan", Replies.forwardSubject("Fwd: Plan"))
        assertEquals("FW: Plan", Replies.forwardSubject("FW: Plan"))
        assertEquals("İlt: Plan", Replies.forwardSubject("İlt: Plan"))
        assertEquals("Fwd: Re: Plan", Replies.forwardSubject("Re: Plan"))
    }

    @Test
    fun aReplyGoesToTheSender() {
        val reply = Replies.recipients(from = listOf(ayse), replyTo = emptyList(), to = listOf(deniz, elif), cc = listOf(mehmet), mine = mine, all = false)
        assertEquals(Replies.Recipients(listOf(ayse), emptyList()), reply)
    }

    @Test
    fun aReplyToEverybodyLeavesOutTheWriterHimself() {
        val reply = Replies.recipients(from = listOf(ayse), replyTo = emptyList(), to = listOf(deniz, elif), cc = listOf(mehmet, ayse), mine = mine, all = true)
        assertEquals(Replies.Recipients(listOf(ayse), listOf(elif, mehmet)), reply)
    }

    @Test
    fun whereTheSenderAsksForAnotherAddressTheReplyGoesThere() {
        val list = MailAddress("Fotoğraf Kulübü", "liste@kulup.example")
        val reply = Replies.recipients(from = listOf(ayse), replyTo = listOf(list), to = listOf(deniz), cc = emptyList(), mine = mine, all = false)
        assertEquals(listOf(list), reply.to)
    }

    /** Answering one's own message is writing again to those it went to. */
    @Test
    fun aReplyToOnesOwnMessageGoesToItsRecipients() {
        val own = MailAddress("Deniz", "DENIZ@example.org")
        val reply = Replies.recipients(from = listOf(own), replyTo = emptyList(), to = listOf(ayse, elif), cc = listOf(mehmet), mine = mine, all = false)
        assertEquals(Replies.Recipients(listOf(ayse, elif), emptyList()), reply)
        val all = Replies.recipients(from = listOf(own), replyTo = emptyList(), to = listOf(ayse, elif), cc = listOf(mehmet, deniz), mine = mine, all = true)
        assertEquals(Replies.Recipients(listOf(ayse, elif), listOf(mehmet)), all)
    }

    @Test
    fun theQuotedTextIsMarkedLineByLine() {
        val quoted = Replies.quote("Selam Deniz,\r\n\r\nKahvaltıya ne dersin?\n> Önceki soru\n\n", "12 Kasım 2026 09:05 tarihinde Ayşe Yılmaz yazdı:")
        assertEquals(
            "\n\n12 Kasım 2026 09:05 tarihinde Ayşe Yılmaz yazdı:\n> Selam Deniz,\n>\n> Kahvaltıya ne dersin?\n>> Önceki soru\n",
            quoted,
        )
    }

    @Test
    fun aForwardedMessageKeepsItsTextAsItIs() {
        val forwarded = Replies.forward("Plan ekte.\r\n\r\nMehmet\n", "---------- İletilen ileti ----------", listOf("Kimden: Mehmet Demir <mehmet@example.org>", "Konu: Plan"))
        assertEquals(
            "\n\n---------- İletilen ileti ----------\nKimden: Mehmet Demir <mehmet@example.org>\nKonu: Plan\n\nPlan ekte.\n\nMehmet\n",
            forwarded,
        )
    }

    @Test
    fun theThreadIsCarriedOnInTheReferences() {
        assertEquals("<a@x> <b@x> <c@x>", Replies.references("<a@x>\r\n <b@x>", "<c@x>"))
        assertEquals("<c@x>", Replies.references("", "<c@x>"))
        // what is no message id, and what is there twice, is left out
        assertEquals("<a@x> <c@x>", Replies.references("<a@x> bozuk <a@x>", "<c@x>"))
        // a long thread keeps its first message and its latest
        val long = (1..40).joinToString(" ") { "<$it@x>" }
        val kept = Replies.references(long, "<son@x>").split(" ")
        assertEquals(20, kept.size)
        assertEquals("<1@x>", kept.first())
        assertEquals(listOf("<39@x>", "<40@x>", "<son@x>"), kept.takeLast(3))
    }
}
