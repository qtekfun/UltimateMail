// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.thread

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Linear-time checks. They count the algorithm's own steps instead of measuring time, so they
 * are deterministic: a quadratic algorithm needs billions of steps for 50,000 messages, and
 * doubling the input would multiply the count by about four instead of two.
 */
class ThreadScaleTest {
    private val size = 50_000

    /** Conversations of five messages, each replying to all the previous ones by References. */
    private fun conversations(count: Int): List<ThreadMessage> = (0 until count).map { i ->
        val position = i % CONVERSATION_LENGTH
        val first = i - position
        msg(
            uid = i.toLong(),
            messageId = "m$i@test",
            references = (first until i).map { "m$it@test" },
            inReplyTo = if (position == 0) null else "m${i - 1}@test",
            day = i.toLong() / 10
        )
    }

    /** No headers at all: every reply is found through its subject. */
    private fun subjectsOnly(count: Int): List<ThreadMessage> = (0 until count).map { i ->
        val topic = i / CONVERSATION_LENGTH
        msg(
            uid = i.toLong(),
            messageId = null,
            subject = if (i % CONVERSATION_LENGTH == 0) "Topic $topic" else "Re: Topic $topic",
            day = i.toLong() / 100
        )
    }

    private fun workFor(messages: List<ThreadMessage>): Long {
        val resolver = ThreadResolver()
        resolver.add(messages)
        return resolver.work
    }

    @Test
    fun `fifty thousand messages are threaded correctly and in linear work`() {
        val resolver = ThreadResolver()
        val update = resolver.add(conversations(size))
        assertEquals(size, update.assignments.size)
        assertEquals(size / CONVERSATION_LENGTH, update.assignments.values.toSet().size)
        assertEquals(
            update.assignments.getValue(MessageRef(1, "INBOX", 0)),
            update.assignments.getValue(MessageRef(1, "INBOX", 4))
        )
        assertTrue(resolver.work <= WORK_PER_MESSAGE * size, "work ${resolver.work}")
    }

    @Test
    fun `work doubles when the input doubles`() {
        val small = workFor(conversations(size / 2))
        val large = workFor(conversations(size))
        assertTrue(large < small * 3, "small=$small large=$large")
    }

    @Test
    fun `subject fallback over fifty thousand messages is linear`() {
        val resolver = ThreadResolver()
        val update = resolver.add(subjectsOnly(size))
        assertEquals(size / CONVERSATION_LENGTH, update.assignments.values.toSet().size)
        assertTrue(resolver.work <= WORK_PER_MESSAGE * size, "work ${resolver.work}")
        val small = workFor(subjectsOnly(size / 2))
        assertTrue(resolver.work < small * 3, "small=$small large=${resolver.work}")
    }

    @Test
    fun `adding in many small batches costs no more than one big batch`() {
        val messages = conversations(size)
        val resolver = ThreadResolver()
        messages.chunked(BATCH).forEach { resolver.add(it) }
        assertTrue(resolver.work <= WORK_PER_MESSAGE * size, "work ${resolver.work}")
    }

    @Test
    fun `one huge thread of chained replies stays linear`() {
        val messages = (0 until size).map { i ->
            msg(i.toLong(), inReplyTo = if (i == 0) null else "m${i - 1}@test", day = 0)
        }
        val resolver = ThreadResolver()
        val update = resolver.add(messages)
        assertEquals(1, update.assignments.values.toSet().size)
        assertTrue(resolver.work <= WORK_PER_MESSAGE * size, "work ${resolver.work}")
    }

    private companion object {
        const val CONVERSATION_LENGTH = 5
        const val BATCH = 100
        const val WORK_PER_MESSAGE = 64L
    }
}
