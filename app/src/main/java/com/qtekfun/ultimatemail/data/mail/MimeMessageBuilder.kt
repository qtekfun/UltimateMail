// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.mail

import com.qtekfun.ultimatemail.domain.mail.MailAddress
import com.qtekfun.ultimatemail.domain.mail.OutgoingMessage
import jakarta.activation.DataHandler
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import java.util.Date
import java.util.Properties
import java.util.UUID

/** Builds the MIME form of an [OutgoingMessage], shared by SMTP sending and draft saving. */
internal object MimeMessageBuilder {
    private const val CHARSET = "UTF-8"
    private const val FALLBACK_DOMAIN = "localhost"

    /** A session with no network settings, only for building and parsing MIME. */
    val mimeSession: Session = Session.getInstance(Properties())

    @Throws(MessagingException::class)
    fun build(message: OutgoingMessage): MimeMessage {
        val messageId = normalizeId(message.messageId) ?: generateId(message.from)
        val mime = object : MimeMessage(mimeSession) {
            override fun updateMessageID() {
                setHeader("Message-ID", messageId)
            }
        }
        mime.setFrom(message.from.toInternet())
        mime.setRecipients(
            Message.RecipientType.TO,
            message.to.map {
                it.toInternet()
            }.toTypedArray()
        )
        mime.setRecipients(
            Message.RecipientType.CC,
            message.cc.map {
                it.toInternet()
            }.toTypedArray()
        )
        mime.setRecipients(
            Message.RecipientType.BCC,
            message.bcc.map {
                it.toInternet()
            }.toTypedArray()
        )
        mime.setSubject(message.subject, CHARSET)
        // Without a Date header the copy kept in Sent would show no time.
        mime.sentDate = Date()
        message.inReplyTo?.let { mime.setHeader("In-Reply-To", it) }
        if (message.references.isNotEmpty()) {
            mime.setHeader("References", message.references.joinToString(" "))
        }
        mime.setContentOf(message)
        mime.saveChanges()
        return mime
    }

    private fun MimeMessage.setContentOf(message: OutgoingMessage) {
        if (message.attachments.isEmpty()) {
            val html = message.html
            if (html ==
                null
            ) {
                setText(message.text, CHARSET)
            } else {
                setContent(alternative(message.text, html))
            }
            return
        }
        val mixed = MimeMultipart("mixed")
        mixed.addBodyPart(
            MimeBodyPart().apply {
                val html = message.html
                if (html ==
                    null
                ) {
                    setText(message.text, CHARSET)
                } else {
                    setContent(alternative(message.text, html))
                }
            }
        )
        message.attachments.forEach { attachment ->
            mixed.addBodyPart(
                MimeBodyPart().apply {
                    dataHandler =
                        DataHandler(ByteArrayDataSource(attachment.content, attachment.mimeType))
                    fileName = attachment.fileName
                    disposition = Part.ATTACHMENT
                    attachment.contentId?.let { setContentID(it) }
                }
            )
        }
        setContent(mixed)
    }

    private fun alternative(text: String, html: String) = MimeMultipart("alternative").apply {
        addBodyPart(MimeBodyPart().apply { setText(text, CHARSET, "plain") })
        addBodyPart(MimeBodyPart().apply { setText(html, CHARSET, "html") })
    }

    private fun MailAddress.toInternet() = InternetAddress(address, name, CHARSET)

    private fun normalizeId(id: String?): String? = id?.trim()?.takeIf { it.isNotEmpty() }?.let {
        if (it.startsWith("<")) it else "<$it>"
    }

    private fun generateId(from: MailAddress): String {
        val domain = from.address.substringAfter('@', FALLBACK_DOMAIN).ifBlank { FALLBACK_DOMAIN }
        return "<${UUID.randomUUID()}@$domain>"
    }
}
