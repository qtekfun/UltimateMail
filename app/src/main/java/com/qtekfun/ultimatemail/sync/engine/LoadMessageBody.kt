// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.dao.AttachmentDao
import com.qtekfun.ultimatemail.data.local.dao.MessageDao
import com.qtekfun.ultimatemail.data.local.entity.AttachmentEntity
import com.qtekfun.ultimatemail.data.local.entity.MessageEntity
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MessageBody
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
        val message = messages.getById(messageId)
        return when {
            message == null -> BodyResult.NotFound

            message.bodyText != null || message.bodyHtml != null ->
                BodyResult.Loaded(message.bodyText, message.bodyHtml)

            // A local-only row: nothing of it is on the server.
            message.uid <= 0 -> BodyResult.NotFound

            else -> download(message)
        }
    }

    private suspend fun download(message: MessageEntity): BodyResult {
        val leased = sessions.withSession(message.accountId) {
            it.fetchBody(message.folderPath, message.uid)
        }
        return when (leased) {
            is Leased.Ok -> when (val fetched = leased.value) {
                is MailResult.Success -> save(message, fetched.value)
                MailResult.NotFound -> BodyResult.NotFound
                MailResult.AuthenticationFailed -> BodyResult.AuthenticationRequired
                is MailResult.Failure -> BodyResult.Failed(fetched.toProblem())
            }

            Leased.AuthRequired -> BodyResult.AuthenticationRequired

            is Leased.Failed -> BodyResult.Failed(leased.failure.toProblem())

            Leased.NoAccount -> BodyResult.NotFound
        }
    }

    private suspend fun save(message: MessageEntity, body: MessageBody): BodyResult {
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
