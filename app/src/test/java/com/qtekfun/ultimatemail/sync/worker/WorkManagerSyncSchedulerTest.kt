// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WorkManagerSyncSchedulerTest {
    private val context = mockk<Context>(relaxed = true)
    private val workManager = mockk<WorkManager>(relaxed = true)
    private val scheduler = WorkManagerSyncScheduler(context)

    @BeforeEach
    fun setUp() {
        mockkObject(WorkManager.Companion)
        every { WorkManager.getInstance(context) } returns workManager
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(WorkManager.Companion)
    }

    @Test
    fun `the periodic sync is unique, every 15 minutes and needs network`() {
        val request = slot<PeriodicWorkRequest>()
        every {
            workManager.enqueueUniquePeriodicWork("sync-periodic", any(), capture(request))
        } returns mockk(relaxed = true)

        scheduler.startPeriodic()

        verify {
            workManager.enqueueUniquePeriodicWork(
                "sync-periodic",
                ExistingPeriodicWorkPolicy.KEEP,
                any()
            )
        }
        val spec = request.captured.workSpec
        assertEquals(TimeUnit.MINUTES.toMillis(15), spec.intervalDuration)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(SyncWorker::class.java.name, spec.workerClassName)
    }

    @Test
    fun `a sync on demand is unique per account and carries who asked for what`() {
        val request = slot<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork("sync-now-7", any(), capture(request))
        } returns mockk(relaxed = true)

        scheduler.requestSync(accountId = 7, userInitiated = true)

        verify {
            workManager.enqueueUniqueWork(
                "sync-now-7",
                ExistingWorkPolicy.KEEP,
                any<OneTimeWorkRequest>()
            )
        }
        val spec = request.captured.workSpec
        assertEquals(7L, spec.input.getLong(SyncWorker.KEY_ACCOUNT, -2))
        assertTrue(spec.input.getBoolean(SyncWorker.KEY_USER_INITIATED, false))
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertTrue("sync-now" in request.captured.tags)
    }

    @Test
    fun `a sync of every account has its own unique name`() {
        val request = slot<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork("sync-now-all", any(), capture(request))
        } returns mockk(relaxed = true)

        scheduler.requestSync()

        assertEquals(-1L, request.captured.workSpec.input.getLong(SyncWorker.KEY_ACCOUNT, -2))
    }

    @Test
    fun `stopping cancels the periodic sync and the waiting requests`() {
        scheduler.stop()

        verify { workManager.cancelUniqueWork("sync-periodic") }
        verify { workManager.cancelAllWorkByTag("sync-now") }
    }
}
