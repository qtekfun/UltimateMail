// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.qtekfun.ultimatemail.sync.engine.AccountSyncResult
import com.qtekfun.ultimatemail.sync.engine.SyncEngine
import javax.inject.Inject
import javax.inject.Provider

/**
 * Runs a sync in the background. It syncs one account when [KEY_ACCOUNT] is in the input data and
 * every account otherwise. WorkManager retries it with backoff after a network failure.
 */
class SyncWorker(context: Context, params: WorkerParameters, private val engine: SyncEngine) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val userInitiated = inputData.getBoolean(KEY_USER_INITIATED, false)
        val account = inputData.getLong(KEY_ACCOUNT, ALL_ACCOUNTS)
        val retry = if (account == ALL_ACCOUNTS) {
            engine.syncAll(userInitiated).shouldRetry
        } else {
            val result = engine.sync(account, userInitiated)
            result is AccountSyncResult.Failed && result.problem.retryable
        }
        return if (retry) Result.retry() else Result.success()
    }

    companion object {
        const val KEY_ACCOUNT = "account"
        const val KEY_USER_INITIATED = "user_initiated"
        const val ALL_ACCOUNTS = -1L
    }
}

/** Creates workers with their dependencies, without an extra Hilt-WorkManager library. */
class SyncWorkerFactory @Inject constructor(private val engine: Provider<SyncEngine>) :
    WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? = if (workerClassName == SyncWorker::class.java.name) {
        SyncWorker(appContext, workerParameters, engine.get())
    } else {
        null
    }
}
