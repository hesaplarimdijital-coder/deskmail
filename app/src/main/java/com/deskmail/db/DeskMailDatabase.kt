package com.deskmail.db

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.Dispatchers

/**
 * The app's database: the accounts and what is kept of their mail on the device. The mail itself
 * lives on the servers; everything here except the accounts can be fetched again.
 *
 * It runs on the SQLite the device has. The schema of every version is written to app/schemas;
 * from version 1 on, a change of schema comes with a migration and its test, so an update installs
 * over the version before it and the accounts stay.
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
    ],
    version = 1,
    exportSchema = true,
)
abstract class DeskMailDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao

    abstract fun folders(): FolderDao

    abstract fun messages(): MessageDao

    companion object {
        /** The database's file among the app's databases. */
        const val FILE = "mail.db"

        fun open(context: Context): DeskMailDatabase =
            Room.databaseBuilder<DeskMailDatabase>(context.applicationContext, FILE)
                .setDriver(AndroidSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()

        /** A database that is gone when it is closed: for the tests. */
        fun inMemory(context: Context): DeskMailDatabase =
            Room.inMemoryDatabaseBuilder<DeskMailDatabase>(context.applicationContext)
                .setDriver(AndroidSQLiteDriver())
                .build()
    }
}
