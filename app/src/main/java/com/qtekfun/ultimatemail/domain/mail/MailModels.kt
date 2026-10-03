// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.mail

import java.time.Instant

/** How the connection to a server is secured. Plain text does not exist (SPEC section 6). */
enum class TransportSecurity { TLS, STARTTLS }

/** Where a mail server is and how to secure the connection. */
data class MailServer(val host: String, val port: Int, val security: TransportSecurity)

/** What proves who the user is. [toString] never shows the secret. */
sealed interface MailCredentials {
    val username: String

    data class Password(override val username: String, val password: String) : MailCredentials {
        override fun toString(): String = "Password(REDACTED)"
    }

    /** XOAUTH2 with an access token the caller keeps fresh (RF-01). */
    data class OAuthBearer(override val username: String, val accessToken: String) :
        MailCredentials {
        override fun toString(): String = "OAuthBearer(REDACTED)"
    }
}

/** An e-mail address with an optional display name. [toString] hides both (never log them). */
data class MailAddress(val address: String, val name: String? = null) {
    override fun toString(): String = "MailAddress(REDACTED)"
}

/** Special folders detected with IMAP SPECIAL-USE (RF-02). */
enum class MailFolderRole { INBOX, SENT, DRAFTS, TRASH, ARCHIVE, JUNK, ALL_MAIL, STARRED, OTHER }

data class MailFolder(
    /** The full server path; the key to every other call. */
    val path: String,
    val name: String,
    val delimiter: Char?,
    val role: MailFolderRole,
    /** False for containers such as Gmail's "[Gmail]", which cannot hold messages. */
    val selectable: Boolean
) {
    override fun toString(): String = "MailFolder(role=$role)"
}

data class FolderStatus(
    val uidValidity: Long,
    val uidNext: Long,
    val messageCount: Int,
    /** Null when the server has no CONDSTORE. */
    val highestModSeq: Long?
)

/** An inclusive UID range; a null [last] means "to the end" (IMAP's `*`). */
data class UidRange(val first: Long, val last: Long? = null) {
    init {
        require(first >= 1) { "UIDs start at 1" }
        require(last == null || last >= first) { "last must not precede first" }
    }
}

enum class MailFlag { SEEN, ANSWERED, FLAGGED, DELETED, DRAFT }

data class MessageFlags(
    val seen: Boolean = false,
    val answered: Boolean = false,
    val flagged: Boolean = false,
    val deleted: Boolean = false,
    val draft: Boolean = false
)

/** What only Gmail's X-GM-EXT-1 extension provides (RF-02, RF-03). */
data class GmailMetadata(val threadId: Long, val messageId: Long, val labels: List<String>) {
    override fun toString(): String = "GmailMetadata(threadId=$threadId, messageId=$messageId)"
}

data class MessageHeader(
    val uid: Long,
    /** The Message-ID header, with its angle brackets; null if the message has none. */
    val messageId: String?,
    val subject: String?,
    val from: MailAddress?,
    val to: List<MailAddress>,
    val cc: List<MailAddress>,
    val date: Instant?,
    val flags: MessageFlags,
    val size: Long,
    val hasAttachments: Boolean,
    val inReplyTo: String? = null,
    val references: List<String> = emptyList(),
    /** Null unless the server is Gmail. */
    val gmail: GmailMetadata? = null
) {
    override fun toString(): String = "MessageHeader(uid=$uid)"
}

data class AttachmentInfo(
    /** Opaque; pass it back to [MailSession.fetchAttachment]. */
    val partId: String,
    val fileName: String?,
    val mimeType: String,
    val size: Long,
    val contentId: String?,
    val inline: Boolean
) {
    override fun toString(): String = "AttachmentInfo(partId=$partId, size=$size)"
}

data class MessageBody(
    val text: String?,
    val html: String?,
    val attachments: List<AttachmentInfo>
) {
    override fun toString(): String = "MessageBody(attachments=${attachments.size})"
}

/** The result of an operation on a set of UIDs: some may have vanished from the server. */
data class UidOperationResult(val applied: Set<Long>, val missing: Set<Long>)

class OutgoingAttachment(
    val fileName: String,
    val mimeType: String,
    val content: ByteArray,
    val contentId: String? = null
) {
    override fun toString(): String = "OutgoingAttachment(size=${content.size})"
}

class OutgoingMessage(
    val from: MailAddress,
    val to: List<MailAddress>,
    val cc: List<MailAddress> = emptyList(),
    val bcc: List<MailAddress> = emptyList(),
    val subject: String,
    val text: String,
    val html: String? = null,
    val attachments: List<OutgoingAttachment> = emptyList(),
    val inReplyTo: String? = null,
    val references: List<String> = emptyList(),
    /** Generated when null. Keep it: it is how a sent message is found again in Sent. */
    val messageId: String? = null
) {
    override fun toString(): String = "OutgoingMessage(attachments=${attachments.size})"
}
