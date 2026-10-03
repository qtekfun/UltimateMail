// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.data.local.model.DraftKind
import com.qtekfun.ultimatemail.domain.mail.MailAddress
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal fun source(
    from: MailAddress? = MailAddress("bob@example.test", "Bob"),
    replyTo: List<MailAddress> = emptyList(),
    to: List<MailAddress> = listOf(MailAddress("ana@example.test")),
    cc: List<MailAddress> = emptyList(),
    subject: String = "Hello",
    messageId: String? = "<m1@example.test>",
    references: List<String> = emptyList(),
    inReplyTo: String? = null,
    body: String? = "Hi Ana,\nsee you."
) = ComposeSource(
    accountId = 1,
    folderPath = "INBOX",
    uid = 7,
    messageId = messageId,
    from = from,
    replyTo = replyTo,
    to = to,
    cc = cc,
    subject = subject,
    sentAt = Instant.parse("2024-01-15T10:30:00Z"),
    inReplyTo = inReplyTo,
    references = references,
    bodyText = body
)

class ReplyRecipientsTest {
    private val own = setOf("ana@example.test")

    private fun addresses(list: List<MailAddress>) = list.map { it.address }

    @Test
    fun `a reply goes to the sender`() {
        val result = ReplyRecipientsRule.of(DraftKind.REPLY, source(), own)

        assertEquals(listOf("bob@example.test"), addresses(result.to))
        assertTrue(result.cc.isEmpty())
    }

    @Test
    fun `a reply goes to Reply-To instead of the sender when there is one`() {
        val result = ReplyRecipientsRule.of(
            DraftKind.REPLY,
            source(replyTo = listOf(MailAddress("list@example.test"))),
            own
        )

        assertEquals(listOf("list@example.test"), addresses(result.to))
    }

    @Test
    fun `a reply to a message the user sent goes to the people it was sent to`() {
        val result = ReplyRecipientsRule.of(
            DraftKind.REPLY,
            source(
                from = MailAddress("ANA@example.test"),
                to = listOf(MailAddress("bob@example.test"), MailAddress("ana@example.test"))
            ),
            own
        )

        assertEquals(listOf("bob@example.test"), addresses(result.to))
    }

    @Test
    fun `reply all adds To and Cc without the user and without repeats`() {
        val result = ReplyRecipientsRule.of(
            DraftKind.REPLY_ALL,
            source(
                to = listOf(
                    MailAddress("ana@example.test"),
                    MailAddress("Cy@Example.test", "Cy"),
                    MailAddress("bob@example.test")
                ),
                cc = listOf(
                    MailAddress("cy@example.test"),
                    MailAddress("DI@example.test"),
                    MailAddress("di@example.test"),
                    MailAddress("ANA@example.test")
                )
            ),
            own
        )

        assertEquals(listOf("bob@example.test", "Cy@Example.test"), addresses(result.to))
        assertEquals("Cy", result.to.last().name)
        assertEquals(listOf("DI@example.test"), addresses(result.cc))
    }

    @Test
    fun `reply all keeps Reply-To first`() {
        val result = ReplyRecipientsRule.of(
            DraftKind.REPLY_ALL,
            source(
                replyTo = listOf(MailAddress("list@example.test")),
                to = listOf(MailAddress("ana@example.test"), MailAddress("cy@example.test"))
            ),
            own
        )

        assertEquals(listOf("list@example.test", "cy@example.test"), addresses(result.to))
    }

    @Test
    fun `a message without a sender gives no recipient to reply to`() {
        val result = ReplyRecipientsRule.of(DraftKind.REPLY, source(from = null), own)

        assertTrue(result.to.isEmpty())
    }

    @Test
    fun `forwards and new messages start without recipients`() {
        listOf(DraftKind.FORWARD, DraftKind.NEW).forEach {
            val result = ReplyRecipientsRule.of(it, source(), own)

            assertTrue(result.to.isEmpty() && result.cc.isEmpty())
        }
    }

    @Test
    fun `the summary says only how many`() {
        assertEquals(
            "ReplyRecipients(to=1, cc=0)",
            ReplyRecipientsRule.of(DraftKind.REPLY, source(), own).toString()
        )
    }
}

class ComposeSubjectTest {
    @Test
    fun `a reply gets Re once`() {
        assertEquals("Re: Hello", ComposeSubject.reply("Hello"))
        assertEquals("Re: Hello", ComposeSubject.reply("  Hello "))
        assertEquals("Re: Hello", ComposeSubject.reply("Re: Hello"))
        assertEquals("RE: Hello", ComposeSubject.reply("RE: Hello"))
        assertEquals("Re: Re: Hello", ComposeSubject.reply("Re: Re: Hello"))
    }

    @Test
    fun `reply prefixes of other languages count and numbered ones too`() {
        assertEquals("AW: Hallo", ComposeSubject.reply("AW: Hallo"))
        assertEquals("SV: Hej", ComposeSubject.reply("SV: Hej"))
        assertEquals("RE[2]: Hello", ComposeSubject.reply("RE[2]: Hello"))
    }

    @Test
    fun `a forwarded subject is answered with Re in front`() {
        assertEquals("Re: Fwd: Hello", ComposeSubject.reply("Fwd: Hello"))
        assertEquals("Re: ", ComposeSubject.reply(""))
    }

    @Test
    fun `a forward gets Fwd once`() {
        assertEquals("Fwd: Hello", ComposeSubject.forward("Hello"))
        assertEquals("Fwd: Hello", ComposeSubject.forward("Fwd: Hello"))
        assertEquals("FW: Hello", ComposeSubject.forward("FW: Hello"))
        assertEquals("RV: Hola", ComposeSubject.forward("RV: Hola"))
        assertEquals("WG: Hallo", ComposeSubject.forward("WG: Hallo"))
        assertEquals("ENC: Olá", ComposeSubject.forward("ENC: Olá"))
        assertEquals("Fwd: Re: Hello", ComposeSubject.forward("Re: Hello"))
    }
}

class ReferenceChainTest {
    @Test
    fun `a reply points at its parent and extends the parent's references`() {
        val headers = ReferenceChain.forReply(
            source(messageId = "<m3@x>", references = listOf("<m1@x>", "<m2@x>"))
        )

        assertEquals("<m3@x>", headers.inReplyTo)
        assertEquals(listOf("<m1@x>", "<m2@x>", "<m3@x>"), headers.references)
    }

    @Test
    fun `without References the In-Reply-To of the parent starts the chain`() {
        val headers = ReferenceChain.forReply(source(messageId = "<m2@x>", inReplyTo = "<m1@x>"))

        assertEquals(listOf("<m1@x>", "<m2@x>"), headers.references)
    }

    @Test
    fun `a parent without a Message-ID leaves the chain as it was`() {
        val headers = ReferenceChain.forReply(
            source(messageId = null, references = listOf("<m1@x>"))
        )

        assertEquals(null, headers.inReplyTo)
        assertEquals(listOf("<m1@x>"), headers.references)
    }

    @Test
    fun `unusable ids and repeats are dropped`() {
        val headers = ReferenceChain.forReply(
            source(
                messageId = "<M2@x>",
                references = listOf("<m1@x>", "not valid", "", "<M1@X>", "<m2@x>")
            )
        )

        assertEquals(listOf("<m1@x>", "<M2@x>"), headers.references)
    }

    @Test
    fun `a garbage Message-ID is not a parent`() {
        val headers = ReferenceChain.forReply(source(messageId = "has space"))

        assertEquals(null, headers.inReplyTo)
        assertTrue(headers.references.isEmpty())
    }

    @Test
    fun `a long chain keeps the root and the newest ids`() {
        val chain = (1..30).map { "<m$it@x>" }

        val headers = ReferenceChain.forReply(source(messageId = "<m31@x>", references = chain))

        assertEquals(ReferenceChain.MAX_REFERENCES, headers.references.size)
        assertEquals("<m1@x>", headers.references.first())
        assertEquals("<m31@x>", headers.references.last())
        assertEquals("<m13@x>", headers.references[1])
    }

    @Test
    fun `a chain at the limit is kept whole`() {
        val chain = (1..ReferenceChain.MAX_REFERENCES - 1).map { "<m$it@x>" }

        val headers = ReferenceChain.forReply(source(messageId = "<last@x>", references = chain))

        assertEquals(ReferenceChain.MAX_REFERENCES, headers.references.size)
        assertEquals("<m2@x>", headers.references[1])
    }
}

class DraftMessageIdsTest {
    @Test
    fun `a server copy id carries the draft key and nothing else is taken for one`() {
        val id = DraftMessageIds.forServerCopy("abc123", "ana@example.test")

        assertEquals("abc123", DraftMessageIds.keyOf(id))
        assertEquals("abc123", DraftMessageIds.keyOf(id.removeSurrounding("<", ">")))
        assertEquals(null, DraftMessageIds.keyOf("<1234@example.test>"))
        assertEquals(null, DraftMessageIds.keyOf(null))
        assertEquals(null, DraftMessageIds.keyOf("<um-draft.@example.test>"))
        assertTrue(id.endsWith("@example.test>"))
    }

    @Test
    fun `every version of a draft has a different id and the same key`() {
        val first = DraftMessageIds.forServerCopy("k", "ana@example.test")
        val second = DraftMessageIds.forServerCopy("k", "ana@example.test")

        assertTrue(first != second)
        assertEquals(DraftMessageIds.keyOf(first), DraftMessageIds.keyOf(second))
    }

    @Test
    fun `keys are unique and a sent id is not a draft id`() {
        assertTrue(DraftMessageIds.newKey() != DraftMessageIds.newKey())
        val sent = DraftMessageIds.forSending("ana@example.test")
        assertTrue(sent.startsWith("<") && sent.endsWith("@example.test>"))
        assertEquals(null, DraftMessageIds.keyOf(sent))
    }

    @Test
    fun `an address without a domain still gets a usable id`() {
        assertTrue(DraftMessageIds.forSending("local").endsWith("@localhost>"))
        assertTrue(DraftMessageIds.forServerCopy("k", "local@").endsWith("@localhost>"))
    }
}
