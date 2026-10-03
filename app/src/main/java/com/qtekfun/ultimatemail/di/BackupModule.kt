// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.di

import android.content.ContentResolver
import android.content.Context
import com.qtekfun.ultimatemail.BuildConfig
import com.qtekfun.ultimatemail.data.backup.ContentResolverDocuments
import com.qtekfun.ultimatemail.data.backup.PreferencePendingFolderChoices
import com.qtekfun.ultimatemail.domain.backup.BackupCrypto
import com.qtekfun.ultimatemail.domain.backup.DocumentSink
import com.qtekfun.ultimatemail.domain.backup.DocumentSource
import com.qtekfun.ultimatemail.domain.backup.PendingFolderChoices
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class BackupModule {
    @Binds
    abstract fun documentSink(documents: ContentResolverDocuments): DocumentSink

    @Binds
    abstract fun documentSource(documents: ContentResolverDocuments): DocumentSource

    @Binds
    abstract fun pendingFolderChoices(impl: PreferencePendingFolderChoices): PendingFolderChoices

    companion object {
        @Provides
        fun contentResolver(@ApplicationContext context: Context): ContentResolver =
            context.contentResolver

        /** Production key derivation settings: 600,000 PBKDF2 iterations. */
        @Provides
        fun backupCrypto(): BackupCrypto = BackupCrypto()

        @Provides
        @AppVersionName
        fun appVersionName(): String = BuildConfig.VERSION_NAME
    }
}
