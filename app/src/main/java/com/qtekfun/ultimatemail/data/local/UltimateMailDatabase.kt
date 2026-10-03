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

private val FTS_TRIGGERS = listOf("BEFORE_UPDATE", "BEFORE_DELETE", "AFTER_UPDATE", "AFTER_INSERT")

/**
 * T20: the full-text index gets the `unicode61` tokenizer, which folds case and accents (the
 * default one only folds ASCII letters). The index is dropped and rebuilt from the message table
 * it mirrors, together with the triggers that keep it in step: Room's own definition of all of
 * it, so the schema check at open time passes.
 */
internal val MIGRATION_3_4: Migration = object : Migration(VERSION_3, VERSION_4) {
    override suspend fun migrate(connection: SQLiteConnection) {
        FTS_TRIGGERS.forEach {
            connection.execSQL("DROP TRIGGER IF EXISTS room_fts_content_sync_message_fts_$it")
        }
        connection.execSQL("DROP TABLE IF EXISTS `message_fts`")
        connection.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS `message_fts` USING FTS4(`subject` TEXT NOT " +
                "NULL, `senderName` TEXT NOT NULL, `senderAddress` TEXT NOT NULL, `bodyText` " +
                "TEXT, tokenize=unicode61, content=`message`)"
        )
        val columns = "`docid`, `subject`, `senderName`, `senderAddress`, `bodyText`"
        val values = "NEW.`rowid`, NEW.`subject`, NEW.`senderName`, NEW.`senderAddress`, " +
            "NEW.`bodyText`"
        connection.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_message_fts_BEFORE_UPDATE " +
                "BEFORE UPDATE ON `message` BEGIN DELETE FROM `message_fts` " +
                "WHERE `docid`=OLD.`rowid`; END"
        )
        connection.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_message_fts_BEFORE_DELETE " +
                "BEFORE DELETE ON `message` BEGIN DELETE FROM `message_fts` " +
                "WHERE `docid`=OLD.`rowid`; END"
        )
        connection.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_message_fts_AFTER_UPDATE " +
                "AFTER UPDATE ON `message` BEGIN INSERT INTO `message_fts`($columns) " +
                "VALUES ($values); END"
        )
        connection.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_message_fts_AFTER_INSERT " +
                "AFTER INSERT ON `message` BEGIN INSERT INTO `message_fts`($columns) " +
                "VALUES ($values); END"
        )
        connection.execSQL("INSERT INTO `message_fts`(`message_fts`) VALUES('rebuild')")
    }
}
