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
    ],
    version = 2,
    exportSchema = true,
)
abstract class DeskMailDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao

    abstract fun folders(): FolderDao

    abstract fun messages(): MessageDao

    abstract fun pending(): PendingDao

    companion object {
        /** The database's file among the app's databases. */
        const val FILE = "mail.db"

        /**
         * 0.1.0-alpha.3: what was done on the device and is still to be told to the server
         * (read and unread, the star, moves and removals).
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.run(
                    "CREATE TABLE IF NOT EXISTS `pending_op` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`accountId` INTEGER NOT NULL, `messageId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `value` INTEGER NOT NULL, " +
                        "`targetFolderId` INTEGER NOT NULL, `notBefore` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`accountId`) REFERENCES `account`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`messageId`) REFERENCES `message`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                connection.run("CREATE INDEX IF NOT EXISTS `index_pending_op_accountId` ON `pending_op` (`accountId`)")
                connection.run("CREATE INDEX IF NOT EXISTS `index_pending_op_messageId` ON `pending_op` (`messageId`)")
            }
        }

        /** Every step from a version that was released to the one after it, in order. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        /** Runs one statement that answers nothing. */
        private suspend fun SQLiteConnection.run(sql: String) {
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
