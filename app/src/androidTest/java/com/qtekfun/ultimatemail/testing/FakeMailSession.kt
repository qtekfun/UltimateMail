// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.testing

import com.qtekfun.ultimatemail.domain.mail.FolderStatus
import com.qtekfun.ultimatemail.domain.mail.MailFlag
import com.qtekfun.ultimatemail.domain.mail.MailFolder
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MailSearchCriteria
import com.qtekfun.ultimatemail.domain.mail.MailSession
import com.qtekfun.ultimatemail.domain.mail.MessageBody
import com.qtekfun.ultimatemail.domain.mail.MessageFlags
import com.qtekfun.ultimatemail.domain.mail.MessageHeader
import com.qtekfun.ultimatemail.domain.mail.OutgoingMessage
import com.qtekfun.ultimatemail.domain.mail.UidOperationResult
import com.qtekfun.ultimatemail.domain.mail.UidRange
import java.time.Instant

/**
 * An IMAP session on a [FakeMailbox]. It does what the real one does to the data (move really
 * moves, flags really change, new UIDs in the target) and nothing else: no extensions, so Gmail
 * labels answer [MailResult.Unsupported] like on any other server.
 */
@Suppress("TooManyFunctions") // One method per IMAP operation, as in MailSession.
class FakeMailSession(private val mailbox: FakeMailbox) : MailSession {
    private fun <T> inFolder(path: String, block: (FakeFolder) -> MailResult<T>): MailResult<T> =
        synchronized(mailbox.lock) {
            mailbox.folders[path]?.let(block) ?: MailResult.NotFound
        }

    override suspend fun listFolders(): MailResult<List<MailFolder>> = synchronized(mailbox.lock) {
        MailResult.Success(
            mailbox.folders.values.map {
                MailFolder(it.path, it.path.substringAfterLast('/'), '/', it.role, true)
            }
        )
    }

    override suspend fun folderStatus(folder: String) = inFolder(folder) {
        MailResult.Success(FolderStatus(it.uidValidity, it.nextUid, it.messages.size, null))
    }

    override suspend fun fetchHeaders(folder: String, range: UidRange) = inFolder(folder) {
        val last = range.last ?: Long.MAX_VALUE
        MailResult.Success(it.messages.subMap(range.first, true, last, true).values.toList())
    }

    /** A search over subjects only: every text term has to be in the subject, any case. */
    override suspend fun search(
        folder: String,
        criteria: MailSearchCriteria,
        limit: Int
    ): MailResult<List<Long>> = inFolder(folder) { found ->
        val hits = found.messages.values.filter { header ->
            val subject = header.subject.orEmpty()
            (criteria.text + criteria.subject).all { subject.contains(it, ignoreCase = true) }
        }
        MailResult.Success(hits.map { it.uid }.sortedDescending().take(limit))
    }

    override suspend fun fetchHeadersByUid(folder: String, uids: Set<Long>) =
        inFolder(folder) { found ->
            MailResult.Success(uids.sorted().mapNotNull { found.messages[it] })
        }

    override suspend fun fetchBody(folder: String, uid: Long): MailResult<MessageBody> =
        inFolder(folder) { found ->
            found.bodies[uid]?.let { MailResult.Success(it) } ?: MailResult.NotFound
        }

    override suspend fun fetchAttachment(folder: String, uid: Long, partId: String) =
        MailResult.NotFound

    override suspend fun setFlags(
        folder: String,
        uids: Set<Long>,
        flags: Set<MailFlag>,
        enabled: Boolean
    ) = inFolder(folder) { found ->
        val applied = uids.filter { it in found.messages }.toSet()
        applied.forEach { uid ->
            val header = found.messages.getValue(uid)
            found.messages[uid] = header.copy(flags = header.flags.with(flags, enabled))
        }
        MailResult.Success(UidOperationResult(applied, uids - applied))
    }

    override suspend fun move(folder: String, uids: Set<Long>, target: String) =
        inFolder(folder) { source ->
            val destination = mailbox.folders[target] ?: return@inFolder MailResult.NotFound
            val applied = uids.filter { it in source.messages }.toSet()
            applied.forEach { uid ->
                val header = source.messages.remove(uid) ?: return@forEach
                val body = source.bodies.remove(uid)
                val newUid = destination.nextUid++
                destination.messages[newUid] = header.copy(uid = newUid)
                body?.let { destination.bodies[newUid] = it }
            }
            MailResult.Success(UidOperationResult(applied, uids - applied))
        }

    override suspend fun copy(folder: String, uids: Set<Long>, target: String) =
        MailResult.Unsupported("copy")

    override suspend fun delete(folder: String, uids: Set<Long>) = inFolder(folder) { found ->
        val applied = uids.filter { it in found.messages }.toSet()
        applied.forEach {
            found.messages.remove(it)
            found.bodies.remove(it)
        }
        MailResult.Success(UidOperationResult(applied, uids - applied))
    }

    override suspend fun addLabels(folder: String, uids: Set<Long>, labels: Set<String>) =
        MailResult.Unsupported("labels")

    override suspend fun removeLabels(folder: String, uids: Set<Long>, labels: Set<String>) =
        MailResult.Unsupported("labels")

    override suspend fun appendDraft(folder: String, message: OutgoingMessage) =
        append(folder, message, MessageFlags(draft = true))

    override suspend fun appendSent(folder: String, message: OutgoingMessage) =
        append(folder, message, MessageFlags(seen = true))

    private fun append(
        folder: String,
        message: OutgoingMessage,
        flags: MessageFlags
    ): MailResult<Long?> = inFolder(folder) { found ->
        val uid = found.nextUid++
        found.messages[uid] = MessageHeader(
            uid = uid,
            messageId = message.messageId,
            subject = message.subject,
            from = message.from,
            to = message.to,
            cc = message.cc,
            date = Instant.EPOCH,
            flags = flags,
            size = 1,
            hasAttachments = message.attachments.isNotEmpty()
        )
        MailResult.Success(uid as Long?)
    }

    override suspend fun close() = Unit
}

private fun MessageFlags.with(changed: Set<MailFlag>, enabled: Boolean): MessageFlags =
    changed.fold(this) { flags, flag ->
        when (flag) {
            MailFlag.SEEN -> flags.copy(seen = enabled)
            MailFlag.FLAGGED -> flags.copy(flagged = enabled)
            MailFlag.ANSWERED -> flags.copy(answered = enabled)
            MailFlag.DELETED -> flags.copy(deleted = enabled)
            MailFlag.DRAFT -> flags.copy(draft = enabled)
            MailFlag.FORWARDED -> flags
        }
    }
