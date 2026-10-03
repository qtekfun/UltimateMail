// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.di

import com.qtekfun.ultimatemail.domain.inbox.RefreshTrigger
import com.qtekfun.ultimatemail.sync.engine.SyncScheduler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds pull-to-refresh to the sync engine: a user-initiated sync request, which also retries
 * accounts that are waiting for the user to sign in again.
 */
@Module
@InstallIn(SingletonComponent::class)
object RefreshModule {
    @Provides
    fun refreshTrigger(scheduler: SyncScheduler): RefreshTrigger = RefreshTrigger { accountId ->
        scheduler.requestSync(accountId, userInitiated = true)
    }
}
