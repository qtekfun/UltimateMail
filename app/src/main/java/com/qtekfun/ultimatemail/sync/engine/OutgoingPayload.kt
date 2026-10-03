// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.domain.mail.MailAddress
import com.qtekfun.ultimatemail.domain.mail.OutgoingMessage
import java.util.Base64
import java.util.UUID

/** A file of the outbox storage to attach when the message is built, read only at that moment. */
data class PayloadAttachment(val path: String, val fileName: String, val mimeType: String) {
    override fun toString(): String = "PayloadAttachment(REDACTED)"
}

/**
 * The message a SEND or SAVE_DRAFT answers: after a successful send its flag is set
 * ([forwarded]: `$Forwarded`, else `\Answered`), once the server still holds that UID for
 * [messageId].
 */
data class PayloadSource(
    val accountId: Long,
    val folderPath: String,
    val uid: Long,
    val messageId: String?,
    val forwarded: Boolean
) {
    override fun toString(): String = "PayloadSource(uid=$uid)"
}

/**
 * What a SEND or SAVE_DRAFT operation carries: the [message] (without attachment bytes), the
 * [attachments] to read from the outbox storage, and, for operations that come from a draft,
 * which one ([draftId], [draftKey], [revision] of the text the payload was made from) and the
 * [source] message to mark.
 */
data class QueuedMessage(
    val message: OutgoingMessage,
    val attachments: List<PayloadAttachment> = emptyList(),
    val draftId: Long? = null,
    val draftKey: String? = null,
    val revision: Int = 0,
    val source: PayloadSource? = null
) {
    override fun toString(): String = "QueuedMessage(draftId=$draftId)"
}

/**
 * The text stored in the payload of a SAVE_DRAFT or SEND operation: the message to put on the
 * server, one `key:value` line per field with every value in Base64, so no content can break the
 * format.
 *
 * Version `v1` (T10) holds just the message. Version `v2` (T18a) adds the draft the message
 * comes from, the source message to mark as answered or forwarded, and the attachments as
 * references to files, never as bytes inside a database row. [decode] and [decodeQueued] read
 * both, so operations queued before an update still run.
 */
object OutgoingPayload {
    private const val V1 = "v1"
    private const val V2 = "v2"

    /**
     * The payload of [message]. A message without a Message-ID gets one now: it is how a sent
     * message is found again in Sent, so it must be the same on every attempt.
     */
    fun encode(message: OutgoingMessage): String = encode(QueuedMessage(message))

    /** Like [encode] for a message that comes from a draft; plain messages stay on `v1`. */
    fun encode(queued: QueuedMessage): String {
        val message = queued.message
        val id =
            message.messageId
                ?: "<${UUID.randomUUID()}@${message.from.address.substringAfter('@', "localhost")}>"
        val v2 = queued.draftId != null || queued.attachments.isNotEmpty() || queued.source != null
        return buildList {
            add("version:${if (v2) V2 else V1}")
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
            queued.draftId?.let {
                add(line("draft", listOf(it, queued.draftKey.orEmpty(), queued.revision).join()))
            }
            queued.source?.let {
                add(
                    line(
                        "source",
                        listOf(
                            it.accountId,
                            it.folderPath,
                            it.uid,
                            it.messageId.orEmpty(),
                            it.forwarded
                        ).join()
                    )
                )
            }
            queued.attachments.forEach {
                add(line("attachment", listOf(it.path, it.fileName, it.mimeType).join()))
            }
        }.joinToString("\n")
    }

    /** The message in [payload], or null if it is not a payload this version wrote. */
    fun decode(payload: String): OutgoingMessage? = decodeQueued(payload)?.message

    /** Everything in [payload], or null if it is not a payload this version wrote. */
    fun decodeQueued(payload: String): QueuedMessage? = runCatching {
        val lines = payload.lines().map { it.substringBefore(':') to it.substringAfter(':') }
        require(lines.first() == "version" to V1 || lines.first() == "version" to V2)
        fun all(key: String) = lines.filter { it.first == key }.map { text(it.second) }
        val message = OutgoingMessage(
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
        val draft = all("draft").singleOrNull()?.fields()
        val source = all("source").singleOrNull()?.fields()?.let {
            PayloadSource(
                it[0].toLong(),
                it[1],
                it[2].toLong(),
                it[3].ifEmpty { null },
                it[4].toBooleanStrict()
            )
        }
        QueuedMessage(
            message = message,
            attachments = all("attachment").map {
                it.fields().let { part -> PayloadAttachment(part[0], part[1], part[2]) }
            },
            draftId = draft?.get(0)?.toLong(),
            draftKey = draft?.get(1)?.ifEmpty { null },
            revision = draft?.get(2)?.toInt() ?: 0,
            source = source
        )
    }.getOrNull()

    private fun line(key: String, value: String) =
        "$key:" + Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun text(encoded: String) = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)

    private fun List<Any>.join() = joinToString(FIELD_SEPARATOR)

    private fun String.fields(): List<String> = split(FIELD_SEPARATOR)

    /** Address and name in one value; an address has no line feed, so the first one splits. */
    private fun address(value: MailAddress) = value.address + "\n" + value.name.orEmpty()

    private fun parseAddress(text: String) =
        MailAddress(text.substringBefore('\n'), text.substringAfter('\n').ifEmpty { null })

    /** Unit separator: it cannot be part of a path, a name or a Message-ID. */
    private const val FIELD_SEPARATOR = "\u001F"
}
