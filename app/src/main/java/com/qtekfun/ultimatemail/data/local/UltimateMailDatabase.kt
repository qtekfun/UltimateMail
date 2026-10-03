// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local

import androidx.room3.ColumnTypeConverters
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.qtekfun.ultimatemail.data.local.dao.AccountDao
import com.qtekfun.ultimatemail.data.local.dao.AttachmentDao
import com.qtekfun.ultimatemail.data.local.dao.ConversationDao
import com.qtekfun.ultimatemail.data.local.dao.DraftDao
import com.qtekfun.ultimatemail.data.local.dao.FolderDao
import com.qtekfun.ultimatemail.data.local.dao.MessageDao
import com.qtekfun.ultimatemail.data.local.dao.PendingOperationDao
import com.qtekfun.ultimatemail.data.local.entity.AccountEntity
import com.qtekfun.ultimatemail.data.local.entity.AttachmentEntity
import com.qtekfun.ultimatemail.data.local.entity.DraftEntity
import com.qtekfun.ultimatemail.data.local.entity.FolderEntity
import com.qtekfun.ultimatemail.data.local.entity.MessageEntity
import com.qtekfun.ultimatemail.data.local.entity.MessageFtsEntity
import com.qtekfun.ultimatemail.data.local.entity.OutgoingAttachmentEntity
import com.qtekfun.ultimatemail.data.local.entity.PendingOperationEntity

/** Local source of truth (SPEC RF-10). Schemas are exported to app/schemas and versioned. */
@Database(
    entities = [
        AccountEntity::class,
        FolderEntity::class,
        MessageEntity::class,
        MessageFtsEntity::class,
        AttachmentEntity::class,
        PendingOperationEntity::class,
        DraftEntity::class,
        OutgoingAttachmentEntity::class
    ],
    version = UltimateMailDatabase.VERSION,
    exportSchema = true
)
@ColumnTypeConverters(Converters::class)
abstract class UltimateMailDatabase : RoomDatabase() {
    companion object {
        const val VERSION = 4

        /**
         * Migrations from each released version to the next. There is no destructive fallback:
         * raising [VERSION] requires adding its migration here (checked by DatabaseSchemaTest).
         */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    }

    abstract fun accountDao(): AccountDao

    abstract fun folderDao(): FolderDao

    abstract fun messageDao(): MessageDao

    abstract fun conversationDao(): ConversationDao

    abstract fun attachmentDao(): AttachmentDao

    abstract fun pendingOperationDao(): PendingOperationDao

    abstract fun draftDao(): DraftDao
}

/** T10: the headers conversations are built from are kept with each message. */
internal val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE message ADD COLUMN inReplyTo TEXT")
        connection.execSQL("ALTER TABLE message ADD COLUMN referenceIds TEXT NOT NULL DEFAULT ''")
    }
}

private const val VERSION_3 = 3

/** T15: attachments remember their Content-ID, so the `cid:` images of a message can be shown. */
internal val MIGRATION_2_3: Migration = object : Migration(2, VERSION_3) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE attachment ADD COLUMN contentId TEXT")
        connection.execSQL("ALTER TABLE attachment ADD COLUMN inline INTEGER NOT NULL DEFAULT 0")
    }
}

private const val VERSION_4 = 4

/** T18a: drafts and the files attached to them, for the composer and the outbox. */
internal val MIGRATION_3_4: Migration = object : Migration(VERSION_3, VERSION_4) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `draft` (`id` INTEGER PRIMARY KEY AUTOINCREMENT " +
                "NOT NULL, `key` TEXT NOT NULL, `accountId` INTEGER NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`state` TEXT NOT NULL DEFAULT 'EDITING', `toAddresses` TEXT NOT NULL, " +
                "`ccAddresses` TEXT NOT NULL, `bccAddresses` TEXT NOT NULL, " +
                "`subject` TEXT NOT NULL, `body` TEXT NOT NULL, `inReplyTo` TEXT, " +
                "`referenceIds` TEXT NOT NULL DEFAULT '', `sourceAccountId` INTEGER, " +
                "`sourceFolderPath` TEXT, `sourceUid` INTEGER, `sourceMessageId` TEXT, " +
                "`signatureText` TEXT, `signatureBeforeQuote` INTEGER NOT NULL DEFAULT 1, " +
                "`serverMessageId` TEXT, `dirty` INTEGER NOT NULL DEFAULT 1, " +
                "`revision` INTEGER NOT NULL DEFAULT 0, `outgoingMessageId` TEXT, " +
                "`smtpAcceptedAt` INTEGER, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, FOREIGN KEY(`accountId`) REFERENCES " +
                "`account`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_draft_accountId_state_updatedAt` " +
                "ON `draft` (`accountId`, `state`, `updatedAt`)"
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_draft_key` ON `draft` (`key`)"
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `outgoing_attachment` (`id` INTEGER PRIMARY KEY " +
                "AUTOINCREMENT NOT NULL, `draftId` INTEGER NOT NULL, " +
                "`displayName` TEXT NOT NULL, `mimeType` TEXT NOT NULL, " +
                "`size` INTEGER NOT NULL, `filePath` TEXT NOT NULL, " +
                "FOREIGN KEY(`draftId`) REFERENCES `draft`(`id`) ON UPDATE NO ACTION " +
                "ON DELETE CASCADE )"
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_outgoing_attachment_draftId` " +
                "ON `outgoing_attachment` (`draftId`)"
        )
    }
}
