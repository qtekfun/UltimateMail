// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.thread

import java.time.Instant
import java.util.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

/** A message sent [day] days after the epoch; the default subject is unique so it never links. */
internal fun msg(
    uid: Long,
    messageId: String? = "m$uid@test",
    references: List<String> = emptyList(),
    inReplyTo: String? = null,
    subject: String? = "unique subject $uid",
    day: Long = uid,
    gmail: String? = null,
    server: String? = null,
    account: Long = 1,
    folder: String = "INBOX"
) = ThreadMessage(
    ref = MessageRef(account, folder, uid),
    messageId = messageId,
    inReplyTo = inReplyTo,
    references = references,
    subject = subject,
    sentAt = Instant.ofEpochMilli(day * DAY_MILLIS),
    gmailThreadId = gmail,
    serverThreadId = server
)

private fun ThreadUpdate.id(uid: Long, account: Long = 1, folder: String = "INBOX"): String =
    assignments.getValue(MessageRef(account, folder, uid))

class ThreadResolverTest {
    private val resolver = ThreadResolver()

    @Test
    fun `gmail thread id is the thread, whatever the headers say`() {
        val update = resolver.add(
            listOf(
                msg(1, gmail = "T1"),
                msg(2, gmail = "T1", subject = "something else"),
                msg(3, gmail = "T2", references = listOf("m1@test"), subject = "unique subject 1")
            )
        )
        assertEquals(update.id(1), update.id(2))
        assertNotEquals(update.id(1), update.id(3))
        assertEquals("gmail:1:T1", update.id(1))
    }

    @Test
    fun `gmail threads of different accounts do not mix`() {
        val update = resolver.add(
            listOf(msg(1, gmail = "T1", account = 1), msg(1, gmail = "T1", account = 2))
        )
        assertNotEquals(update.id(1, account = 1), update.id(1, account = 2))
    }

    @Test
    fun `a blank gmail id counts as absent`() {
        val update = resolver.add(
            listOf(
                msg(1, gmail = " ", server = "S"),
                msg(2, gmail = "", references = listOf("m1@test"))
            )
        )
        assertEquals("thread:1:INBOX:S", update.id(1))
        assertEquals(update.id(1), update.id(2))
    }

    @Test
    fun `server thread id groups messages of the same folder`() {
        val update = resolver.add(
            listOf(
                msg(1, server = "7"),
                msg(2, server = "7", subject = "other"),
                msg(3, server = "7", folder = "Archive"),
                msg(4, server = "8", references = listOf("m1@test"), subject = "unique subject 1")
            )
        )
        assertEquals(update.id(1), update.id(2))
        assertNotEquals(update.id(1), update.id(3, folder = "Archive"))
        assertNotEquals(update.id(1), update.id(4))
    }

    @Test
    fun `gmail beats the server thread when both are present`() {
        val update = resolver.add(listOf(msg(1, gmail = "G", server = "S")))
        assertEquals("gmail:1:G", update.id(1))
    }

    @Test
    fun `a message without server data joins an authority thread through its references`() {
        val update = resolver.add(
            listOf(msg(1, gmail = "G"), msg(2, references = listOf("<M1@test>")))
        )
        assertEquals("gmail:1:G", update.id(2))
    }

    @Test
    fun `references and in-reply-to link a conversation`() {
        val update = resolver.add(
            listOf(
                msg(1),
                msg(2, inReplyTo = "<m1@test>"),
                msg(3, references = listOf("<m1@test>", "<m2@test>"), inReplyTo = "<m2@test>"),
                msg(4)
            )
        )
        assertEquals(update.id(1), update.id(2))
        assertEquals(update.id(1), update.id(3))
        assertNotEquals(update.id(1), update.id(4))
    }

    @Test
    fun `a missing intermediate message still ties its replies together`() {
        val update = resolver.add(
            listOf(
                msg(1),
                msg(3, references = listOf("m1@test", "gone@test"), inReplyTo = "gone@test"),
                msg(4, inReplyTo = "gone@test")
            )
        )
        assertEquals(update.id(1), update.id(3))
        assertEquals(update.id(1), update.id(4))
    }

    @Test
    fun `two replies to a message that never arrived share a thread`() {
        val update = resolver.add(
            listOf(msg(1, inReplyTo = "lost@test"), msg(2, references = listOf("lost@test")))
        )
        assertEquals(update.id(1), update.id(2))
    }

    @Test
    fun `reference cycles terminate and form one thread`() {
        val update = resolver.add(
            listOf(
                msg(1, references = listOf("m2@test")),
                msg(2, references = listOf("m3@test")),
                msg(3, references = listOf("m1@test", "m3@test"), inReplyTo = "m3@test")
            )
        )
        assertEquals(update.id(1), update.id(2))
        assertEquals(update.id(1), update.id(3))
    }

    @Test
    fun `a message citing itself changes nothing`() {
        val update = resolver.add(
            listOf(msg(1, references = listOf("m1@test"), inReplyTo = "<M1@test>"), msg(2))
        )
        assertNotEquals(update.id(1), update.id(2))
    }

    @Test
    fun `garbage message ids link nothing`() {
        val garbage = listOf("", "   ", "<>", "not an id", "<<a@b>>")
        val update = resolver.add(
            listOf(
                msg(1, messageId = "", references = garbage),
                msg(2, messageId = "not an id", references = garbage, inReplyTo = "<>")
            )
        )
        assertNotEquals(update.id(1), update.id(2))
    }

    @Test
    fun `message ids match regardless of brackets and case`() {
        val update = resolver.add(
            listOf(msg(1, messageId = "<Abc@Host.Test>"), msg(2, inReplyTo = "abc@host.test"))
        )
        assertEquals(update.id(1), update.id(2))
    }

    @Test
    fun `the same message id in two folders is one thread`() {
        val update = resolver.add(
            listOf(
                msg(1, messageId = "same@test", subject = "x", folder = "INBOX"),
                msg(9, messageId = "same@test", subject = "x", folder = "Archive")
            )
        )
        assertEquals(update.id(1), update.id(9, folder = "Archive"))
    }

    @Test
    fun `message ids never link across accounts`() {
        val update = resolver.add(
            listOf(
                msg(1, messageId = "same@test", account = 1),
                msg(1, messageId = "same@test", account = 2)
            )
        )
        assertNotEquals(update.id(1, account = 1), update.id(1, account = 2))
    }

    @Test
    fun `missing headers leave each message in its own thread`() {
        val update = resolver.add(
            listOf(
                msg(1, messageId = null, subject = null),
                msg(2, messageId = null, subject = null),
                msg(3, messageId = null, subject = "")
            )
        )
        assertEquals(3, update.assignments.values.toSet().size)
    }

    @Test
    fun `duplicates are one message and re-adding is idempotent`() {
        val first = resolver.add(listOf(msg(1), msg(1, references = listOf("zz@test")), msg(2)))
        assertEquals(2, first.assignments.size)
        val again = resolver.add(listOf(msg(1), msg(2, inReplyTo = "m1@test")))
        assertEquals(first.id(1), again.id(1))
        assertEquals(first.id(2), again.id(2))
        assertTrue(again.merged.isEmpty())
    }

    @Test
    fun `an empty batch does nothing`() {
        val update = resolver.add(emptyList())
        assertTrue(update.assignments.isEmpty() && update.merged.isEmpty())
    }

    // --- Subject fallback -------------------------------------------------------------------

    @Test
    fun `a reply without references joins the original by subject`() {
        val update = resolver.add(
            listOf(
                msg(1, subject = "Lunch plans", day = 0),
                msg(2, subject = "RE: lunch   PLANS", day = 3),
                msg(3, subject = "RV: Lunch plans", day = 4),
                msg(4, subject = "REENVIO: Lunch plans", day = 5)
            )
        )
        assertEquals(1, update.assignments.values.toSet().size)
    }

    @Test
    fun `subject linking respects the window`() {
        val update = resolver.add(
            listOf(msg(1, subject = "Report", day = 0), msg(2, subject = "Re: Report", day = 31))
        )
        assertNotEquals(update.id(1), update.id(2))
    }

    @Test
    fun `the window is configurable and inclusive`() {
        val messages = listOf(
            msg(1, subject = "Report", day = 0),
            msg(2, subject = "Re: Report", day = 10)
        )
        val inside = ThreadResolver(ThreadConfig(10 * DAY_MILLIS)).add(messages)
        assertEquals(inside.id(1), inside.id(2))
        val outside = ThreadResolver(ThreadConfig(10 * DAY_MILLIS - 1)).add(messages)
        assertNotEquals(outside.id(1), outside.id(2))
        val thirtyDays = listOf(
            msg(1, subject = "Report", day = 0),
            msg(2, subject = "Re: Report", day = 30)
        )
        val byDefault = ThreadResolver().add(thirtyDays)
        assertEquals(byDefault.id(1), byDefault.id(2))
    }

    @Test
    fun `two new conversations with the same subject stay apart`() {
        val update = resolver.add(
            listOf(msg(1, subject = "Hello", day = 0), msg(2, subject = "Hello", day = 1))
        )
        assertNotEquals(update.id(1), update.id(2))
    }

    @Test
    fun `a reply with references never uses the subject`() {
        val update = resolver.add(
            listOf(
                msg(1, subject = "Hello", day = 0),
                msg(2, subject = "Re: Hello", inReplyTo = "other-thread@test", day = 1)
            )
        )
        assertNotEquals(update.id(1), update.id(2))
    }

    @Test
    fun `messages with server authority never use the subject`() {
        val update = resolver.add(
            listOf(
                msg(1, subject = "Hello", day = 0),
                msg(2, subject = "Re: Hello", day = 1, gmail = "G"),
                msg(3, subject = "Re: Hello", day = 2, server = "S"),
                msg(4, subject = "Hello", day = 3, gmail = "G")
            )
        )
        assertNotEquals(update.id(1), update.id(2))
        assertNotEquals(update.id(1), update.id(3))
        assertEquals(update.id(2), update.id(4))
    }

    @Test
    fun `an empty subject or prefix-only subject never links`() {
        val update = resolver.add(
            listOf(
                msg(1, subject = "Re:", day = 0),
                msg(2, subject = "Re:", day = 1),
                msg(3, subject = null, day = 2)
            )
        )
        assertEquals(3, update.assignments.values.toSet().size)
    }

    @Test
    fun `subjects with accents and unicode compare equal`() {
        val update = resolver.add(
            listOf(
                msg(1, subject = "Reunión del Área 日本", day = 0),
                msg(2, subject = "RE: REUNIÓN DEL ÁREA 日本", day = 1)
            )
        )
        assertEquals(update.id(1), update.id(2))
    }

    @Test
    fun `subjects never link across accounts`() {
        val update = resolver.add(
            listOf(
                msg(1, subject = "Hello", account = 1),
                msg(2, subject = "Re: Hello", account = 2)
            )
        )
        assertNotEquals(update.id(1, account = 1), update.id(2, account = 2))
    }

    @Test
    fun `a reply joins the closest earlier message with that subject`() {
        val update = resolver.add(
            listOf(
                msg(1, subject = "Status", day = 0),
                msg(2, subject = "Status", day = 10),
                msg(3, subject = "Re: Status", day = 11)
            )
        )
        assertEquals(update.id(2), update.id(3))
        assertNotEquals(update.id(1), update.id(3))
    }

    @Test
    fun `equal send times break ties by message key`() {
        val update = resolver.add(
            listOf(
                msg(5, subject = "Status", day = 4),
                msg(6, subject = "Re: Status", day = 4),
                msg(2, subject = "Status", day = 4)
            )
        )
        // Order is (time, key): 1/INBOX/2, 1/INBOX/5, 1/INBOX/6 -> the reply follows uid 5.
        assertEquals(update.id(5), update.id(6))
        assertNotEquals(update.id(2), update.id(6))
    }

    @Test
    fun `a reply sent before its original arrived joins it later without changing its id`() {
        val reply = resolver.add(listOf(msg(2, subject = "Re: Status", day = 20))).id(2)
        val update = resolver.add(listOf(msg(1, subject = "Status", day = 15)))
        assertEquals(reply, update.id(1))
        assertTrue(update.merged.isEmpty())
    }

    @Test
    fun `a late original does not steal a reply already linked to a nearer one`() {
        val first = resolver.add(
            listOf(msg(1, subject = "Status", day = 10), msg(2, subject = "Re: Status", day = 12))
        )
        val update = resolver.add(listOf(msg(3, subject = "Status", day = 11)))
        assertEquals(first.id(1), first.id(2))
        assertNotEquals(first.id(2), update.id(3))
    }

    @Test
    fun `a late original outside the window leaves the reply alone`() {
        val reply = resolver.add(listOf(msg(2, subject = "Re: Status", day = 100))).id(2)
        val update = resolver.add(listOf(msg(1, subject = "Status", day = 1)))
        assertNotEquals(reply, update.id(1))
    }

    @Test
    fun `a subject link is refused when message ids put the messages in different threads`() {
        val update = resolver.add(
            listOf(
                msg(1, messageId = "g1@test", gmail = "G1", day = 0),
                msg(2, subject = "Status", references = listOf("g1@test"), day = 1),
                msg(3, messageId = "x@test", subject = "Re: Status", day = 2),
                msg(4, gmail = "G2", references = listOf("x@test"), day = 3)
            )
        )
        assertEquals("gmail:1:G1", update.id(2))
        assertEquals("gmail:1:G2", update.id(3))
    }

    // --- Merging and stability --------------------------------------------------------------

    @Test
    fun `a message that references two threads merges them into the oldest one`() {
        val first = resolver.add(listOf(msg(1, day = 1), msg(2, day = 2)))
        val update = resolver.add(
            listOf(msg(3, day = 3, references = listOf("m1@test", "m2@test")))
        )
        assertEquals(first.id(1), update.id(3))
        assertEquals(mapOf(first.id(2) to first.id(1)), update.merged)
    }

    @Test
    fun `the surviving id is the oldest thread whatever the arrival order`() {
        val newer = resolver.add(listOf(msg(1, day = 5))).id(1)
        val older = resolver.add(listOf(msg(2, day = 1))).id(2)
        val update = resolver.add(
            listOf(msg(3, day = 6, references = listOf("m1@test", "m2@test")))
        )
        assertEquals(older, update.id(3))
        assertEquals(mapOf(newer to older), update.merged)
    }

    @Test
    fun `equally old threads merge into the smaller key`() {
        val a = resolver.add(listOf(msg(8, day = 2))).id(8)
        val b = resolver.add(listOf(msg(3, day = 2))).id(3)
        val update = resolver.add(
            listOf(msg(9, day = 3, references = listOf("m8@test", "m3@test")))
        )
        assertEquals(b, update.id(9))
        assertEquals(mapOf(a to b), update.merged)
    }

    @Test
    fun `merging several threads reports each and resolves chains`() {
        val ids = (1L..3L).map { resolver.add(listOf(msg(it, day = it))).id(it) }
        val update = resolver.add(
            listOf(msg(4, day = 4, references = listOf("m3@test", "m2@test", "m1@test")))
        )
        assertEquals(ids[0], update.id(4))
        assertEquals(mapOf(ids[1] to ids[0], ids[2] to ids[0]), update.merged)
    }

    @Test
    fun `a local thread merged into a gmail thread reports the local id as merged`() {
        val local = resolver.add(listOf(msg(1, day = 1))).id(1)
        resolver.add(listOf(msg(2, day = 2, gmail = "G")))
        val update = resolver.add(
            listOf(msg(3, day = 3, references = listOf("m1@test", "m2@test")))
        )
        assertEquals("gmail:1:G", update.id(3))
        assertEquals(mapOf(local to "gmail:1:G"), update.merged)
    }

    @Test
    fun `a message referencing two authority threads does not merge them`() {
        val update = resolver.add(
            listOf(
                msg(1, gmail = "A"),
                msg(2, gmail = "B"),
                msg(3, references = listOf("m1@test", "m2@test"))
            )
        )
        assertEquals("gmail:1:A", update.id(3))
        assertNotEquals(update.id(1), update.id(2))
        assertTrue(update.merged.isEmpty())
    }

    @Test
    fun `threads created and merged within one batch are not reported`() {
        val update = resolver.add(
            listOf(
                msg(1, day = 1),
                msg(2, day = 2),
                msg(3, day = 3, references = listOf("m1@test", "m2@test"))
            )
        )
        assertEquals(1, update.assignments.values.toSet().size)
        assertTrue(update.merged.isEmpty())
    }

    @Test
    fun `an older message joining an existing thread leaves its id alone`() {
        val first = resolver.add(listOf(msg(5, day = 5)))
        val update = resolver.add(listOf(msg(0, day = 0, inReplyTo = "m5@test")))
        assertEquals(first.id(5), update.id(0))
        assertTrue(update.merged.isEmpty())
    }

    @Test
    fun `adding unrelated messages keeps every existing id`() {
        val first = resolver.add((1L..20L).map { msg(it) })
        val second = resolver.add((21L..40L).map { msg(it) } + msg(41, inReplyTo = "m7@test"))
        (1L..20L).forEach { assertEquals(first.id(it), resolver.add(listOf(msg(it))).id(it)) }
        assertEquals(first.id(7), second.id(41))
        assertTrue(second.merged.isEmpty())
    }

    @Test
    fun `batch order does not change the result`() {
        val random = Random(7)
        val messages = (1L..60L).map { uid ->
            val parent = if (uid > 1 &&
                random.nextInt(3) != 0
            ) {
                random.nextInt(uid.toInt() - 1) + 1
            } else {
                0
            }
            msg(
                uid,
                references = if (parent == 0) emptyList() else listOf("m$parent@test"),
                subject = if (random.nextBoolean()) "Re: Topic ${uid % 4}" else "Topic ${uid % 4}",
                day = uid
            )
        }
        val expected = ThreadResolver().add(messages).assignments
        repeat(5) {
            val shuffled = messages.shuffled(random)
            assertEquals(expected, ThreadResolver().add(shuffled).assignments)
        }
    }
}
