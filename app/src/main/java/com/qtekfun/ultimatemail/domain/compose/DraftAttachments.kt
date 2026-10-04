// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.data.local.dao.DraftDao
import com.qtekfun.ultimatemail.data.local.entity.OutgoingAttachmentEntity
import com.qtekfun.ultimatemail.data.local.model.DraftState
import com.qtekfun.ultimatemail.di.IoDispatcher
import com.qtekfun.ultimatemail.domain.conversation.AttachmentFileNames
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * How much a message may carry. The server's real limit is unknown (it is not part of IMAP or
 * SMTP; the SMTP SIZE extension is not read yet), so these are conservative guesses:
 * above [WARN_BYTES] in total the composer should warn that many servers will refuse the message;
 * above [MAX_BYTES] attachments are refused, because Gmail, the strictest common provider, stops
 * at 25 MB. Attachments grow by about a third when encoded for mail, so a total near
 * [MAX_BYTES] can still be refused by the server; the sender then sees a permanent failure.
 * Sizes are mebibytes (1024 * 1024 bytes).
 */
object AttachmentLimits {
    const val WARN_BYTES: Long = 20L * 1024 * 1024
    const val MAX_BYTES: Long = 25L * 1024 * 1024

    fun isOverWarning(totalBytes: Long) = totalBytes > WARN_BYTES
}

/** How adding an attachment ended. */
sealed interface AddAttachmentResult {
    /** [overWarning]: the draft's attachments now add up to more than [AttachmentLimits.WARN_BYTES]. */
    data class Added(
        val attachment: DraftAttachment,
        val totalBytes: Long,
        val overWarning: Boolean
    ) : AddAttachmentResult

    /** It would take the draft above [AttachmentLimits.MAX_BYTES]; nothing was added. */
    data class TooLarge(val limitBytes: Long) : AddAttachmentResult

    /** The file could not be read (access revoked, deleted) or copied. */
    data object Unreadable : AddAttachmentResult

    data object DraftMissing : AddAttachmentResult

    /** The draft is in the outbox. */
    data object NotEditable : AddAttachmentResult
}

/**
 * The files attached to a draft (RF-07): added from a `content:` URI by copying them into the
 * outbox storage (the URI grant of a picker does not last), removed, listed and observed.
 */
class DraftAttachments @Inject constructor(
    private val dao: DraftDao,
    private val source: AttachmentSource,
    private val files: OutboxFileStorage,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    /** Copies the file at [uri] into the draft, within [AttachmentLimits]. */
    suspend fun add(draftId: Long, uri: String): AddAttachmentResult = withContext(io) {
        val draft = dao.get(draftId) ?: return@withContext AddAttachmentResult.DraftMissing
        if (draft.state != DraftState.EDITING) return@withContext AddAttachmentResult.NotEditable
        val info = source.describe(uri) ?: return@withContext AddAttachmentResult.Unreadable
        store(draftId, info) { source.open(uri) }
    }

    /**
     * Copies a stream into the draft: what [add] does once it knows what the file is. [open] is
     * called once, only after the size check, and its stream is closed here.
     */
    internal suspend fun store(
        draftId: Long,
        info: SourceInfo,
        open: () -> java.io.InputStream?
    ): AddAttachmentResult = withContext(io) {
        val draft = dao.get(draftId) ?: return@withContext AddAttachmentResult.DraftMissing
        if (draft.state != DraftState.EDITING) return@withContext AddAttachmentResult.NotEditable
        val used = dao.attachmentBytes(draftId)
        val room = AttachmentLimits.MAX_BYTES - used
        if (info.size != null && info.size > room) {
            return@withContext AddAttachmentResult.TooLarge(AttachmentLimits.MAX_BYTES)
        }
        val name = AttachmentFileNames.safe(info.name, DEFAULT_NAME)
        val stream = open() ?: return@withContext AddAttachmentResult.Unreadable
        when (val stored = stream.use { files.write(draftId, name, it, room) }) {
            StoreResult.TooLarge -> AddAttachmentResult.TooLarge(AttachmentLimits.MAX_BYTES)

            StoreResult.Failed -> AddAttachmentResult.Unreadable

            is StoreResult.Stored -> {
                val row = OutgoingAttachmentEntity(
                    draftId = draftId,
                    displayName = name,
                    mimeType = info.mimeType?.takeIf { it.isNotBlank() } ?: DEFAULT_MIME,
                    size = stored.size,
                    filePath = stored.path
                )
                val id = dao.insertAttachment(row)
                val total = used + stored.size
                AddAttachmentResult.Added(
                    row.copy(id = id).toAttachment(),
                    total,
                    AttachmentLimits.isOverWarning(total)
                )
            }
        }
    }

    /** Removes an attachment and its file; unknown ids are ignored. */
    suspend fun remove(attachmentId: Long) = withContext(io) {
        val row = dao.attachment(attachmentId) ?: return@withContext
        dao.deleteAttachment(attachmentId)
        files.delete(row.filePath)
    }

    suspend fun list(draftId: Long): List<DraftAttachment> =
        withContext(io) { dao.attachments(draftId).map { it.toAttachment() } }

    fun observe(draftId: Long): Flow<List<DraftAttachment>> =
        dao.observeAttachments(draftId).map { rows -> rows.map { it.toAttachment() } }

    /** The size of all the draft's attachments together. */
    suspend fun totalBytes(draftId: Long): Long = withContext(io) { dao.attachmentBytes(draftId) }

    private companion object {
        const val DEFAULT_NAME = "attachment"
        const val DEFAULT_MIME = "application/octet-stream"
    }
}
