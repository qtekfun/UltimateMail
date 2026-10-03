// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.dao.AttachmentDao
import com.qtekfun.ultimatemail.data.local.dao.MessageDao
import com.qtekfun.ultimatemail.data.local.entity.AttachmentEntity
import com.qtekfun.ultimatemail.data.local.model.AttachmentState
import com.qtekfun.ultimatemail.domain.conversation.ContentIds
import com.qtekfun.ultimatemail.domain.mail.MailResult
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Where downloaded attachments are kept: app-private storage, never shared by path. */
interface AttachmentStorage {
    /** Stores [bytes] and returns the path to read them from. Replaces an earlier copy. */
    fun write(accountId: Long, attachment: AttachmentEntity, bytes: ByteArray): String

    /** Deletes every downloaded attachment of an account (when the account is removed). */
    fun deleteAccount(accountId: Long)

    /** Whether the file at [path] is still there (the system can clear app storage). */
    fun exists(path: String): Boolean

    /** The finished downloads of an account, for [AttachmentFileCleaner]. */
    fun stored(accountId: Long): List<StoredAttachment>

    /** Deletes one stored file (and its folder when that is left empty). */
    fun delete(path: String)
}

/** A downloaded attachment file and the id of the attachment row it was made for. */
data class StoredAttachment(val attachmentId: Long, val path: String)

/** The outcome of [DownloadAttachment]. */
sealed interface DownloadResult {
    /** The attachment is on the device at [path]. */
    data class Ready(val path: String) : DownloadResult

    /** The attachment or its message is not on the server any more. */
    data object Gone : DownloadResult

    data object AuthenticationRequired : DownloadResult

    /** The download did not work, e.g. there is no connection ([problem]). */
    data class Failed(val problem: SyncProblem) : DownloadResult
}

/**
 * Downloads the content of an attachment, only when the reader asks for it (RF-04, RF-10), into
 * [AttachmentStorage] and records the state in Room: DOWNLOADING while it runs, then DOWNLOADED
 * with the path, or FAILED. An attachment already on the device is not fetched again.
 *
 * Parts that belong to the body of the message (images the HTML shows with `cid:`) are fetched by
 * [fetchInline] when the message is opened, since the message is not complete without them.
 */
class DownloadAttachment @Inject constructor(
    private val attachments: AttachmentDao,
    private val messages: MessageDao,
    private val sessions: AccountSessions,
    private val storage: AttachmentStorage
) {
    suspend operator fun invoke(attachmentId: Long): DownloadResult {
        val attachment = attachments.get(attachmentId)
        val message = attachment?.let { messages.getById(it.messageId) }
        val cached = attachment?.localPath?.takeIf {
            attachment.state == AttachmentState.DOWNLOADED && storage.exists(it)
        }
        return when {
            attachment == null || message == null || message.uid <= 0 -> DownloadResult.Gone
            cached != null -> DownloadResult.Ready(cached)
            else -> download(attachment, message.accountId, message.folderPath, message.uid)
        }
    }

    private suspend fun download(
        attachment: AttachmentEntity,
        accountId: Long,
        folderPath: String,
        uid: Long
    ): DownloadResult {
        attachments.setState(attachment.id, AttachmentState.DOWNLOADING, null)
        val result = try {
            fetch(attachment, accountId, folderPath, uid)
        } catch (cancelled: CancellationException) {
            // The reader left: it can be started again, nothing stays "downloading".
            withContext(NonCancellable) {
                attachments.setState(attachment.id, AttachmentState.REMOTE, null)
            }
            throw cancelled
        }
        when (result) {
            is DownloadResult.Ready ->
                attachments.setState(attachment.id, AttachmentState.DOWNLOADED, result.path)

            DownloadResult.Gone, DownloadResult.AuthenticationRequired, is DownloadResult.Failed ->
                attachments.setState(attachment.id, AttachmentState.FAILED, null)
        }
        return result
    }

    private suspend fun fetch(
        attachment: AttachmentEntity,
        accountId: Long,
        folderPath: String,
        uid: Long
    ): DownloadResult {
        val leased = sessions.withSession(accountId) {
            it.fetchAttachment(folderPath, uid, attachment.partId)
        }
        return when (leased) {
            is Leased.Ok -> when (val fetched = leased.value) {
                is MailResult.Success -> save(accountId, attachment, fetched.value)
                MailResult.NotFound -> DownloadResult.Gone
                MailResult.AuthenticationFailed -> DownloadResult.AuthenticationRequired
                is MailResult.Failure -> DownloadResult.Failed(fetched.toProblem())
            }

            Leased.AuthRequired -> DownloadResult.AuthenticationRequired

            is Leased.Failed -> DownloadResult.Failed(leased.failure.toProblem())

            Leased.NoAccount -> DownloadResult.Gone
        }
    }

    private fun save(accountId: Long, attachment: AttachmentEntity, bytes: ByteArray) = try {
        DownloadResult.Ready(storage.write(accountId, attachment, bytes))
    } catch (@Suppress("TooGenericExceptionCaught") ignored: Exception) {
        // Disk full or storage gone; the exception text can hold a file name.
        DownloadResult.Failed(SyncProblem.UNKNOWN)
    }

    /**
     * Downloads the small images [messageId]'s HTML shows inline (`cid:`) that are not on the
     * device yet. Returns how many were fetched. Other attachments are never touched.
     */
    suspend fun fetchInline(messageId: Long): Int {
        val html = messages.getById(messageId)?.bodyHtml ?: return 0
        val shown = ContentIds.referencedIn(html)
        val wanted = attachments.listFor(messageId).filter {
            it.inline && it.contentId in shown && it.state == AttachmentState.REMOTE &&
                it.mimeType.startsWith("image/", ignoreCase = true) && it.size <= MAX_INLINE_BYTES
        }
        return wanted.count { invoke(it.id) is DownloadResult.Ready }
    }

    companion object {
        /** Inline images larger than this are not fetched on their own. */
        const val MAX_INLINE_BYTES = 2L * 1024 * 1024
    }
}
