// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import com.qtekfun.ultimatemail.data.local.entity.AttachmentEntity
import com.qtekfun.ultimatemail.data.local.model.AttachmentState
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
    @Insert
    suspend fun insert(attachments: List<AttachmentEntity>)

    @Query("SELECT * FROM attachment WHERE messageId = :messageId ORDER BY id")
    fun observe(messageId: Long): Flow<List<AttachmentEntity>>

    @Query("UPDATE attachment SET state = :state, localPath = :localPath WHERE id = :id")
    suspend fun setState(id: Long, state: AttachmentState, localPath: String?)
}
