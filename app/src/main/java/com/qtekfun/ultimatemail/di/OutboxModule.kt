// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.di

import android.content.Context
import com.qtekfun.ultimatemail.data.compose.ContentResolverSource
import com.qtekfun.ultimatemail.data.compose.ResourceQuoteTemplates
import com.qtekfun.ultimatemail.data.local.FileOutboxStorage
import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.dao.DraftDao
import com.qtekfun.ultimatemail.domain.compose.AttachmentSource
import com.qtekfun.ultimatemail.domain.compose.OutboxFileStorage
import com.qtekfun.ultimatemail.domain.compose.QuoteTemplatesProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/** What the composer engine (T18a) needs from the platform: files, picked content, texts. */
@Module
@InstallIn(SingletonComponent::class)
abstract class OutboxModule {
    @Binds
    abstract fun attachmentSource(impl: ContentResolverSource): AttachmentSource

    @Binds
    abstract fun quoteTemplates(impl: ResourceQuoteTemplates): QuoteTemplatesProvider

    companion object {
        /** Not backed up: an attachment copy is only useful on this device, until it is sent. */
        const val FOLDER = "outbox"

        @Provides
        @Singleton
        fun outboxStorage(@ApplicationContext context: Context): OutboxFileStorage =
            FileOutboxStorage(File(context.noBackupFilesDir, FOLDER))

        @Provides
        fun draftDao(database: UltimateMailDatabase): DraftDao = database.draftDao()
    }
}
