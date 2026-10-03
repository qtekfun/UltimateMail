// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.domain.mail.MailAddress
import com.qtekfun.ultimatemail.domain.mail.OutgoingMessage
import java.util.Base64
import java.util.UUID

/**
 * The text stored in the payload of a SAVE_DRAFT or SEND operation: the message to put on the
 * server, one `key:value` line per field with every value in Base64, so no content can break the
 * format. Attachments are not part of it yet (they arrive with the composer, T18, as references
 * to files, not as bytes inside a database row).
 */
object OutgoingPayload {
    private const val VERSION = "v1"

    /**
     * The payload of [message]. A message without a Message-ID gets one now: it is how a sent
     * message is found again in Sent, so it must be the same on every attempt.
     */
    fun encode(message: OutgoingMessage): String {
        val id = message.messageId ?: "<${UUID.randomUUID()}@${message.from.address.substringAfter('@', "localhost")}>"
        return buildList {
            add("version:$VERSION")
            add(line("from", address(message.from)))
            message.to.forEach { add(line("to", address(it))) }
            message.cc.forEach { add(line("cc", address(it))) }
            message.bcc.forEach { add(line("bcc", address(it))) }
            add(line("subject", message.subject))
            add(line("text", message.text))
            message.html?.let { add(line("html", it)) }
            message.inReplyTo?.let { add(line("inReplyTo", it)) }
            message.references.forEach { add(line("reference", it)) }
            add(line("messageId", id))
        }.joinToString("\n")
    }

    /** The message in [payload], or null if it is not a payload this version wrote. */
    fun decode(payload: String): OutgoingMessage? = runCatching {
        val lines = payload.lines().map { it.substringBefore(':') to it.substringAfter(':') }
        require(lines.first() == "version" to VERSION)
        fun all(key: String) = lines.filter { it.first == key }.map { text(it.second) }
        OutgoingMessage(
            from = parseAddress(all("from").single()),
            to = all("to").map(::parseAddress),
            cc = all("cc").map(::parseAddress),
            bcc = all("bcc").map(::parseAddress),
            subject = all("subject").single(),
            text = all("text").single(),
            html = all("html").singleOrNull(),
            inReplyTo = all("inReplyTo").singleOrNull(),
            references = all("reference"),
            messageId = all("messageId").single()
        )
    }.getOrNull()

    private fun line(key: String, value: String) =
        "$key:" + Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun text(encoded: String) = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)

    /** Address and name in one value; an address has no line feed, so the first one splits. */
    private fun address(value: MailAddress) = value.address + "\n" + value.name.orEmpty()

    private fun parseAddress(text: String) =
        MailAddress(text.substringBefore('\n'), text.substringAfter('\n').ifEmpty { null })
}
