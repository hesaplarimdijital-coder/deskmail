package com.deskmail.ui.settings

import com.deskmail.core.mail.MailException
import com.deskmail.core.net.Security
import com.deskmail.core.net.ServerAddress
import com.deskmail.core.provider.AuthKind
import com.deskmail.core.provider.MailSettings
import com.deskmail.core.provider.ProviderTable
import com.deskmail.mail.AccountActions
import com.deskmail.mail.AccountDraft
import com.deskmail.mail.SetupPlan
import com.deskmail.mail.SetupResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Add an account" step by step, with the servers' answers made up. */
class AccountSetupFormTest {

    /** Answers as the test says; remembers what it was asked. */
    private class Actions(var plan: SetupPlan = SetupPlan.Manual, var result: SetupResult = SetupResult.Done(7)) : AccountActions {
        val asked = ArrayList<String>()
        val drafts = ArrayList<AccountDraft>()

        override suspend fun find(address: String): SetupPlan {
            asked += address
            return plan
        }

        override suspend fun add(draft: AccountDraft): SetupResult {
            drafts += draft
            return result
        }

        override suspend fun reconnect(accountId: Long, password: String): SetupResult = result

        override suspend fun remove(accountId: Long) = Unit
    }

    private val posta = MailSettings(
        ServerAddress("imap.posta.example", 143, Security.StartTls),
        ServerAddress("smtp.posta.example", 587, Security.StartTls),
        username = "deniz",
        auth = AuthKind.Password,
    )

    @Test
    fun aKnownProviderAsksForThePasswordOnly() = runBlocking {
        val gmail = SetupPlan.Found(ProviderTable.Gmail.settingsFor("deniz@gmail.com"), ProviderTable.Gmail)
        val actions = Actions(plan = gmail)
        val form = AccountSetupForm()
        form.address = " deniz@gmail.com "

        form.lookUp(actions)

        assertEquals(AccountSetupForm.Step.Password(gmail), form.step)
        assertNull(form.problem)
        assertFalse(form.busy)

        form.password = "abcd efgh ijkl mnop"
        assertEquals(7L, form.add(actions))
        val draft = actions.drafts.single()
        assertEquals("deniz@gmail.com", draft.address)
        assertSame(ProviderTable.Gmail, draft.provider)
        assertEquals(AuthKind.AppPassword, draft.settings.auth)
        // as typed: what is made of an app password's spaces is not the form's business
        assertEquals("abcd efgh ijkl mnop", draft.password)
    }

    @Test
    fun whatIsNoAddressOrAlreadyThereStaysAtTheAddress() = runBlocking {
        val form = AccountSetupForm()
        form.address = "deniz"

        form.lookUp(Actions(plan = SetupPlan.NotAnAddress))
        assertEquals(AccountSetupForm.Step.Address, form.step)
        assertEquals(AccountSetupForm.Problem.NotAnAddress, form.problem)

        // typing takes the complaint away
        form.edited()
        assertNull(form.problem)

        form.lookUp(Actions(plan = SetupPlan.Duplicate))
        assertEquals(AccountSetupForm.Step.Address, form.step)
        assertEquals(AccountSetupForm.Problem.Duplicate, form.problem)
    }

    @Test
    fun aProviderWithoutItsSignInYetSaysSo() = runBlocking {
        val form = AccountSetupForm()
        form.address = "deniz@outlook.com"

        form.lookUp(Actions(plan = SetupPlan.NotYet(ProviderTable.Outlook)))

        assertEquals(AccountSetupForm.Step.NotYet(ProviderTable.Outlook), form.step)
        // nothing to add from here
        assertNull(form.add(Actions()))
        form.startOver()
        assertEquals(AccountSetupForm.Step.Address, form.step)
        assertEquals("deniz@outlook.com", form.address)
    }

    @Test
    fun anUnknownDomainIsSetUpByHandFromTheUsualNames() = runBlocking {
        val actions = Actions(plan = SetupPlan.Manual)
        val form = AccountSetupForm()
        form.address = "deniz@kendi.example"

        form.lookUp(actions)

        assertEquals(AccountSetupForm.Step.Manual, form.step)
        val servers = form.servers
        assertEquals("imap.kendi.example", servers.imapHost)
        assertEquals("993", servers.imapPort)
        assertEquals(Security.Tls, servers.imapSecurity)
        assertEquals("smtp.kendi.example", servers.smtpHost)
        assertEquals("465", servers.smtpPort)
        assertEquals("deniz@kendi.example", servers.username)

        // another security brings its usual port, unless the port was the user's own
        servers.chooseSmtpSecurity(Security.StartTls)
        assertEquals("587", servers.smtpPort)
        servers.imapPort = "1993"
        servers.chooseImapSecurity(Security.StartTls)
        assertEquals("1993", servers.imapPort)

        servers.imapHost = " Posta.Kendi.Example "
        form.password = "parola"
        assertEquals(7L, form.add(actions))
        val draft = actions.drafts.single()
        assertNull(draft.provider)
        assertEquals(ServerAddress("posta.kendi.example", 1993, Security.StartTls), draft.settings.imap)
        assertEquals(ServerAddress("smtp.kendi.example", 587, Security.StartTls), draft.settings.smtp)
        assertEquals("deniz@kendi.example", draft.settings.username)
        assertEquals(AuthKind.Password, draft.settings.auth)
    }

    @Test
    fun serversThatAreNotCompleteAreNotTried() = runBlocking {
        val actions = Actions(plan = SetupPlan.Manual)
        val form = AccountSetupForm()
        form.address = "deniz@kendi.example"
        form.lookUp(actions)

        for (broken in listOf<(ManualServers) -> Unit>(
            { it.imapHost = "" },
            { it.smtpHost = "smtp kendi example" },
            { it.imapPort = "imap" },
            { it.smtpPort = "70000" },
            { it.username = "  " },
        )) {
            form.servers.fill("deniz@kendi.example", null)
            broken(form.servers)
            assertNull(form.add(actions))
            assertEquals(AccountSetupForm.Problem.Servers, form.problem)
        }
        assertTrue(actions.drafts.isEmpty())
    }

    @Test
    fun foundServersCanBeCorrectedByHand() = runBlocking {
        val form = AccountSetupForm()
        form.address = "deniz@posta.example"
        form.lookUp(Actions(plan = SetupPlan.Found(posta)))

        form.byHand(posta)

        assertEquals(AccountSetupForm.Step.Manual, form.step)
        assertEquals("imap.posta.example", form.servers.imapHost)
        assertEquals("143", form.servers.imapPort)
        assertEquals(Security.StartTls, form.servers.imapSecurity)
        assertEquals("587", form.servers.smtpPort)
        assertEquals("deniz", form.servers.username)
        assertEquals(posta, form.servers.settings())
    }

    @Test
    fun aRefusedSignInStaysWhereItIsAndSaysWhy() = runBlocking {
        val found = SetupPlan.Found(posta)
        val actions = Actions(plan = found, result = SetupResult.Failed(MailException.Kind.Authentication, "NO LOGIN failed"))
        val form = AccountSetupForm()
        form.address = "deniz@posta.example"
        form.lookUp(actions)
        form.password = "yanlis"

        assertNull(form.add(actions))

        assertEquals(AccountSetupForm.Step.Password(found), form.step)
        assertEquals(AccountSetupForm.Problem.Failed(MailException.Kind.Authentication, "NO LOGIN failed"), form.problem)
        assertEquals("yanlis", form.password)
        assertFalse(form.busy)

        actions.result = SetupResult.Duplicate
        assertNull(form.add(actions))
        assertEquals(AccountSetupForm.Problem.Duplicate, form.problem)
    }
}
