// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.dao.AttachmentDao
import com.qtekfun.ultimatemail.data.local.dao.MessageDao
import com.qtekfun.ultimatemail.data.local.entity.AttachmentEntity
import com.qtekfun.ultimatemail.data.local.entity.MessageEntity
import com.qtekfun.ultimatemail.domain.conversation.ContentIds
import com.qtekfun.ultimatemail.domain.mail.MessageBody
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The text and HTML a message now has in Room. */
data class StoredBody(val text: String?, val html: String?)

/**
 * Saves a fetched body and the list of its attachments into Room. Both the reader
 * ([LoadMessageBody]) and the sync ([BodyDownloader]) save through here, one at a time: when
 * both fetched the same message, the second finds the body already stored and keeps it, so the
 * attachments are not listed twice.
 */
@Singleton
class BodyStore @Inject constructor(
    private val messages: MessageDao,
    private val attachments: AttachmentDao
) {
    private val lock = Mutex()

    suspend fun save(message: MessageEntity, body: MessageBody): StoredBody = lock.withLock {
        val current = messages.getById(message.id)
        if (current != null && (current.bodyText != null || current.bodyHtml != null)) {
            return@withLock StoredBody(current.bodyText, current.bodyHtml)
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
                    size = it.size,
                    contentId = it.contentId?.let(ContentIds::normalize),
                    inline = it.inline
                )
            }
        )
        StoredBody(text, body.html)
    }
}
