// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.dao.AttachmentDao
import com.qtekfun.ultimatemail.data.local.dao.MessageDao
import com.qtekfun.ultimatemail.data.local.entity.AttachmentEntity
import com.qtekfun.ultimatemail.domain.mail.MailResult
import javax.inject.Inject

/** The outcome of [LoadMessageBody]. */
sealed interface BodyResult {
    /** The body is in Room (the message row now carries it); attachments are listed there too. */
    data class Loaded(val text: String?, val html: String?) : BodyResult

    /** No such message, or one that only exists locally, or it is gone from the server. */
    data object NotFound : BodyResult

    data object AuthenticationRequired : BodyResult

    data class Failed(val problem: SyncProblem) : BodyResult
}

/**
 * Bodies and attachments stay on the server until a message is opened (RF-10). This fetches the
 * body of a message once, caches it in Room and lists its attachments (their content is fetched
 * separately, on demand).
 */
class LoadMessageBody @Inject constructor(
    private val messages: MessageDao,
    private val attachments: AttachmentDao,
    private val sessions: AccountSessions
) {
    suspend operator fun invoke(messageId: Long): BodyResult {
        val message = messages.getById(messageId) ?: return BodyResult.NotFound
        if (message.bodyText != null || message.bodyHtml != null) {
            return BodyResult.Loaded(message.bodyText, message.bodyHtml)
        }
        if (message.uid <= 0) return BodyResult.NotFound
        val leased = sessions.withSession(message.accountId) {
            it.fetchBody(message.folderPath, message.uid)
        }
        val fetched = when (leased) {
            is Leased.Ok -> leased.value
            Leased.AuthRequired -> return BodyResult.AuthenticationRequired
            is Leased.Failed -> return BodyResult.Failed(leased.failure.toProblem())
            Leased.NoAccount -> return BodyResult.NotFound
        }
        val body = when (fetched) {
            is MailResult.Success -> fetched.value
            MailResult.NotFound -> return BodyResult.NotFound
            MailResult.AuthenticationFailed -> return BodyResult.AuthenticationRequired
            is MailResult.Failure -> return BodyResult.Failed(fetched.toProblem())
        }
        // A message without any text still counts as fetched: the empty string says so.
        val text = body.text ?: if (body.html == null) "" else null
        messages.setBody(message.accountId, message.folderPath, message.uid, text, body.html)
        attachments.insert(
            body.attachments.map {
                AttachmentEntity(
                    messageId = message.id,
                    partId = it.partId,
                    fileName = it.fileName.orEmpty(),
                    mimeType = it.mimeType,
                    size = it.size
                )
            }
        )
        return BodyResult.Loaded(text, body.html)
    }
}
