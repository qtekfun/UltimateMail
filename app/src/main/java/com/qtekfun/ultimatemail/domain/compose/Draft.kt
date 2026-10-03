// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.data.local.entity.DraftEntity
import com.qtekfun.ultimatemail.data.local.entity.OutgoingAttachmentEntity
import com.qtekfun.ultimatemail.data.local.model.DraftKind
import com.qtekfun.ultimatemail.data.local.model.DraftState
import com.qtekfun.ultimatemail.domain.mail.MailAddress
import com.qtekfun.ultimatemail.domain.signature.ComposeKind
import com.qtekfun.ultimatemail.domain.signature.SignatureSettings
import java.time.Instant

/** The message a reply or forward answers, as stored with the draft. */
data class DraftSource(
    val accountId: Long,
    val folderPath: String,
    val uid: Long,
    val messageId: String?
) {
    override fun toString(): String = "DraftSource(uid=$uid)"
}

/** A file attached to a draft; [filePath] is the app-private copy. */
data class DraftAttachment(
    val id: Long,
    val draftId: Long,
    val displayName: String,
    val mimeType: String,
    val size: Long,
    val filePath: String
) {
    override fun toString(): String = "DraftAttachment(id=$id, size=$size)"
}

/**
 * A message being written (RF-07). [body] is plain text and holds the signature and the quoted
 * original as the user sees them. [kind] decides the subject prefix, the flag set on the
 * [source] after sending and where the signature goes.
 *
 * [state] is [DraftState.EDITING] while the composer owns it and [DraftState.OUTBOX] once
 * `SendDraft` handed it to the queue; the outbox says how that is going (`OutboxEntry`).
 * [toString] shows no content: never log a draft.
 */
data class Draft(
    val id: Long,
    /** The same on every device: names this draft in the server's Drafts folder. */
    val key: String,
    val accountId: Long,
    val kind: DraftKind,
    val state: DraftState,
    val to: List<MailAddress>,
    val cc: List<MailAddress>,
    val bcc: List<MailAddress>,
    val subject: String,
    val body: String,
    val inReplyTo: String?,
    val references: List<String>,
    val source: DraftSource?,
    /** The signature block text now in [body] (null: none), for swapping it on a sender change. */
    val signatureText: String?,
    val signatureBeforeQuote: Boolean,
    val serverMessageId: String?,
    /** Not yet uploaded to the server's Drafts folder in its latest form. */
    val dirty: Boolean,
    val revision: Int,
    val outgoingMessageId: String?,
    /** The SMTP server took the message; it is only being tidied up (Sent copy, flags). */
    val smtpAcceptedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    /** The recipients of all three kinds. */
    val recipients: List<MailAddress> get() = to + cc + bcc

    /** The signature now in [body] as settings, to swap it when the sender changes. */
    val signatureSettings: SignatureSettings?
        get() = signatureText?.let { SignatureSettings(it, enabled = true, signatureBeforeQuote) }

    /** The kind in the vocabulary of the signature editor. */
    val composeKind: ComposeKind
        get() = when (kind) {
            DraftKind.NEW -> ComposeKind.NEW
            DraftKind.REPLY, DraftKind.REPLY_ALL -> ComposeKind.REPLY
            DraftKind.FORWARD -> ComposeKind.FORWARD
        }

    override fun toString(): String = "Draft(id=$id, state=$state)"
}

internal fun DraftEntity.toDraft() = Draft(
    id = id,
    key = key,
    accountId = accountId,
    kind = kind,
    state = state,
    to = toAddresses.mapNotNull(RecipientParser::parse),
    cc = ccAddresses.mapNotNull(RecipientParser::parse),
    bcc = bccAddresses.mapNotNull(RecipientParser::parse),
    subject = subject,
    body = body,
    inReplyTo = inReplyTo,
    references = referenceIds,
    source = sourceAccountId?.let { account ->
        sourceFolderPath?.let { folder ->
            sourceUid?.let { uid -> DraftSource(account, folder, uid, sourceMessageId) }
        }
    },
    signatureText = signatureText,
    signatureBeforeQuote = signatureBeforeQuote,
    serverMessageId = serverMessageId,
    dirty = dirty,
    revision = revision,
    outgoingMessageId = outgoingMessageId,
    smtpAcceptedAt = smtpAcceptedAt,
    createdAt = createdAt,
    updatedAt = updatedAt
)

internal fun Draft.toEntity() = DraftEntity(
    id = id,
    key = key,
    accountId = accountId,
    kind = kind,
    state = state,
    toAddresses = to.map(RecipientParser::format),
    ccAddresses = cc.map(RecipientParser::format),
    bccAddresses = bcc.map(RecipientParser::format),
    subject = subject,
    body = body,
    inReplyTo = inReplyTo,
    referenceIds = references,
    sourceAccountId = source?.accountId,
    sourceFolderPath = source?.folderPath,
    sourceUid = source?.uid,
    sourceMessageId = source?.messageId,
    signatureText = signatureText,
    signatureBeforeQuote = signatureBeforeQuote,
    serverMessageId = serverMessageId,
    dirty = dirty,
    revision = revision,
    outgoingMessageId = outgoingMessageId,
    smtpAcceptedAt = smtpAcceptedAt,
    createdAt = createdAt,
    updatedAt = updatedAt
)

internal fun OutgoingAttachmentEntity.toAttachment() =
    DraftAttachment(id, draftId, displayName, mimeType, size, filePath)
