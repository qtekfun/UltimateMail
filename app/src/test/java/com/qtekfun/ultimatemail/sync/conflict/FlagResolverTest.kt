// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.conflict

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FlagResolverTest {
    private fun op(id: Long, second: Long, patch: FlagPatch) =
        PendingFlagOperation(id, Instant.ofEpochSecond(second), patch)

    @Test
    fun `without pending operations the server state stands`() {
        val server = Flags(seen = true, answered = true)
        val result = FlagResolver.resolve(server, emptyList())
        assertEquals(FlagResolution(server, emptyList(), emptyList()), result)
    }

    @Test
    fun `a pending change is laid over a different server state and stays queued`() {
        val result = FlagResolver.resolve(
            Flags(seen = false),
            listOf(op(1, 10, FlagPatch(seen = true)))
        )
        assertEquals(Flags(seen = true), result.merged)
        assertEquals(listOf(1L), result.remaining)
        assertEquals(emptyList<Long>(), result.completed)
    }

    @Test
    fun `a change the server already has completes without being sent`() {
        val result = FlagResolver.resolve(
            Flags(flagged = true),
            listOf(op(1, 10, FlagPatch(flagged = true)))
        )
        assertEquals(Flags(flagged = true), result.merged)
        assertEquals(emptyList<Long>(), result.remaining)
        assertEquals(listOf(1L), result.completed)
    }

    @Test
    fun `the last change wins whatever the list order`() {
        val later = op(2, 20, FlagPatch(seen = false))
        val earlier = op(1, 10, FlagPatch(seen = true))
        val result = FlagResolver.resolve(Flags(seen = true), listOf(later, earlier))
        assertEquals(Flags(seen = false), result.merged)
        assertEquals(listOf(2L), result.remaining)
        assertEquals(listOf(1L), result.completed)
    }

    @Test
    fun `equal timestamps fall back to the operation id`() {
        val a = op(5, 10, FlagPatch(seen = true))
        val b = op(6, 10, FlagPatch(seen = false))
        val result = FlagResolver.resolve(Flags(seen = true), listOf(b, a))
        assertEquals(Flags(seen = false), result.merged)
        assertEquals(listOf(6L), result.remaining)
    }

    @Test
    fun `flags are settled independently and untouched ones keep the server value`() {
        val result = FlagResolver.resolve(
            Flags(seen = false, flagged = true, answered = true),
            listOf(
                op(1, 10, FlagPatch(seen = true, flagged = true)),
                op(2, 20, FlagPatch(flagged = false))
            )
        )
        assertEquals(Flags(seen = true, flagged = false, answered = true), result.merged)
        assertEquals(listOf(1L, 2L), result.remaining)
        assertEquals(emptyList<Long>(), result.completed)
    }

    @Test
    fun `an operation whose every value is superseded or already there is completed`() {
        val result = FlagResolver.resolve(
            Flags(seen = true, flagged = false),
            listOf(
                op(1, 10, FlagPatch(seen = false, answered = true)),
                op(2, 20, FlagPatch(seen = true)),
                op(3, 30, FlagPatch(flagged = false))
            )
        )
        // op 1 still has to send answered; seen is overridden by op 2, which the server has.
        assertEquals(Flags(seen = true, flagged = false, answered = true), result.merged)
        assertEquals(listOf(1L), result.remaining)
        assertEquals(listOf(2L, 3L), result.completed)
    }

    @Test
    fun `an operation touching no flag is completed and changes nothing`() {
        val server = Flags(seen = true)
        val result = FlagResolver.resolve(server, listOf(op(9, 1, FlagPatch())))
        assertEquals(server, result.merged)
        assertEquals(emptyList<Long>(), result.remaining)
        assertEquals(listOf(9L), result.completed)
    }

    @Test
    fun `the answered flag is merged like the others`() {
        val result = FlagResolver.resolve(
            Flags(answered = false),
            listOf(op(1, 1, FlagPatch(answered = true)))
        )
        assertEquals(Flags(answered = true), result.merged)
    }
}
