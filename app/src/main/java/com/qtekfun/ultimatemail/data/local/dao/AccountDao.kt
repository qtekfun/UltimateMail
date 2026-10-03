// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Update
import com.qtekfun.ultimatemail.data.local.entity.AccountEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    @Query("SELECT * FROM account ORDER BY id")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM account WHERE id = :id")
    suspend fun get(id: Long): AccountEntity?

    @Query("SELECT * FROM account WHERE id = :id")
    fun observe(id: Long): Flow<AccountEntity?>

    /** Deletes the account and, through foreign keys, all its local data (RF-01). */
    @Query("DELETE FROM account WHERE id = :id")
    suspend fun delete(id: Long)
}
