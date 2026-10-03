// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local

import androidx.room3.ColumnTypeConverters
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import com.qtekfun.ultimatemail.data.local.dao.AccountDao
import com.qtekfun.ultimatemail.data.local.dao.AttachmentDao
import com.qtekfun.ultimatemail.data.local.dao.ConversationDao
import com.qtekfun.ultimatemail.data.local.dao.FolderDao
import com.qtekfun.ultimatemail.data.local.dao.MessageDao
import com.qtekfun.ultimatemail.data.local.dao.PendingOperationDao
import com.qtekfun.ultimatemail.data.local.entity.AccountEntity
import com.qtekfun.ultimatemail.data.local.entity.AttachmentEntity
import com.qtekfun.ultimatemail.data.local.entity.FolderEntity
import com.qtekfun.ultimatemail.data.local.entity.MessageEntity
import com.qtekfun.ultimatemail.data.local.entity.MessageFtsEntity
import com.qtekfun.ultimatemail.data.local.entity.PendingOperationEntity

/** Local source of truth (SPEC RF-10). Schemas are exported to app/schemas and versioned. */
@Database(
    entities = [
        AccountEntity::class,
        FolderEntity::class,
        MessageEntity::class,
        MessageFtsEntity::class,
        AttachmentEntity::class,
        PendingOperationEntity::class
    ],
    version = UltimateMailDatabase.VERSION,
    exportSchema = true
)
@ColumnTypeConverters(Converters::class)
abstract class UltimateMailDatabase : RoomDatabase() {
    companion object {
        const val VERSION = 1

        /**
         * Migrations from each released version to the next. There is no destructive fallback:
         * raising [VERSION] requires adding its migration here (checked by DatabaseSchemaTest).
         */
        val MIGRATIONS: Array<Migration> = emptyArray()
    }

    abstract fun accountDao(): AccountDao

    abstract fun folderDao(): FolderDao

    abstract fun messageDao(): MessageDao

    abstract fun conversationDao(): ConversationDao

    abstract fun attachmentDao(): AttachmentDao

    abstract fun pendingOperationDao(): PendingOperationDao
}
