// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

/**
 * Decides when the Compose button hides while a list scrolls: it goes away when the user scrolls
 * down by more than [thresholdPx] and comes back on the first scroll up, when the list stops
 * moving, or when it is back at the top. Pure: feed it the position of the list and read the
 * answer, so the rule is testable without a screen.
 */
class FabScrollTracker(private val thresholdPx: Int = DEFAULT_THRESHOLD_PX) {
    private var lastIndex = 0
    private var lastOffset = 0
    private var downDistance = 0
    private var hidden = false

    /**
     * The list is at [index] with [offset] pixels scrolled into that item, and [scrolling] says
     * whether a drag or a fling is still going. Returns whether the button should be hidden.
     */
    fun onScroll(index: Int, offset: Int, scrolling: Boolean): Boolean {
        val delta = when {
            index > lastIndex -> ITEM_STEP_PX
            index < lastIndex -> -ITEM_STEP_PX
            else -> offset - lastOffset
        }
        lastIndex = index
        lastOffset = offset
        when {
            !scrolling || (index == 0 && offset == 0) || delta < 0 -> reveal()

            delta > 0 -> {
                downDistance += delta
                if (downDistance >= thresholdPx) hidden = true
            }
        }
        return hidden
    }

    private fun reveal() {
        downDistance = 0
        hidden = false
    }

    private companion object {
        const val DEFAULT_THRESHOLD_PX = 48

        /** Moving to another item is at least this much, whatever the heights of the rows. */
        const val ITEM_STEP_PX = 1_000
    }
}
