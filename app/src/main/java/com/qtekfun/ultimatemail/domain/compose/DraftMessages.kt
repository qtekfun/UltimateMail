// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.data.local.entity.AccountEntity
import com.qtekfun.ultimatemail.data.local.model.DraftKind
import com.qtekfun.ultimatemail.domain.mail.MailAddress
import com.qtekfun.ultimatemail.domain.mail.OutgoingMessage
import com.qtekfun.ultimatemail.sync.engine.PayloadAttachment
import com.qtekfun.ultimatemail.sync.engine.PayloadSource
import com.qtekfun.ultimatemail.sync.engine.QueuedMessage

/** Turns a [Draft] into the message a SEND or SAVE_DRAFT operation carries. */
internal object DraftMessages {
    /** The address mail from [account] is sent as. */
    fun sender(account: AccountEntity) =
        MailAddress(account.email, account.displayName.ifBlank { null })

    /**
     * The payload content of [draft] as sent from [account] under [messageId]. Attachments are
     * references to the outbox files; a source message is only marked after a real send, so a
     * server copy of a draft does not carry one.
     */
    fun queued(
        draft: Draft,
        account: AccountEntity,
        messageId: String,
        attachments: List<DraftAttachment>,
        forSending: Boolean
    ): QueuedMessage {
        val message = OutgoingMessage(
            from = sender(account),
            to = draft.to.map(::ascii),
            cc = draft.cc.map(::ascii),
            bcc = draft.bcc.map(::ascii),
            subject = draft.subject,
            text = draft.body,
            inReplyTo = draft.inReplyTo,
            references = draft.references,
            messageId = messageId
        )
        val source = draft.source?.takeIf { forSending && draft.kind != DraftKind.NEW }?.let {
            PayloadSource(
                it.accountId,
                it.folderPath,
                it.uid,
                it.messageId,
                draft.kind == DraftKind.FORWARD
            )
        }
        return QueuedMessage(
            message = message,
            attachments = if (forSending) {
                attachments.map { PayloadAttachment(it.filePath, it.displayName, it.mimeType) }
            } else {
                emptyList()
            },
            draftId = draft.id,
            draftKey = draft.key,
            revision = draft.revision,
            source = source
        )
    }

    private fun ascii(address: MailAddress) = RecipientParser.toAscii(address) ?: address
}
