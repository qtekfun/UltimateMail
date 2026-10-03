// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.queue

import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BackoffTest {
    private val backoff = Backoff()

    @Test
    fun `starts at 30 seconds and doubles`() {
        assertEquals(
            listOf(30L, 60L, 120L, 240L, 480L),
            (1..5).map { backoff.delayAfter(it).seconds }
        )
    }

    @Test
    fun `never goes below the base`() {
        assertEquals(Duration.ofSeconds(30), backoff.delayAfter(0))
        assertEquals(Duration.ofSeconds(30), backoff.delayAfter(-3))
    }

    @Test
    fun `is capped at six hours`() {
        assertEquals(Duration.ofSeconds(15_360), backoff.delayAfter(10))
        assertEquals(Duration.ofHours(6), backoff.delayAfter(11))
        assertEquals(Duration.ofHours(6), backoff.delayAfter(Int.MAX_VALUE))
    }

    @Test
    fun `base and cap can be chosen`() {
        val custom = Backoff(base = Duration.ofSeconds(1), cap = Duration.ofSeconds(5))

        assertEquals(listOf(1L, 2L, 4L, 5L), (1..4).map { custom.delayAfter(it).seconds })
    }
}
