// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.conflict

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SendResolverTest {
    @Test
    fun `an accepted send is done whatever Sent says`() {
        for (sent in SentLookup.entries) {
            assertEquals(SendDecision.Done, SendResolver.resolve(SendAttempt.ACCEPTED, sent))
        }
    }

    @Test
    fun `a refused send is retried even if Sent could not be read`() {
        for (sent in SentLookup.entries) {
            assertEquals(SendDecision.Retry, SendResolver.resolve(SendAttempt.REFUSED, sent))
        }
    }

    @Test
    fun `an ambiguous send found in Sent is done and never sent twice`() {
        assertEquals(
            SendDecision.Done,
            SendResolver.resolve(SendAttempt.AMBIGUOUS, SentLookup.FOUND)
        )
    }

    @Test
    fun `an ambiguous send absent from Sent is retried`() {
        assertEquals(
            SendDecision.Retry,
            SendResolver.resolve(SendAttempt.AMBIGUOUS, SentLookup.NOT_FOUND)
        )
    }

    @Test
    fun `an ambiguous send with Sent unreachable waits instead of risking a duplicate`() {
        assertEquals(
            SendDecision.ConfirmFirst,
            SendResolver.resolve(SendAttempt.AMBIGUOUS, SentLookup.UNAVAILABLE)
        )
    }
}
