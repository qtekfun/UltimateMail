// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.di

import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.dao.PendingOperationDao
import com.qtekfun.ultimatemail.sync.queue.OperationExecutor
import com.qtekfun.ultimatemail.sync.queue.OperationOutcome
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock

/** What the operation queue needs from the outside. */
@Module
@InstallIn(SingletonComponent::class)
object QueueModule {
    @Provides
    fun pendingOperationDao(database: UltimateMailDatabase): PendingOperationDao =
        database.pendingOperationDao()

    @Provides
    fun clock(): Clock = Clock.systemUTC()

    // Placeholder until the sync engine (T10) provides the IMAP executor: nothing is sent yet.
    @Provides
    fun operationExecutor(): OperationExecutor =
        OperationExecutor { OperationOutcome.RetryLater("executor_unavailable") }
}
