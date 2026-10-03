// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.domain.mail.FolderStatus
import com.qtekfun.ultimatemail.domain.mail.GmailMetadata
import com.qtekfun.ultimatemail.domain.mail.MailAddress
import com.qtekfun.ultimatemail.domain.mail.MailFlag
import com.qtekfun.ultimatemail.domain.mail.MailFolder
import com.qtekfun.ultimatemail.domain.mail.MailFolderRole
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MailSession
import com.qtekfun.ultimatemail.domain.mail.MessageBody
import com.qtekfun.ultimatemail.domain.mail.MessageFlags
import com.qtekfun.ultimatemail.domain.mail.MessageHeader
import com.qtekfun.ultimatemail.domain.mail.OutgoingMessage
import com.qtekfun.ultimatemail.domain.mail.UidOperationResult
import com.qtekfun.ultimatemail.domain.mail.UidRange
import java.time.Instant
import java.util.TreeMap

class FakeFolder(
    val path: String,
    val role: MailFolderRole = MailFolderRole.OTHER,
    val selectable: Boolean = true
) {
    var uidValidity = 1L
    var nextUid = 1L
    var modSeq = 1L
    val messages = TreeMap<Long, MessageHeader>()
    val bodies = mutableMapOf<Long, MessageBody>()

    /** Attachment bytes by (uid, part id). */
    val attachments = mutableMapOf<Pair<Long, String>, ByteArray>()
}

/**
 * An in-memory IMAP server model for the sync tests: folders with UIDs, UIDVALIDITY and
 * HIGHESTMODSEQ, every call logged, and failures injectable per call. GreenMail cannot change a
 * UIDVALIDITY or cut the connection on demand.
 */
class FakeMailServer {
    val folders = LinkedHashMap<String, FakeFolder>()
    var condstore = false

    /** Every call as text, in order, e.g. "fetchHeaders INBOX 1-200". */
    val log = mutableListOf<String>()

    /** Return a failure to make the call named [call] (with its log line) fail. */
    var failure: (call: String) -> MailResult.Failure? = { null }

    val appendedDrafts = mutableListOf<OutgoingMessage>()

    fun folder(
        path: String,
        role: MailFolderRole = MailFolderRole.OTHER,
        selectable: Boolean = true
    ) = folders.getOrPut(path) { FakeFolder(path, role, selectable) }

    fun deliver(
        path: String,
        subject: String = "Subject",
        sentAt: Instant = Instant.ofEpochSecond(1_700_000_000),
        messageId: String? = null,
        flags: MessageFlags = MessageFlags(),
        inReplyTo: String? = null,
        references: List<String> = emptyList(),
        gmail: GmailMetadata? = null
    ): Long {
        val folder = folder(path)
        val uid = folder.nextUid++
        folder.messages[uid] = MessageHeader(
            uid = uid,
            messageId = messageId ?: "<m$uid-${path.hashCode()}@example.test>",
            subject = subject,
            from = MailAddress("bob@example.test", "Bob"),
            to = listOf(MailAddress("ana@example.test")),
            cc = emptyList(),
            date = sentAt,
            flags = flags,
            size = 100,
            hasAttachments = false,
            inReplyTo = inReplyTo,
            references = references,
            gmail = gmail
        )
        folder.modSeq++
        return uid
    }

    fun changeFlags(path: String, uid: Long, flags: MessageFlags) {
        val folder = folder(path)
        folder.messages[uid] = folder.messages.getValue(uid).copy(flags = flags)
        folder.modSeq++
    }

    fun expunge(path: String, uid: Long) {
        val folder = folder(path)
        folder.messages.remove(uid)
        folder.modSeq++
    }

    /** The server loses its UID numbering: new UIDVALIDITY, same messages renumbered from 1. */
    fun renumber(path: String) {
        val folder = folder(path)
        val old = folder.messages.values.toList()
        folder.messages.clear()
        folder.nextUid = 1
        folder.uidValidity++
        old.forEach { header ->
            val uid = folder.nextUid++
            folder.messages[uid] = header.copy(uid = uid)
        }
        folder.modSeq++
    }

    fun uidsOf(path: String): List<Long> = folder(path).messages.keys.toList()

    fun session(): MailSession = FakeSession(this)

    fun logged(prefix: String) = log.filter { it.startsWith(prefix) }
}

class FakeSession(private val server: FakeMailServer) : MailSession {
    var closed = false

    private fun <T> call(name: String, block: () -> MailResult<T>): MailResult<T> {
        server.log += name
        return server.failure(name) ?: block()
    }

    private fun <T> inFolder(
        path: String,
        name: String,
        block: (FakeFolder) -> MailResult<T>
    ): MailResult<T> = call(name) {
        server.folders[path]?.let(block) ?: MailResult.NotFound
    }

    override suspend fun listFolders() = call("listFolders") {
        MailResult.Success(
            server.folders.values.map {
                MailFolder(it.path, it.path.substringAfterLast('/'), '/', it.role, it.selectable)
            }
        )
    }

    override suspend fun folderStatus(folder: String) = inFolder(folder, "folderStatus $folder") {
        MailResult.Success(
            FolderStatus(
                uidValidity = it.uidValidity,
                uidNext = it.nextUid,
                messageCount = it.messages.size,
                highestModSeq = if (server.condstore) it.modSeq else null
            )
        )
    }

    override suspend fun fetchHeaders(folder: String, range: UidRange) =
        inFolder(folder, "fetchHeaders $folder ${range.first}-${range.last}") {
            val last = range.last ?: Long.MAX_VALUE
            MailResult.Success(it.messages.subMap(range.first, true, last, true).values.toList())
        }

    override suspend fun fetchBody(folder: String, uid: Long) =
        inFolder(folder, "fetchBody $folder $uid") {
            it.bodies[uid]?.let { body -> MailResult.Success(body) } ?: MailResult.NotFound
        }

    override suspend fun fetchAttachment(folder: String, uid: Long, partId: String) =
        inFolder(folder, "fetchAttachment $folder $uid $partId") {
            it.attachments[uid to partId]?.let { bytes -> MailResult.Success(bytes) }
                ?: MailResult.NotFound
        }

    override suspend fun setFlags(
        folder: String,
        uids: Set<Long>,
        flags: Set<MailFlag>,
        enabled: Boolean
    ) = inFolder(folder, "setFlags $folder ${uids.sorted()} ${flags.sorted()} $enabled") { f ->
        val applied = uids.filter { it in f.messages }.toSet()
        applied.forEach { uid ->
            val header = f.messages.getValue(uid)
            val current = header.flags
            val updated = flags.fold(current) { acc, flag ->
                when (flag) {
                    MailFlag.SEEN -> acc.copy(seen = enabled)
                    MailFlag.FLAGGED -> acc.copy(flagged = enabled)
                    MailFlag.ANSWERED -> acc.copy(answered = enabled)
                    MailFlag.DELETED -> acc.copy(deleted = enabled)
                    MailFlag.DRAFT -> acc.copy(draft = enabled)
                }
            }
            f.messages[uid] = header.copy(flags = updated)
        }
        f.modSeq++
        MailResult.Success(UidOperationResult(applied, uids - applied))
    }

    override suspend fun move(folder: String, uids: Set<Long>, target: String) =
        inFolder(folder, "move $folder ${uids.sorted()} $target") { f ->
            val destination = server.folders[target] ?: return@inFolder MailResult.NotFound
            val applied = uids.filter { it in f.messages }.toSet()
            applied.forEach { uid ->
                val header = f.messages.remove(uid) ?: return@forEach
                val newUid = destination.nextUid++
                destination.messages[newUid] = header.copy(uid = newUid)
            }
            f.modSeq++
            destination.modSeq++
            MailResult.Success(UidOperationResult(applied, uids - applied))
        }

    override suspend fun copy(folder: String, uids: Set<Long>, target: String) =
        call<UidOperationResult>("copy") { MailResult.Unsupported("copy") }

    override suspend fun delete(folder: String, uids: Set<Long>) =
        inFolder(folder, "delete $folder ${uids.sorted()}") { f ->
            val applied = uids.filter { it in f.messages }.toSet()
            applied.forEach { f.messages.remove(it) }
            f.modSeq++
            MailResult.Success(UidOperationResult(applied, uids - applied))
        }

    override suspend fun addLabels(folder: String, uids: Set<Long>, labels: Set<String>) =
        inFolder(folder, "addLabels $folder ${uids.sorted()} ${labels.sorted()}") { f ->
            val applied = uids.filter { it in f.messages }.toSet()
            MailResult.Success(UidOperationResult(applied, uids - applied))
        }

    override suspend fun removeLabels(folder: String, uids: Set<Long>, labels: Set<String>) =
        inFolder(folder, "removeLabels $folder ${uids.sorted()} ${labels.sorted()}") { f ->
            val applied = uids.filter { it in f.messages }.toSet()
            MailResult.Success(UidOperationResult(applied, uids - applied))
        }

    override suspend fun appendDraft(folder: String, message: OutgoingMessage) =
        inFolder(folder, "appendDraft $folder") { f ->
            server.appendedDrafts += message
            val uid = f.nextUid++
            f.messages[uid] = MessageHeader(
                uid = uid,
                messageId = message.messageId,
                subject = message.subject,
                from = message.from,
                to = message.to,
                cc = message.cc,
                date = Instant.EPOCH,
                flags = MessageFlags(draft = true),
                size = 1,
                hasAttachments = false
            )
            MailResult.Success(uid as Long?)
        }

    override suspend fun close() {
        closed = true
        server.log += "close"
    }
}
