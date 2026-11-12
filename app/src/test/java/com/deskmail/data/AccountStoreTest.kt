package com.deskmail.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskmail.core.log.AppLog
import com.deskmail.core.net.Credentials
import com.deskmail.core.net.Security
import com.deskmail.core.net.ServerAddress
import com.deskmail.core.provider.AuthKind
import com.deskmail.core.provider.ProviderTable
import com.deskmail.db.DeskMailDatabase
import com.deskmail.db.testFolder
import com.deskmail.mail.AccountState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The accounts and their passwords: stored sealed, opened only for signing in. */
@RunWith(AndroidJUnit4::class)
class AccountStoreTest {

    private lateinit var db: DeskMailDatabase
    private lateinit var accounts: AccountStore
    private val log = AppLog()
    private val gmail = ProviderTable.Gmail.settingsFor("deniz@gmail.com")

    @Before
    fun open() {
        db = DeskMailDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        accounts = AccountStore(db, testBox(), log, now = { 5_000 })
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun anAccountIsKeptWithItsServersAndItsSealedPassword() = runBlocking {
        val id = accounts.add("deniz@gmail.com", "Gmail", "gmail", gmail, "abcdefghijklmnop")

        val stored = accounts.byId(id)!!
        assertEquals("deniz@gmail.com", stored.address)
        assertEquals("Gmail", stored.name)
        assertEquals("gmail", stored.provider)
        assertEquals(ServerAddress("imap.gmail.com", 993, Security.Tls), stored.imapServer())
        assertEquals(ServerAddress("smtp.gmail.com", 465, Security.Tls), stored.smtpServer())
        assertEquals(AuthKind.AppPassword, stored.auth())
        assertEquals(AccountState.Ok, stored.accountState())
        assertEquals(30, stored.syncDays)
        assertEquals(5_000, stored.createdAt)

        // what is stored is not the password
        val credential = db.accounts().credential(id)!!
        assertEquals(AccountStore.KIND_PASSWORD, credential.kind)
        assertFalse(String(credential.secret, Charsets.ISO_8859_1).contains("abcdefghijklmnop"))
        // and it opens for signing in
        val credentials = accounts.credentials(stored) as Credentials.Password
        assertEquals("deniz@gmail.com", credentials.username)
        assertEquals("abcdefghijklmnop", credentials.password)
        // nothing of it went into the log
        assertTrue(log.lines().none { it.contains("abcdefghijklmnop") })
        assertTrue(log.lines().any { it.contains("account $id added: deniz@gmail.com at imap.gmail.com") })
    }

    @Test
    fun accountsStandInTheOrderTheyWereAdded() = runBlocking {
        val first = accounts.add("deniz@gmail.com", "Gmail", "gmail", gmail, "bir")
        val second = accounts.add("deniz@example.org", "example.org", "", gmail.copy(username = "deniz"), "iki")
        assertEquals(listOf(first, second), accounts.all().map { it.id })
        assertEquals(listOf(0, 1), accounts.all().map { it.position })
        // an address is one account, however it is written
        assertEquals(first, accounts.byAddress(" Deniz@Gmail.com ")?.id)
        assertNull(accounts.byAddress("baska@gmail.com"))
    }

    @Test
    fun aNewPasswordTakesTheOldOnesPlace() = runBlocking {
        val id = accounts.add("deniz@gmail.com", "Gmail", "gmail", gmail, "eski")
        accounts.savePassword(id, "yeni")
        assertEquals("yeni", (accounts.credentials(accounts.byId(id)!!) as Credentials.Password).password)
    }

    @Test
    fun aPasswordThisDeviceCannotOpenIsNone() = runBlocking {
        val id = accounts.add("deniz@gmail.com", "Gmail", "gmail", gmail, "parola")
        val other = accounts.add("deniz@example.org", "example.org", "", gmail, "baska")
        val account = accounts.byId(id)!!

        // the keystore's key is gone (another box), or the row belongs to another account
        assertNull(AccountStore(db, testBox(seed = 9), log).credentials(account))
        val moved = db.accounts().credential(other)!!
        db.accounts().saveCredential(com.deskmail.db.CredentialEntity(id, moved.kind, moved.iv, moved.secret, moved.updatedAt))
        assertNull(accounts.credentials(account))
        // why is in the log, without anything secret
        assertTrue(log.lines().any { it.contains("account $id: the stored sign-in could not be opened") })
    }

    @Test
    fun howAnAccountIsDoingIsWrittenOnIt() = runBlocking {
        val id = accounts.add("deniz@gmail.com", "Gmail", "gmail", gmail, "parola")
        accounts.setState(id, AccountState.NeedsLogin, "[AUTHENTICATIONFAILED] Invalid credentials")
        val stored = accounts.byId(id)!!
        assertEquals(AccountState.NeedsLogin, stored.accountState())
        assertEquals("[AUTHENTICATIONFAILED] Invalid credentials", stored.stateDetail)
        accounts.setState(id, AccountState.Ok)
        assertEquals("", accounts.byId(id)!!.stateDetail)
    }

    @Test
    fun anAccountLeavesWithEverythingOfIts() = runBlocking {
        val id = accounts.add("deniz@gmail.com", "Gmail", "gmail", gmail, "parola")
        val kept = accounts.add("deniz@example.org", "example.org", "", gmail, "baska")
        db.folders().insert(testFolder(id, "INBOX", role = "Inbox"))

        accounts.remove(id)

        assertNull(accounts.byId(id))
        assertNull(db.accounts().credential(id))
        assertTrue(db.folders().ofAccount(id).isEmpty())
        assertNotNull(accounts.byId(kept))
        assertNotNull(accounts.credentials(accounts.byId(kept)!!))
    }
}
