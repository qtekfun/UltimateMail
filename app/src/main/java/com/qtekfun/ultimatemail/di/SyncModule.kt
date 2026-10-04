// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.di

import com.qtekfun.ultimatemail.sync.engine.LastSyncLog
import com.qtekfun.ultimatemail.sync.engine.MailOperationExecutor
import com.qtekfun.ultimatemail.sync.engine.PreferenceLastSyncLog
import com.qtekfun.ultimatemail.sync.engine.SyncScheduler
import com.qtekfun.ultimatemail.sync.engine.SyncStatus
import com.qtekfun.ultimatemail.sync.engine.SyncStatusStore
import com.qtekfun.ultimatemail.sync.queue.OperationExecutor
import com.qtekfun.ultimatemail.sync.worker.WorkManagerSyncScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds the sync engine's interfaces: the real executor, the status for the UI, WorkManager. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {
    @Binds
    abstract fun operationExecutor(impl: MailOperationExecutor): OperationExecutor

    @Binds
    abstract fun syncStatus(impl: SyncStatusStore): SyncStatus

    @Binds
    abstract fun lastSyncLog(impl: PreferenceLastSyncLog): LastSyncLog

    @Binds
    abstract fun syncScheduler(impl: WorkManagerSyncScheduler): SyncScheduler
}
