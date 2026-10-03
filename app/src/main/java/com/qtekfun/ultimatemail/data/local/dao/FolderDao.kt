// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.qtekfun.ultimatemail.data.local.entity.FolderEntity
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import kotlinx.coroutines.flow.Flow

@Suppress("TooManyFunctions") // One query per operation on folders.
@Dao
interface FolderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folders: List<FolderEntity>)

    @Query("SELECT * FROM folder WHERE accountId = :accountId ORDER BY path")
    fun observeAll(accountId: Long): Flow<List<FolderEntity>>

    /** Inserts folders not stored yet; stored ones are left alone, because replacing a folder row
     * would delete its messages through the foreign key. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(folders: List<FolderEntity>)

    @Query("SELECT * FROM folder WHERE accountId = :accountId ORDER BY path")
    suspend fun all(accountId: Long): List<FolderEntity>

    /** What the server says about a folder, keeping the user's sync choice and the sync state. */
    @Query(
        "UPDATE folder SET name = :name, role = :role, isLabel = :isLabel " +
            "WHERE accountId = :accountId AND path = :path"
    )
    suspend fun updateDescription(
        accountId: Long,
        path: String,
        name: String,
        role: FolderRole,
        isLabel: Boolean
    )

    @Query("SELECT * FROM folder WHERE accountId = :accountId AND path = :path")
    suspend fun get(accountId: Long, path: String): FolderEntity?

    @Query("SELECT * FROM folder WHERE accountId = :accountId AND syncEnabled = 1 ORDER BY path")
    suspend fun syncable(accountId: Long): List<FolderEntity>

    /** The user's choice of whether a folder is synced (RF-10). */
    @Query(
        "UPDATE folder SET syncEnabled = :enabled WHERE accountId = :accountId AND path = :path"
    )
    suspend fun setSyncEnabled(accountId: Long, path: String, enabled: Boolean)

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
