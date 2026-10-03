// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.qtekfun.ultimatemail.data.local.entity.FolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folders: List<FolderEntity>)

    @Query("SELECT * FROM folder WHERE accountId = :accountId ORDER BY path")
    fun observeAll(accountId: Long): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folder WHERE accountId = :accountId AND path = :path")
    suspend fun get(accountId: Long, path: String): FolderEntity?

    @Query("SELECT * FROM folder WHERE accountId = :accountId AND syncEnabled = 1 ORDER BY path")
    suspend fun syncable(accountId: Long): List<FolderEntity>

    /** Removes folders that no longer exist on the server; their messages go with them. */
    @Query("DELETE FROM folder WHERE accountId = :accountId AND path NOT IN (:keep)")
    suspend fun deleteAllExcept(accountId: Long, keep: List<String>)

    @Query(
        "UPDATE folder SET uidValidity = :uidValidity, uidNext = :uidNext, " +
            "highestModSeq = :highestModSeq WHERE accountId = :accountId AND path = :path"
    )
    suspend fun setSyncState(
        accountId: Long,
        path: String,
        uidValidity: Long?,
        uidNext: Long?,
        highestModSeq: Long?
    )

    /** Unread messages per folder, for the folder list badges. */
    @Query(
        "SELECT folderPath AS path, COUNT(*) AS unread FROM message " +
            "WHERE accountId = :accountId AND seen = 0 GROUP BY folderPath"
    )
    fun observeUnread(accountId: Long): Flow<List<FolderUnread>>
}

data class FolderUnread(val path: String, val unread: Int)
