// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FabScrollTrackerTest {
    @Test
    fun `a small scroll down keeps the button and a long one hides it`() {
        val tracker = FabScrollTracker(thresholdPx = 50)

        assertFalse(tracker.onScroll(0, 10, scrolling = true))
        assertFalse(tracker.onScroll(0, 40, scrolling = true))
        assertTrue(tracker.onScroll(0, 70, scrolling = true))
        assertTrue(tracker.onScroll(1, 5, scrolling = true))
    }

    @Test
    fun `the first scroll up brings it back`() {
        val tracker = FabScrollTracker(thresholdPx = 50)
        assertTrue(tracker.onScroll(2, 0, scrolling = true))

        assertFalse(tracker.onScroll(1, 30, scrolling = true))
        // Down again: it takes the whole threshold again to hide.
        assertFalse(tracker.onScroll(1, 40, scrolling = true))
    }

    @Test
    fun `stopping brings it back`() {
        val tracker = FabScrollTracker(thresholdPx = 50)
        assertTrue(tracker.onScroll(4, 0, scrolling = true))

        assertFalse(tracker.onScroll(4, 0, scrolling = false))
    }

    @Test
    fun `back at the top it is shown even while still moving`() {
        val tracker = FabScrollTracker(thresholdPx = 50)
        assertTrue(tracker.onScroll(4, 0, scrolling = true))

        assertFalse(tracker.onScroll(0, 0, scrolling = true))
    }

    @Test
    fun `small steps down add up and a step up resets the sum`() {
        val tracker = FabScrollTracker(thresholdPx = 50)
        assertFalse(tracker.onScroll(0, 5, scrolling = true))
        assertFalse(tracker.onScroll(0, 30, scrolling = true))
        assertFalse(tracker.onScroll(0, 20, scrolling = true))
        assertFalse(tracker.onScroll(0, 60, scrolling = true))
        assertTrue(tracker.onScroll(0, 80, scrolling = true))
    }
}
