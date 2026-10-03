// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.qtekfun.ultimatemail.sync.engine.SyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SyncScheduler] on WorkManager: unique periodic work every 15 minutes (the platform minimum)
 * with a network constraint, and unique one-time work for app open and pull-to-refresh.
 *
 * The on-demand work is not expedited: before Android 12 that needs a foreground service and its
 * notification, which the MVP leaves out (SPEC section 4). With network it starts at once anyway.
 */
@Singleton
class WorkManagerSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) : SyncScheduler {
    private val workManager get() = WorkManager.getInstance(context)

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun startPeriodic() {
        workManager.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
        )
    }

    override fun requestSync(accountId: Long?, userInitiated: Boolean) {
        val input = Data.Builder()
            .putLong(SyncWorker.KEY_ACCOUNT, accountId ?: SyncWorker.ALL_ACCOUNTS)
            .putBoolean(SyncWorker.KEY_USER_INITIATED, userInitiated)
            .build()
        workManager.enqueueUniqueWork(
            nowName(accountId),
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(input)
                .addTag(NOW_TAG)
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
        )
    }

    override fun stop() {
        workManager.cancelUniqueWork(PERIODIC)
        workManager.cancelAllWorkByTag(NOW_TAG)
    }

    private fun nowName(accountId: Long?) = "$NOW_TAG-${accountId ?: "all"}"

    private companion object {
        const val PERIODIC = "sync-periodic"
        const val NOW_TAG = "sync-now"
        const val PERIOD_MINUTES = 15L
        const val BACKOFF_SECONDS = 30L
    }
}
