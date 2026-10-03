// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.mail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MailResultTest {
    @Test
    fun `map transforms a success and leaves a failure alone`() {
        assertEquals(MailResult.Success(4), MailResult.Success(2).map { it * 2 })
        val failure: MailResult<Int> = MailResult.Timeout
        assertEquals(MailResult.Timeout, failure.map { it * 2 })
    }

    @Test
    fun `getOrNull`() {
        assertEquals(7, MailResult.Success(7).getOrNull())
        assertNull((MailResult.NetworkUnavailable as MailResult<Int>).getOrNull())
    }

    @Test
    fun `only network trouble and transient rejections are retryable`() {
        assertTrue(MailResult.NetworkUnavailable.isRetryable)
        assertTrue(MailResult.Timeout.isRetryable)
        assertTrue(MailResult.ServerRejected(RejectionKind.NO, permanent = false).isRetryable)
        assertFalse(MailResult.ServerRejected(RejectionKind.BAD, permanent = true).isRetryable)
        listOf(
            MailResult.AuthenticationFailed,
            MailResult.CertificateRejected,
            MailResult.NotFound,
            MailResult.Unsupported("x"),
            MailResult.Protocol,
            MailResult.Unknown
        ).forEach { assertFalse(it.isRetryable, it.toString()) }
    }

    @Test
    fun `uid ranges are validated`() {
        assertThrows(IllegalArgumentException::class.java) { UidRange(0) }
        assertThrows(IllegalArgumentException::class.java) { UidRange(5, 4) }
        assertEquals(UidRange(3, 3), UidRange(3, 3))
    }

    @Test
    fun `private content never shows in toString`() {
        val address = MailAddress("alice@example.test", "Alice")
        val header = MessageHeader(
            uid = 1, messageId = "<id@example.test>", subject = "secret subject", from = address,
            to = listOf(address), cc = emptyList(), date = null, flags = MessageFlags(), size = 1,
            hasAttachments = false
        )
        val texts = listOf(
            address.toString(),
            header.toString(),
            MailFolder("Secret/Folder", "Folder", '/', MailFolderRole.OTHER, true).toString(),
            AttachmentInfo("1", "secret.pdf", "application/pdf", 1, null, false).toString(),
            MessageBody("secret text", "<p>secret</p>", emptyList()).toString(),
            OutgoingMessage(
                address,
                listOf(address),
                subject = "secret subject",
                text = "secret text"
            ).toString(),
            OutgoingAttachment("secret.pdf", "application/pdf", byteArrayOf(1)).toString(),
            GmailMetadata(1, 2, listOf("secret label")).toString()
        )
        texts.forEach { assertFalse(it.contains("secret") || it.contains("alice"), it) }
    }
}
