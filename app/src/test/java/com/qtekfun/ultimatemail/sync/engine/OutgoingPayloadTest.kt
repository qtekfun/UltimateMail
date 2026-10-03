// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.domain.mail.MailAddress
import com.qtekfun.ultimatemail.domain.mail.OutgoingMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OutgoingPayloadTest {
    private val full = OutgoingMessage(
        from = MailAddress("ana@example.test", "Ana: the \"boss\""),
        to = listOf(MailAddress("bob@example.test"), MailAddress("cy@example.test", "Cy")),
        cc = listOf(MailAddress("di@example.test")),
        bcc = listOf(MailAddress("ed@example.test")),
        subject = "Línea 1\nLínea 2: ¿ñ? ☃",
        text = "body\nwith lines\n\n:colons: and | pipes",
        html = "<p>html</p>",
        inReplyTo = "<in@x>",
        references = listOf("<r1@x>", "<r2@x>"),
        messageId = "<id@x>"
    )

    private fun assertSame(a: OutgoingMessage, b: OutgoingMessage) {
        assertEquals(a.from, b.from)
        assertEquals(a.to, b.to)
        assertEquals(a.cc, b.cc)
        assertEquals(a.bcc, b.bcc)
        assertEquals(a.subject, b.subject)
        assertEquals(a.text, b.text)
        assertEquals(a.html, b.html)
        assertEquals(a.inReplyTo, b.inReplyTo)
        assertEquals(a.references, b.references)
        assertEquals(a.messageId, b.messageId)
    }

    @Test
    fun `a message survives encoding with any text in it`() {
        assertSame(full, OutgoingPayload.decode(OutgoingPayload.encode(full))!!)
    }

    @Test
    fun `optional parts stay absent`() {
        val minimal = OutgoingMessage(
            from = MailAddress("a@x.test"),
            to = emptyList(),
            subject = "",
            text = "",
            messageId = "<m@x>"
        )

        val decoded = OutgoingPayload.decode(OutgoingPayload.encode(minimal))!!

        assertSame(minimal, decoded)
        assertNull(decoded.html)
        assertNull(decoded.inReplyTo)
    }

    @Test
    fun `a message without an id gets one, and it is the same on every decode`() {
        val payload = OutgoingPayload.encode(
            OutgoingMessage(
                from = MailAddress("a@host.test"),
                to = emptyList(),
                subject = "s",
                text = "t"
            )
        )

        val first = OutgoingPayload.decode(payload)!!.messageId
        val second = OutgoingPayload.decode(payload)!!.messageId

        assertNotNull(first)
        assertEquals(first, second)
        assertTrue(first!!.startsWith("<") && first.endsWith("@host.test>"))
    }

    @Test
    fun `an address without a domain still gets an id`() {
        val payload = OutgoingPayload.encode(
            OutgoingMessage(
                from = MailAddress("local"),
                to = emptyList(),
                subject = "s",
                text = "t"
            )
        )

        assertTrue(OutgoingPayload.decode(payload)!!.messageId!!.endsWith("@localhost>"))
    }

    @Test
    fun `anything that is not a payload decodes to null`() {
        assertNull(OutgoingPayload.decode(""))
        assertNull(OutgoingPayload.decode("garbage"))
        assertNull(OutgoingPayload.decode("version:v2"))
        assertNull(OutgoingPayload.decode("version:v9"))
        assertNull(OutgoingPayload.decode("version:v1\nfrom:!!!notbase64"))
        assertNull(OutgoingPayload.decode("version:v1"))
    }
}
