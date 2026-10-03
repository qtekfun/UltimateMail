// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.di

import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.dao.PendingOperationDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock

/** What the operation queue needs from the outside (its executor is bound in SyncModule). */
@Module
@InstallIn(SingletonComponent::class)
object QueueModule {
    @Provides
    fun pendingOperationDao(database: UltimateMailDatabase): PendingOperationDao =
        database.pendingOperationDao()

    @Provides
    fun clock(): Clock = Clock.systemUTC()
}
