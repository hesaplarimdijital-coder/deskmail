package com.deskmail.db

import android.content.Context
import androidx.room3.Room
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Paths

/**
 * An update installs over the version before it: a database as an earlier version left it is
 * brought to the schema of this one (app/schemas), and what it held is still there.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file: File = context.getDatabasePath("migration-test.db")

    // the schemas are read from the module's own directory, where the build writes them
    @get:Rule
    val helper = MigrationTestHelper(
        schemaDirectoryPath = Paths.get("schemas"),
        databasePath = file.toPath(),
        driver = AndroidSQLiteDriver(),
        databaseClass = DeskMailDatabase::class,
    )

    @Before
    fun clean() {
        file.parentFile?.mkdirs()
        file.delete()
    }

    @Test
    fun version1BecomesTheCurrentVersionAndKeepsItsAccounts() = runBlocking {
        val old = helper.createDatabase(1)
        old.prepare(
            "INSERT INTO account (address, name, senderName, provider, imapHost, imapPort, imapSecurity, smtpHost, smtpPort, smtpSecurity, " +
                "username, authKind, syncDays, notifyMode, signature, state, stateDetail, position, createdAt) VALUES " +
                "('deniz@example.org', 'Posta', '', '', 'imap.example.org', 993, 'Tls', 'smtp.example.org', 465, 'Tls', " +
                "'deniz@example.org', 'Password', 30, 'push', '', 'Ok', '', 0, 1000)",
        ).use { it.step() }
        old.prepare(
            "INSERT INTO folder (accountId, path, delimiter, role, roleFixed, selectable, position, uidValidity, uidNext, highestModSeq, unread, total, syncedAt) " +
                "VALUES (1, 'INBOX', '/', 'Inbox', 0, 1, 0, 7, 2, 0, 1, 1, 0)",
        ).use { it.step() }
        old.close()

        val migrated = helper.runMigrationsAndValidate(2, DeskMailDatabase.MIGRATIONS.toList())
        migrated.prepare("SELECT address FROM account").use { statement ->
            assertTrue(statement.step())
            assertEquals("deniz@example.org", statement.getText(0))
        }
        migrated.prepare("SELECT COUNT(*) FROM pending_op").use { statement ->
            assertTrue(statement.step())
            assertEquals(0L, statement.getLong(0))
        }
        migrated.close()

        // and the app's own database opens it, with its migrations, and works on it
        val db = Room.databaseBuilder<DeskMailDatabase>(context, file.name)
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(*DeskMailDatabase.MIGRATIONS)
            .build()
        try {
            assertEquals(listOf("deniz@example.org"), db.accounts().all().map { it.address })
            val inbox = db.folders().ofAccount(1).single()
            val message = db.messages().insert(listOf(testMessage(1, inbox.id, uid = 1))).single()
            assertEquals(1L, db.pending().mark(message, seen = true, flagged = null, now = 5))
            assertEquals(listOf("Seen"), db.pending().due(1, now = 5).map { it.kind })
        } finally {
            db.close()
        }
    }

    /** Every version has its schema in the repository: a migration is tested from the real thing. */
    @Test
    fun everyVersionsSchemaIsKept() {
        val schemas = File("schemas", DeskMailDatabase::class.java.name).list().orEmpty().toSet()
        assertEquals((1..2).map { "$it.json" }.toSet(), schemas)
    }
}
