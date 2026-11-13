package com.deskmail.db

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.Dispatchers

/**
 * The app's database: the accounts and what is kept of their mail on the device. The mail itself
 * lives on the servers; everything here except the accounts can be fetched again.
 *
 * It runs on the SQLite the device has. The schema of every version is written to app/schemas;
 * from version 1 on, a change of schema comes with a migration ([MIGRATIONS]) and its test, so an
 * update installs over the version before it and the accounts stay.
 */
@Database(
    entities = [
        AccountEntity::class,
        CredentialEntity::class,
        FolderEntity::class,
        MessageEntity::class,
        BodyEntity::class,
        AttachmentEntity::class,
        MessageFts::class,
        PendingOpEntity::class,
        OutboxEntity::class,
        OutboxFileEntity::class,
        DraftRemovalEntity::class,
        SearchHitEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class DeskMailDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao

    abstract fun folders(): FolderDao

    abstract fun messages(): MessageDao

    abstract fun pending(): PendingDao

    abstract fun outbox(): OutboxDao

    companion object {
        /** The database's file among the app's databases. */
        const val FILE = "mail.db"

        /**
         * 0.1.0-alpha.3: what was done on the device and is still to be told to the server
         * (read and unread, the star, moves and removals).
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.runSql(
                    "CREATE TABLE IF NOT EXISTS `pending_op` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`accountId` INTEGER NOT NULL, `messageId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `value` INTEGER NOT NULL, " +
                        "`targetFolderId` INTEGER NOT NULL, `notBefore` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`accountId`) REFERENCES `account`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`messageId`) REFERENCES `message`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                connection.runSql("CREATE INDEX IF NOT EXISTS `index_pending_op_accountId` ON `pending_op` (`accountId`)")
                connection.runSql("CREATE INDEX IF NOT EXISTS `index_pending_op_messageId` ON `pending_op` (`messageId`)")
            }
        }

        /** 0.1.0-alpha.3: the messages that are being written and those waiting to be sent, with their files. */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.runSql(
                    "CREATE TABLE IF NOT EXISTS `outbox` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `accountId` INTEGER NOT NULL, " +
                        "`state` TEXT NOT NULL, `toText` TEXT NOT NULL, `ccText` TEXT NOT NULL, `bccText` TEXT NOT NULL, `subject` TEXT NOT NULL, " +
                        "`body` TEXT NOT NULL, `messageId` TEXT NOT NULL, `inReplyTo` TEXT NOT NULL, `refs` TEXT NOT NULL, `sourceId` INTEGER NOT NULL, " +
                        "`sourceKind` TEXT NOT NULL, `draftFolderId` INTEGER NOT NULL, `draftUid` INTEGER NOT NULL, `sendAfter` INTEGER NOT NULL, " +
                        "`error` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`accountId`) REFERENCES `account`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                connection.runSql("CREATE INDEX IF NOT EXISTS `index_outbox_accountId` ON `outbox` (`accountId`)")
                connection.runSql(
                    "CREATE TABLE IF NOT EXISTS `outbox_file` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `outboxId` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `size` INTEGER NOT NULL, `path` TEXT NOT NULL, " +
                        "FOREIGN KEY(`outboxId`) REFERENCES `outbox`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                connection.runSql("CREATE INDEX IF NOT EXISTS `index_outbox_file_outboxId` ON `outbox_file` (`outboxId`)")
            }
        }

        /**
         * 0.1.0-beta.1: drafts are kept on the server as well. A draft knows how far its copy there
         * is behind it, and the copy of a message that was sent or thrown away is noted until the
         * server was told to remove it. What a search of the server found is remembered for a while.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.runSql("ALTER TABLE `outbox` ADD COLUMN `draftSavedAt` INTEGER NOT NULL DEFAULT 0")
                connection.runSql("ALTER TABLE `outbox` ADD COLUMN `draftUidValidity` INTEGER NOT NULL DEFAULT 0")
                connection.runSql(
                    "CREATE TABLE IF NOT EXISTS `draft_removal` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `accountId` INTEGER NOT NULL, " +
                        "`folderId` INTEGER NOT NULL, `uid` INTEGER NOT NULL, `uidValidity` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`accountId`) REFERENCES `account`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                connection.runSql("CREATE INDEX IF NOT EXISTS `index_draft_removal_accountId` ON `draft_removal` (`accountId`)")
                connection.runSql(
                    "CREATE TABLE IF NOT EXISTS `search_hit` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `query` TEXT NOT NULL, " +
                        "`messageId` INTEGER NOT NULL, `foundAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`messageId`) REFERENCES `message`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                connection.runSql("CREATE INDEX IF NOT EXISTS `index_search_hit_messageId` ON `search_hit` (`messageId`)")
                connection.runSql("CREATE UNIQUE INDEX IF NOT EXISTS `index_search_hit_query_messageId` ON `search_hit` (`query`, `messageId`)")
            }
        }

        /** Every step from a version that was released to the one after it, in order. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        /** Runs one statement that answers nothing. */
        private suspend fun SQLiteConnection.runSql(sql: String) {
            prepare(sql).use { it.step() }
        }

        fun open(context: Context): DeskMailDatabase =
            Room.databaseBuilder<DeskMailDatabase>(context.applicationContext, FILE)
                .setDriver(AndroidSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .addMigrations(*MIGRATIONS)
                .build()

        /** A database that is gone when it is closed: for the tests. */
        fun inMemory(context: Context): DeskMailDatabase =
            Room.inMemoryDatabaseBuilder<DeskMailDatabase>(context.applicationContext)
                .setDriver(AndroidSQLiteDriver())
                .build()
    }
}
