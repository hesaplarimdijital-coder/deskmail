package com.deskmail.db

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * An update installs over the version before it: a database as an earlier version left it is
 * brought to this version's schema, and what it held is still there.
 *
 * The old database is made from that version's own schema as it is kept in app/schemas (the file
 * Room wrote when the version was built). Opening it with the app's migrations runs them, and
 * Room then checks every table against what this version expects: a migration that leaves a
 * column, an index or a key out fails here.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file: File = context.getDatabasePath("migration-test.db")

    @Before
    fun clean() {
        file.parentFile?.mkdirs()
        listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
    }

    /** Runs one statement that answers nothing. */
    private suspend fun SQLiteConnection.runSql(sql: String) {
        prepare(sql).use { it.step() }
    }

    /** Makes the database as [version] of the app would have, and lets [fill] put something into it. */
    private suspend fun create(version: Int, fill: suspend (SQLiteConnection) -> Unit) {
        val schema = JSONObject(File(SCHEMAS, "$version.json").readText()).getJSONObject("database")
        assertEquals(version, schema.getInt("version"))
        val connection = AndroidSQLiteDriver().open(file.path)
        try {
            val entities = schema.getJSONArray("entities")
            for (at in 0 until entities.length()) {
                val entity = entities.getJSONObject(at)
                val table = entity.getString("tableName")
                connection.runSql(entity.getString("createSql").replace(TABLE_NAME, table))
                val indices = entity.optJSONArray("indices") ?: JSONArray()
                for (index in 0 until indices.length()) {
                    connection.runSql(indices.getJSONObject(index).getString("createSql").replace(TABLE_NAME, table))
                }
            }
            // the table Room knows its own schema by
            val setup = schema.getJSONArray("setupQueries")
            for (at in 0 until setup.length()) connection.runSql(setup.getString(at))
            connection.runSql("PRAGMA user_version = $version")
            fill(connection)
        } finally {
            connection.close()
        }
    }

    private fun open(vararg migrations: androidx.room3.migration.Migration): DeskMailDatabase =
        Room.databaseBuilder<DeskMailDatabase>(context, file.name)
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(*migrations)
            .build()

    private suspend fun anAccountWithItsInbox(connection: SQLiteConnection) {
        connection.runSql(
            "INSERT INTO account (address, name, senderName, provider, imapHost, imapPort, imapSecurity, smtpHost, smtpPort, smtpSecurity, " +
                "username, authKind, syncDays, notifyMode, signature, state, stateDetail, position, createdAt) VALUES " +
                "('deniz@example.org', 'Posta', '', '', 'imap.example.org', 993, 'Tls', 'smtp.example.org', 465, 'Tls', " +
                "'deniz@example.org', 'Password', 30, 'push', '', 'Ok', '', 0, 1000)",
        )
        connection.runSql(
            "INSERT INTO folder (accountId, path, delimiter, role, roleFixed, selectable, position, uidValidity, uidNext, highestModSeq, unread, total, syncedAt) " +
                "VALUES (1, 'INBOX', '/', 'Inbox', 0, 1, 0, 7, 2, 0, 1, 1, 0)",
        )
    }

    @Test
    fun version1BecomesTheCurrentVersionAndKeepsWhatItHeld() = runBlocking {
        create(1) { anAccountWithItsInbox(it) }

        val db = open(*DeskMailDatabase.MIGRATIONS)
        try {
            // opening ran the migrations and checked the result; what was there is there
            assertEquals(listOf("deniz@example.org"), db.accounts().all().map { it.address })
            val inbox = db.folders().ofAccount(1).single()
            assertEquals("INBOX", inbox.path)
            assertEquals(7L, inbox.uidValidity)
            // and what the new version adds works on it
            val message = db.messages().insert(listOf(testMessage(1, inbox.id, uid = 1))).single()
            assertEquals(1L, db.pending().mark(message, seen = true, flagged = null, now = 5))
            assertEquals(listOf("Seen"), db.pending().due(1, now = 5).map { it.kind })
        } finally {
            db.close()
        }
    }

    /** The check this test relies on is really made: without its migrations an old database does not open. */
    @Test
    fun anOldDatabaseDoesNotOpenWithoutItsMigrations() {
        runBlocking { create(1) { anAccountWithItsInbox(it) } }
        val db = open()
        try {
            assertThrows(Exception::class.java) { runBlocking { db.accounts().all() } }
        } finally {
            db.close()
        }
    }

    /** Every version's schema is in the repository, and there is a step from each to the next. */
    @Test
    fun everyVersionHasItsSchemaAndItsMigration() {
        val kept = SCHEMAS.list().orEmpty().toSet()
        // the build writes this version's schema before the tests run: the highest number is the current one
        val current = kept.maxOf { it.removeSuffix(".json").toInt() }
        assertTrue(current >= 2)
        assertEquals((1..current).map { "$it.json" }.toSet(), kept)
        assertEquals(current, JSONObject(File(SCHEMAS, "$current.json").readText()).getJSONObject("database").getInt("version"))
        assertEquals((1 until current).map { it to it + 1 }, DeskMailDatabase.MIGRATIONS.map { it.startVersion to it.endVersion })
    }

    private companion object {
        /** Where the build writes the schemas: the tests run in the module's directory. */
        val SCHEMAS = File("schemas", DeskMailDatabase::class.java.name)

        const val TABLE_NAME = "\${TABLE_NAME}"
    }
}
