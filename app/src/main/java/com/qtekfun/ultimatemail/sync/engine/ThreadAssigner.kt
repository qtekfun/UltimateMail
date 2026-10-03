// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.dao.MessageDao
import com.qtekfun.ultimatemail.domain.thread.MessageRef
import com.qtekfun.ultimatemail.domain.thread.ThreadMessage
import com.qtekfun.ultimatemail.domain.thread.ThreadResolver

/**
 * Gives new messages of one account their conversation id (RF-03) with [ThreadResolver]. The
 * resolver lives only for a sync run: it is fed the messages already stored the first time it is
 * needed, so replies find their conversation across runs, and every merge it reports is applied
 * to Room. Not thread safe; one per account run.
 */
internal class ThreadAssigner(private val accountId: Long, private val messages: MessageDao) {
    private var resolver: ThreadResolver? = null

    /** Forgets what was learned, after a folder was invalidated and its UIDs mean other messages. */
    fun reset() {
        resolver = null
    }

    suspend fun assign(batch: List<ThreadMessage>): Map<MessageRef, String> {
        val current = resolver ?: seed()
        val update = current.add(batch)
        update.merged.forEach { (lost, kept) -> messages.renameThread(accountId, lost, kept) }
        return update.assignments
    }

    private suspend fun seed(): ThreadResolver {
        val fresh = ThreadResolver()
        val stored = messages.threadSeeds(accountId)
        val assigned = fresh.add(
            stored.map {
                ThreadMessage(
                    ref = MessageRef(accountId, it.folderPath, it.uid),
                    messageId = it.messageId,
                    inReplyTo = it.inReplyTo,
                    references = it.referenceIds,
                    subject = it.subject,
                    sentAt = it.sentAt,
                    gmailThreadId = gmailThreadId(it.threadId)
                )
            }
        ).assignments
        // Stored ids that differ from what the headers say now are corrected once, here, so the
        // ids the resolver reports later (and its merges) are the ones in Room.
        stored.forEach {
            val id = assigned.getValue(MessageRef(accountId, it.folderPath, it.uid))
            if (id != it.threadId) messages.setThreadId(it.id, id)
        }
        resolver = fresh
        return fresh
    }

    private fun gmailThreadId(threadId: String): String? =
        threadId.removePrefix("gmail:$accountId:").takeIf { it != threadId }
}
