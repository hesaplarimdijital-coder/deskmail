package com.deskmail.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The database on the SQLite of the test's Android: what is stored comes back, and what belongs together goes together. */
@RunWith(AndroidJUnit4::class)
class DeskMailDatabaseTest {

    private lateinit var db: DeskMailDatabase

    @Before
    fun open() {
        db = DeskMailDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun anAccountComesBackAsItWasStored() = runBlocking {
        val id = db.accounts().insert(testAccount("deniz@gmail.example", name = "Gmail"))
        val stored = db.accounts().byId(id)
        assertNotNull(stored)
        assertEquals(testAccount("deniz@gmail.example", name = "Gmail").copy(id = id), stored)
        assertEquals(listOf(id), db.accounts().observeAll().first().map { it.id })
    }

    @Test
    fun accountsStandInTheUsersOrder() = runBlocking {
        val second = db.accounts().insert(testAccount("b@example.org", position = 1))
        val first = db.accounts().insert(testAccount("a@example.org", position = 0))
        assertEquals(listOf(first, second), db.accounts().all().map { it.id })
    }

    @Test
    fun anAccountTakesItsCredentialAndItsFoldersWithIt() = runBlocking {
        val id = db.accounts().insert(testAccount("deniz@gmail.example"))
        val other = db.accounts().insert(testAccount("deniz@outlook.example"))
        db.accounts().saveCredential(CredentialEntity(id, "password", byteArrayOf(1, 2, 3), byteArrayOf(4, 5), updatedAt = 1))
        db.folders().insert(testFolder(id, "INBOX", role = "Inbox", position = 0))
        db.folders().insert(testFolder(id, "[Gmail]/Tüm Postalar", role = "All", position = 1))
        db.folders().insert(testFolder(other, "INBOX", role = "Inbox", position = 0))
        assertEquals(listOf("INBOX", "[Gmail]/Tüm Postalar"), db.folders().ofAccount(id).map { it.path })
        assertArrayEquals(byteArrayOf(4, 5), db.accounts().credential(id)!!.secret)

        // a new secret takes the old one's place
        db.accounts().saveCredential(CredentialEntity(id, "password", byteArrayOf(9), byteArrayOf(8, 7), updatedAt = 2))
        assertArrayEquals(byteArrayOf(8, 7), db.accounts().credential(id)!!.secret)

        db.accounts().delete(id)
        assertTrue(db.folders().ofAccount(id).isEmpty())
        assertNull(db.accounts().credential(id))
        // the other account is untouched
        assertEquals(1, db.folders().ofAccount(other).size)
    }

    @Test
    fun aFolderIsListedOncePerAccount() {
        val id = runBlocking { db.accounts().insert(testAccount("deniz@gmail.example")) }
        runBlocking { db.folders().insert(testFolder(id, "INBOX")) }
        assertThrows(Exception::class.java) {
            runBlocking { db.folders().insert(testFolder(id, "INBOX")) }
        }
    }

    @Test
    fun aFolderWithoutAnAccountIsRefused() {
        assertThrows(Exception::class.java) {
            runBlocking { db.folders().insert(testFolder(accountId = 404, path = "INBOX")) }
        }
    }
}

fun testAccount(address: String, name: String = "Hesap", position: Int = 0) = AccountEntity(
    address = address,
    name = name,
    senderName = "Deniz Aksoy",
    provider = "",
    imapHost = "imap.example.org",
    imapPort = 993,
    imapSecurity = "Tls",
    smtpHost = "smtp.example.org",
    smtpPort = 465,
    smtpSecurity = "Tls",
    username = address,
    authKind = "password",
    syncDays = 30,
    notifyMode = "manual",
    signature = "",
    state = "ok",
    stateDetail = "",
    position = position,
    createdAt = 1_000,
)

fun testFolder(accountId: Long, path: String, role: String = "Other", position: Int = 0) = FolderEntity(
    accountId = accountId,
    path = path,
    delimiter = "/",
    role = role,
    roleFixed = false,
    selectable = true,
    position = position,
    uidValidity = 0,
    uidNext = 0,
    highestModSeq = 0,
    unread = 0,
    total = 0,
    syncedAt = 0,
)
