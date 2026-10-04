// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.inbox

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpringBackTest {
    /** A row that is away until a reset takes; the first [refusals] resets are refused. */
    private class FakeRow(private val refusals: Int) {
        var away = true
        var resets = 0
        var snaps = 0

        suspend fun reset() {
            resets++
            if (resets <= refusals) throw CancellationException("release animation still running")
            away = false
        }

        suspend fun snap() {
            snaps++
            away = false
        }
    }

    @Test
    fun `a reset that takes leaves the row in place without snapping`() = runTest {
        val row = FakeRow(refusals = 0)

        springBack({ row.away }, row::reset, row::snap)

        assertFalse(row.away)
        assertEquals(1, row.resets)
        assertEquals(0, row.snaps)
    }

    @Test
    fun `a refused reset is tried again until it takes`() = runTest {
        val row = FakeRow(refusals = 3)

        springBack({ row.away }, row::reset, row::snap)

        assertFalse(row.away)
        assertEquals(4, row.resets)
        assertEquals(0, row.snaps)
    }

    @Test
    fun `a reset that is always refused ends with the row snapped back`() = runTest {
        val row = FakeRow(refusals = Int.MAX_VALUE)

        springBack({ row.away }, row::reset, row::snap, attempts = 5)

        assertFalse(row.away)
        assertEquals(5, row.resets)
        assertEquals(1, row.snaps)
    }

    @Test
    fun `a row that is already in place is not touched`() = runTest {
        val row = FakeRow(refusals = 0).apply { away = false }

        springBack({ row.away }, row::reset, row::snap)

        assertEquals(0, row.resets)
        assertEquals(0, row.snaps)
    }

    @Test
    fun `cancelling the caller stops the retries instead of swallowing it`() = runTest {
        val row = FakeRow(refusals = Int.MAX_VALUE)
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            springBack({ row.away }, row::reset, row::snap, retryMillis = 1_000)
        }
        runCurrent()
        assertEquals(1, row.resets)

        job.cancel()
        advanceTimeBy(10_000)

        assertTrue(job.isCancelled)
        assertThrows(CancellationException::class.java) { job.getCompleted() }
        assertEquals(1, row.resets)
        assertEquals(0, row.snaps)
    }
}
