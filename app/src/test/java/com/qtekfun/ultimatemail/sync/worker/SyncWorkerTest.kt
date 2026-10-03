// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.worker

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.qtekfun.ultimatemail.sync.engine.AccountSyncResult
import com.qtekfun.ultimatemail.sync.engine.SyncCounts
import com.qtekfun.ultimatemail.sync.engine.SyncEngine
import com.qtekfun.ultimatemail.sync.engine.SyncProblem
import com.qtekfun.ultimatemail.sync.engine.SyncReport
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncWorkerTest {
    private val engine = mockk<SyncEngine>()
    private val factory = SyncWorkerFactory { engine }
    private val context = mockk<Context>(relaxed = true)

    private fun params(account: Long? = null, userInitiated: Boolean = false): WorkerParameters {
        val input = mockk<Data>()
        every { input.getLong(SyncWorker.KEY_ACCOUNT, SyncWorker.ALL_ACCOUNTS) } returns
            (account ?: SyncWorker.ALL_ACCOUNTS)
        every { input.getBoolean(SyncWorker.KEY_USER_INITIATED, false) } returns userInitiated
        return mockk<WorkerParameters>(relaxed = true).also { every { it.inputData } returns input }
    }

    private fun worker(params: WorkerParameters) =
        factory.createWorker(context, SyncWorker::class.java.name, params) as SyncWorker

    @Test
    fun `without an account every account is synced and success needs no retry`() = runTest {
        coEvery { engine.syncAll(true) } returns
            SyncReport(mapOf(1L to AccountSyncResult.Synced(SyncCounts())))

        val result = worker(params(userInitiated = true)).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `a network problem asks WorkManager to retry with backoff`() = runTest {
        coEvery { engine.syncAll(false) } returns
            SyncReport(mapOf(1L to AccountSyncResult.Failed(SyncProblem.NETWORK)))

        assertEquals(ListenableWorker.Result.retry(), worker(params()).doWork())
    }

    @Test
    fun `problems that waiting does not fix and sign-in requests do not retry`() = runTest {
        coEvery { engine.syncAll(false) } returns SyncReport(
            mapOf(
                1L to AccountSyncResult.Failed(SyncProblem.CERTIFICATE),
                2L to AccountSyncResult.ReauthenticationNeeded
            )
        )

        assertEquals(ListenableWorker.Result.success(), worker(params()).doWork())
    }

    @Test
    fun `one account is synced when the request names it`() = runTest {
        coEvery { engine.sync(7, true) } returns AccountSyncResult.Failed(SyncProblem.TIMEOUT)

        val result = worker(params(account = 7, userInitiated = true)).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        coVerify(exactly = 0) { engine.syncAll(any()) }
    }

    @Test
    fun `a finished sync of one account succeeds`() = runTest {
        coEvery { engine.sync(7, false) } returns AccountSyncResult.Synced(SyncCounts(added = 1))

        assertEquals(ListenableWorker.Result.success(), worker(params(account = 7)).doWork())
    }

    @Test
    fun `the factory only creates its own worker`() {
        assertNull(factory.createWorker(context, "other.Worker", params()))
        assertTrue(
            factory.createWorker(context, SyncWorker::class.java.name, params()) is SyncWorker
        )
    }
}
