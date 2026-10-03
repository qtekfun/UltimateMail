// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.mail

import com.qtekfun.ultimatemail.domain.mail.FolderStatus
import com.qtekfun.ultimatemail.domain.mail.GmailRawQuery
import com.qtekfun.ultimatemail.domain.mail.MailAddress
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
import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.UIDFolder
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore

/**
 * [MailSession] over a connected Angus Mail store. The library blocks, so every call runs on
 * [io] through runInterruptible (cancelling interrupts it; socket reads end at the read
 * timeout at the latest). Calls take a mutex because folders and store are not thread-safe.
 */
@Suppress("TooManyFunctions") // Implements the one-method-per-operation MailSession.
class AngusMailSession(
    private val store: IMAPStore,
    private val io: CoroutineDispatcher,
    private val extensions: ProviderExtensions
) : MailSession {
    private class OpenFolder(val path: String, val writable: Boolean, val folder: IMAPFolder) {
        /** Whether this open folder can serve a request, so it need not be reopened. */
        fun serves(wantedPath: String, wantsWrite: Boolean) =
            path == wantedPath && folder.isOpen && (writable || !wantsWrite)
    }

    private val mutex = Mutex()
    private var current: OpenFolder? = null

    // The library throws many unrelated types; this is the one boundary that maps them all.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> call(block: () -> T): MailResult<T> = mutex.withLock {
        try {
            MailResult.Success(runInterruptible(io, block = block))
        } catch (e: CancellationException) {
            discardFolder()
            throw e
        } catch (e: Exception) {
            discardFolder()
            MailErrorMapper.map(e)
        }
    }

    private fun discardFolder() {
        current?.let { runCatching { it.folder.close(false) } }
        current = null
    }

    private fun open(path: String, writable: Boolean): IMAPFolder {
        current?.let {
            if (it.serves(path, writable)) return it.folder
            discardFolder()
        }
        val folder = store.getFolder(path) as IMAPFolder
        folder.open(if (writable) Folder.READ_WRITE else Folder.READ_ONLY)
        current = OpenFolder(path, writable, folder)
        return folder
    }

    override suspend fun listFolders(): MailResult<List<MailFolder>> = call {
        store.defaultFolder.list("*").map { folder ->
            val imap = folder as IMAPFolder
            val separator = imap.separator.takeIf { it != Char.MIN_VALUE }
            MailFolder(
                path = imap.fullName,
                name = imap.name,
                delimiter = separator,
                role = FolderRoles.detect(imap.attributes.toList(), imap.name, imap.fullName),
                selectable = (imap.type and Folder.HOLDS_MESSAGES) != 0
            )
        }
    }

    override suspend fun folderStatus(folder: String): MailResult<FolderStatus> = call {
        val imap = open(folder, writable = false)
        FolderStatus(
            uidValidity = imap.uidValidity,
            uidNext = imap.uidNext,
            messageCount = imap.messageCount,
            highestModSeq = if (store.hasCapability("CONDSTORE")) imap.highestModSeq else null
        )
    }

    override suspend fun fetchHeaders(
        folder: String,
        range: UidRange
    ): MailResult<List<MessageHeader>> = call {
        val imap = open(folder, writable = false)
        val last = range.last ?: UIDFolder.LASTUID
        headersOf(imap, imap.getMessagesByUID(range.first, last).filterNotNull())
            // "UID FETCH n:*" always returns the last message, even if its UID is below n.
            .filter { it.uid >= range.first }
    }

    override suspend fun fetchHeadersByUid(
        folder: String,
        uids: Set<Long>
    ): MailResult<List<MessageHeader>> = call {
        val imap = open(folder, writable = false)
        val messages = imap.getMessagesByUID(uids.sorted().toLongArray()).filterNotNull()
        headersOf(imap, messages)
    }

    private fun headersOf(imap: IMAPFolder, messages: List<Message>): List<MessageHeader> {
        val profile = FetchProfile().apply {
            add(FetchProfile.Item.ENVELOPE)
            add(FetchProfile.Item.FLAGS)
            add(FetchProfile.Item.CONTENT_INFO)
            add(FetchProfile.Item.SIZE)
            add(UIDFolder.FetchProfileItem.UID)
            add("Message-ID")
            add("In-Reply-To")
            add("References")
        }
        val gmail = extensions.isAvailable(store)
        if (gmail) extensions.fetchItems().forEach(profile::add)
        if (messages.isNotEmpty()) imap.fetch(messages.toTypedArray(), profile)
        return messages.map { toHeader(imap, it, gmail) }
    }

    override suspend fun search(
        folder: String,
        criteria: MailSearchCriteria,
        limit: Int
    ): MailResult<List<Long>> = call {
        val imap = open(folder, writable = false)
        val hits = if (extensions.isAvailable(store)) {
            extensions.rawSearch(imap, GmailRawQuery.of(criteria))
        } else {
            ImapSearchTerms.build(criteria)?.let { imap.search(it).toList() }
                ?: imap.messages.toList()
        }
        // Higher sequence numbers are newer messages: keep the newest, then ask for their UIDs.
        val newest = hits.sortedBy { it.messageNumber }.takeLast(limit)
        if (newest.isNotEmpty()) {
            val uids = FetchProfile().apply { add(UIDFolder.FetchProfileItem.UID) }
            imap.fetch(newest.toTypedArray(), uids)
        }
        newest.map { imap.getUID(it) }.sortedDescending()
    }

    private fun toHeader(folder: IMAPFolder, message: Message, gmail: Boolean): MessageHeader {
        val mime = message as MimeMessage
        return MessageHeader(
            uid = folder.getUID(message),
            messageId = mime.getHeader("Message-ID", null),
            subject = message.subject,
            from = message.from?.firstOrNull()?.toMailAddress(),
            to = message.getRecipients(Message.RecipientType.TO).toMailAddresses(),
            cc = message.getRecipients(Message.RecipientType.CC).toMailAddresses(),
            date = (message.sentDate ?: message.receivedDate)?.toInstant(),
            flags = message.flags.toMessageFlags(),
            size = message.size.toLong().coerceAtLeast(0),
            hasAttachments = MimeParts.hasAttachments(message),
            inReplyTo = mime.getHeader("In-Reply-To", null),
            references = mime.getHeader("References", null)?.split(WHITESPACE)
                ?.filter { it.isNotEmpty() }.orEmpty(),
            gmail = if (gmail) extensions.metadata(message) else null
        )
    }

    override suspend fun fetchBody(folder: String, uid: Long): MailResult<MessageBody> = call {
        val message = open(folder, writable = false).getMessageByUID(uid)
        message?.let { MimeParts.body(it) }
    }.orNotFound()

    override suspend fun fetchAttachment(
        folder: String,
        uid: Long,
        partId: String
    ): MailResult<ByteArray> = call {
        val message = open(folder, writable = false).getMessageByUID(uid)
        message?.let { m ->
            MimeParts.leaves(m).firstOrNull { it.id == partId && it.isAttachment }
                ?.let { MimeParts.readBytes(it.part) }
        }
    }.orNotFound()

    override suspend fun setFlags(
        folder: String,
        uids: Set<Long>,
        flags: Set<MailFlag>,
        enabled: Boolean
    ): MailResult<UidOperationResult> = onMessages(folder, uids) { imap, messages ->
        imap.setFlags(messages.toTypedArray(), flags.toJakarta(), enabled)
    }

    override suspend fun move(
        folder: String,
        uids: Set<Long>,
        target: String
    ): MailResult<UidOperationResult> = onMessages(folder, uids) { imap, messages ->
        imap.moveMessages(messages.toTypedArray(), store.getFolder(target))
    }

    override suspend fun copy(
        folder: String,
        uids: Set<Long>,
        target: String
    ): MailResult<UidOperationResult> = onMessages(folder, uids) { imap, messages ->
        imap.copyMessages(messages.toTypedArray(), store.getFolder(target))
    }

    override suspend fun delete(folder: String, uids: Set<Long>): MailResult<UidOperationResult> =
        onMessages(folder, uids) { imap, messages ->
            val array = messages.toTypedArray()
            imap.setFlags(array, Flags(Flags.Flag.DELETED), true)
            // Expunge only these messages (UID EXPUNGE), never other \Deleted ones.
            imap.expunge(array)
        }

    override suspend fun addLabels(
        folder: String,
        uids: Set<Long>,
        labels: Set<String>
    ): MailResult<UidOperationResult> = changeLabels(folder, uids, labels, add = true)

    override suspend fun removeLabels(
        folder: String,
        uids: Set<Long>,
        labels: Set<String>
    ): MailResult<UidOperationResult> = changeLabels(folder, uids, labels, add = false)

    private suspend fun changeLabels(
        folder: String,
        uids: Set<Long>,
        labels: Set<String>,
        add: Boolean
    ): MailResult<UidOperationResult> {
        if (!hasGmailExtensions()) return MailResult.Unsupported(GMAIL_LABELS)
        return onMessages(folder, uids) { imap, messages ->
            extensions.changeLabels(imap, messages, labels, add)
        }
    }

    private suspend fun hasGmailExtensions(): Boolean =
        call { extensions.isAvailable(store) }.let { it is MailResult.Success && it.value }

    override suspend fun appendDraft(folder: String, message: OutgoingMessage): MailResult<Long?> =
        call {
            val mime = MimeMessageBuilder.build(message)
            mime.setFlag(Flags.Flag.DRAFT, true)
            mime.setFlag(Flags.Flag.SEEN, true)
            val imap = store.getFolder(folder) as IMAPFolder
            imap.appendUIDMessages(arrayOf<Message>(mime))?.firstOrNull()?.uid
        }

    override suspend fun appendSent(folder: String, message: OutgoingMessage): MailResult<Long?> =
        call {
            val mime = MimeMessageBuilder.build(message)
            mime.setFlag(Flags.Flag.SEEN, true)
            val imap = store.getFolder(folder) as IMAPFolder
            imap.appendUIDMessages(arrayOf<Message>(mime))?.firstOrNull()?.uid
        }

    override suspend fun close() {
        withContext(NonCancellable) {
            mutex.withLock {
                runInterruptible(io) {
                    discardFolder()
                    runCatching { store.close() }
                }
            }
        }
    }

    /** Resolves [uids], runs [action] on those that exist and reports the ones that do not. */
    private suspend fun onMessages(
        folder: String,
        uids: Set<Long>,
        action: (IMAPFolder, List<Message>) -> Unit
    ): MailResult<UidOperationResult> = call {
        val imap = open(folder, writable = true)
        val sorted = uids.sorted()
        val found = imap.getMessagesByUID(sorted.toLongArray())
        val present = sorted.zip(found.toList()).filter { it.second != null }
        if (present.isNotEmpty()) action(imap, present.map { checkNotNull(it.second) })
        val applied = present.map { it.first }.toSet()
        UidOperationResult(applied = applied, missing = uids - applied)
    }

    private fun <T : Any> MailResult<T?>.orNotFound(): MailResult<T> = when (this) {
        is MailResult.Success -> value?.let { MailResult.Success(it) } ?: MailResult.NotFound
        is MailResult.Failure -> this
    }

    private fun Set<MailFlag>.toJakarta() = Flags().also { result ->
        forEach {
            when (it) {
                MailFlag.SEEN -> result.add(Flags.Flag.SEEN)
                MailFlag.ANSWERED -> result.add(Flags.Flag.ANSWERED)
                MailFlag.FLAGGED -> result.add(Flags.Flag.FLAGGED)
                MailFlag.DELETED -> result.add(Flags.Flag.DELETED)
                MailFlag.DRAFT -> result.add(Flags.Flag.DRAFT)
                MailFlag.FORWARDED -> result.add(FORWARDED_KEYWORD)
            }
        }
    }

    private fun Flags.toMessageFlags() = MessageFlags(
        seen = contains(Flags.Flag.SEEN),
        answered = contains(Flags.Flag.ANSWERED),
        flagged = contains(Flags.Flag.FLAGGED),
        deleted = contains(Flags.Flag.DELETED),
        draft = contains(Flags.Flag.DRAFT)
    )

    private fun jakarta.mail.Address.toMailAddress(): MailAddress? =
        (this as? InternetAddress)?.let { MailAddress(it.address, it.personal) }

    private fun Array<jakarta.mail.Address>?.toMailAddresses(): List<MailAddress> =
        this?.mapNotNull { it.toMailAddress() }.orEmpty()

    private companion object {
        val WHITESPACE = Regex("\\s+")
        const val GMAIL_LABELS = "gmail-labels"
        const val FORWARDED_KEYWORD = "\$Forwarded"
    }
}
