// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.queue

/** How many operations of an [OperationQueue.drain] pass ended each way. */
data class DrainSummary(val done: Int = 0, val retryLater: Int = 0, val rejected: Int = 0) {
    /** Counts [outcome]; null (an operation that was skipped or vanished) counts for nothing. */
    internal operator fun plus(outcome: OperationOutcome?) = when (outcome) {
        OperationOutcome.Done -> copy(done = done + 1)
        is OperationOutcome.RetryLater -> copy(retryLater = retryLater + 1)
        is OperationOutcome.Rejected -> copy(rejected = rejected + 1)
        null -> this
    }
}
