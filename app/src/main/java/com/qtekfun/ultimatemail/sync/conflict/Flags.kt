// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.conflict

import java.time.Instant

/** The flags that sync both ways. */
enum class Flag { SEEN, FLAGGED, ANSWERED }

/** The flags of a message as the server (or the merged result) has them. */
data class Flags(
    val seen: Boolean = false,
    val flagged: Boolean = false,
    val answered: Boolean = false
) {
    operator fun get(flag: Flag) = when (flag) {
        Flag.SEEN -> seen
        Flag.FLAGGED -> flagged
        Flag.ANSWERED -> answered
    }

    fun with(flag: Flag, value: Boolean) = when (flag) {
        Flag.SEEN -> copy(seen = value)
        Flag.FLAGGED -> copy(flagged = value)
        Flag.ANSWERED -> copy(answered = value)
    }
}

/** Absolute values the user set; a null flag was not touched by the operation. */
data class FlagPatch(
    val seen: Boolean? = null,
    val flagged: Boolean? = null,
    val answered: Boolean? = null
) {
    operator fun get(flag: Flag) = when (flag) {
        Flag.SEEN -> seen
        Flag.FLAGGED -> flagged
        Flag.ANSWERED -> answered
    }
}

/** A queued flag operation: [createdAt] and [id] give the order in which the user made them. */
data class PendingFlagOperation(val id: Long, val createdAt: Instant, val patch: FlagPatch)

/**
 * Outcome of laying the pending operations over the server state.
 *
 * @property merged what the message must look like locally now.
 * @property remaining ids of the operations that still have to be sent.
 * @property completed ids of the operations that need no sending: the server already has the
 * value, or a later operation overrides it.
 */
data class FlagResolution(val merged: Flags, val remaining: List<Long>, val completed: List<Long>)
