// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.testing

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.qtekfun.ultimatemail.data.compose.ContentResolverSource
import com.qtekfun.ultimatemail.data.compose.ResourceQuoteTemplates
import com.qtekfun.ultimatemail.data.local.FileAttachmentStorage
import com.qtekfun.ultimatemail.data.local.FileOutboxStorage
import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.dao.AccountDao
import com.qtekfun.ultimatemail.data.local.dao.AttachmentDao
import com.qtekfun.ultimatemail.data.local.dao.DraftDao
import com.qtekfun.ultimatemail.data.local.dao.FolderDao
import com.qtekfun.ultimatemail.data.local.dao.MessageDao
import com.qtekfun.ultimatemail.data.local.dao.PendingOperationDao
import com.qtekfun.ultimatemail.di.AttachmentModule
import com.qtekfun.ultimatemail.di.DatabaseModule
import com.qtekfun.ultimatemail.di.IoDispatcher
import com.qtekfun.ultimatemail.di.OutboxModule
import com.qtekfun.ultimatemail.di.QueueModule
import com.qtekfun.ultimatemail.domain.compose.AttachmentSource
import com.qtekfun.ultimatemail.domain.compose.OutboxFileStorage
import com.qtekfun.ultimatemail.domain.compose.QuoteTemplatesProvider
import com.qtekfun.ultimatemail.sync.engine.AttachmentStorage
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher

/*
 * The test modules replace the production ones one for one (`@TestInstallIn`), each providing
 * everything the old module did, so the dependency graph of the app stays what it is and only
 * the edges to the outside world change: the database, the files, the network, WorkManager and
 * the clock. Files written by a test go under the cache folder, never the app's real storage.
 */

/** A fresh in-memory database for every test: no file, nothing shared with an installed app. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DatabaseModule::class])
object TestDatabaseModule {
    @Provides
    @Singleton
    fun database(
        @ApplicationContext context: Context,
        @IoDispatcher io: CoroutineDispatcher
    ): UltimateMailDatabase = Room.inMemoryDatabaseBuilder<UltimateMailDatabase>(context)
        .setDriver(AndroidSQLiteDriver())
        .setQueryCoroutineContext(io)
        .build()

    @Provides
    fun accountDao(database: UltimateMailDatabase): AccountDao = database.accountDao()

    @Provides
    fun folderDao(database: UltimateMailDatabase): FolderDao = database.folderDao()

    @Provides
    fun messageDao(database: UltimateMailDatabase): MessageDao = database.messageDao()

    @Provides
    fun attachmentDao(database: UltimateMailDatabase): AttachmentDao = database.attachmentDao()
}

/**
 * The queue's DAO and the time source. The clock stands still at the moment the test started:
 * nothing in these tests depends on time passing (the send window uses coroutine delays), and a
 * frozen clock keeps seeded dates, the offline window and back-off deterministic.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [QueueModule::class])
object TestQueueModule {
    @Provides
    fun pendingOperationDao(database: UltimateMailDatabase): PendingOperationDao =
        database.pendingOperationDao()

    @Provides
    @Singleton
    fun clock(): Clock = Clock.fixed(Instant.now(), ZoneOffset.UTC)
}

/** Attachments of received mail, under the cache folder. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [AttachmentModule::class])
object TestAttachmentModule {
    @Provides
    fun attachmentStorage(@ApplicationContext context: Context): AttachmentStorage =
        FileAttachmentStorage(File(context.cacheDir, "ui-test-attachments"))
}

/** What the composer needs from the platform; its files go under the cache folder. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [OutboxModule::class])
abstract class TestOutboxModule {
    @Binds
    abstract fun attachmentSource(impl: ContentResolverSource): AttachmentSource

    @Binds
    abstract fun quoteTemplates(impl: ResourceQuoteTemplates): QuoteTemplatesProvider

    companion object {
        @Provides
        @Singleton
        fun outboxStorage(@ApplicationContext context: Context): OutboxFileStorage =
            FileOutboxStorage(File(context.cacheDir, "ui-test-outbox"))

        @Provides
        fun draftDao(database: UltimateMailDatabase): DraftDao = database.draftDao()
    }
}
