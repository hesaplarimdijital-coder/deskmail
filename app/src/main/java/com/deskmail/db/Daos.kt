package com.deskmail.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Update
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    /** Every account in the user's order; emits again whenever one changes. */
    @Query("SELECT * FROM account ORDER BY position, id")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM account ORDER BY position, id")
    suspend fun all(): List<AccountEntity>

    @Query("SELECT * FROM account WHERE id = :id")
    suspend fun byId(id: Long): AccountEntity?

    /** @return the new account's id. */
    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    /** Deletes the account and, with it, its credential, its folders and their messages. */
    @Query("DELETE FROM account WHERE id = :id")
    suspend fun delete(id: Long)

    @Upsert
    suspend fun saveCredential(credential: CredentialEntity)

    @Query("SELECT * FROM credential WHERE accountId = :accountId")
    suspend fun credential(accountId: Long): CredentialEntity?
}

@Dao
interface FolderDao {
    /** Every folder of every account, in the accounts' order and then the servers'. */
    @Query("SELECT folder.* FROM folder JOIN account ON account.id = folder.accountId ORDER BY account.position, account.id, folder.position, folder.id")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folder WHERE accountId = :accountId ORDER BY position, id")
    suspend fun ofAccount(accountId: Long): List<FolderEntity>

    @Query("SELECT * FROM folder WHERE id = :id")
    suspend fun byId(id: Long): FolderEntity?

    @Insert
    suspend fun insert(folder: FolderEntity): Long

    @Update
    suspend fun update(folder: FolderEntity)

    @Query("DELETE FROM folder WHERE id = :id")
    suspend fun delete(id: Long)
}
