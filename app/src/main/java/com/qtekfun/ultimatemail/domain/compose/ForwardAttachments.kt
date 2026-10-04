// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.data.local.dao.AttachmentDao
import com.qtekfun.ultimatemail.data.local.model.AttachmentState
import com.qtekfun.ultimatemail.di.IoDispatcher
import com.qtekfun.ultimatemail.sync.engine.AttachmentStorage
import com.qtekfun.ultimatemail.sync.engine.DownloadAttachment
import com.qtekfun.ultimatemail.sync.engine.DownloadResult
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** How carrying a message's attachments into a draft ended. */
data class ForwardAttachmentsResult(val attached: Int, val skipped: Int) {
    val allAttached: Boolean get() = skipped == 0
}

/**
 * Puts the attachments of a received message into a draft (RF-07): a forward carries them, and a
 * server draft written elsewhere brings the ones it had. Each file is copied into the outbox
 * storage like any file the user attaches, so it shows as a chip and can be removed, and the
 * normal limits of [AttachmentLimits] apply. An attachment not yet on the device is downloaded
 * first through [DownloadAttachment], for at most [timeoutMillis] in all (the rest is skipped,
 * so an offline phone or a slow link does not keep the composer from opening). Parts the body
 * shows inline (`cid:` images) are not attached: a plain-text message cannot show them.
 */
class ForwardAttachments @Inject constructor(
    private val messageAttachments: AttachmentDao,
    private val download: DownloadAttachment,
    private val storage: AttachmentStorage,
    private val draftAttachments: DraftAttachments,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    /** Attaches what [messageRowId] carries to [draftId]; counts what did not fit or arrive. */
    suspend fun attach(
        draftId: Long,
        messageRowId: Long,
        timeoutMillis: Long = DOWNLOAD_TIMEOUT_MILLIS
    ): ForwardAttachmentsResult = withContext(io) {
        val wanted = messageAttachments.listFor(messageRowId).filter { !it.inline }
        var attached = 0
        var timedOut = false
        for (item in wanted) {
            val path = if (!timedOut) {
                try {
                    withTimeout(timeoutMillis) { readyPath(item.id, item.state, item.localPath) }
                } catch (@Suppress("SwallowedException") expired: TimeoutCancellationException) {
                    timedOut = true
                    null
                }
            } else {
                null
            }
            // The size in the message is the encoded one; the copy itself enforces the limit.
            val added = path?.let {
                val info = SourceInfo(item.fileName, item.mimeType, null)
                draftAttachments.store(draftId, info) { storage.open(it) }
            }
            if (added is AddAttachmentResult.Added) attached++
        }
        ForwardAttachmentsResult(attached, wanted.size - attached)
    }

    private suspend fun readyPath(id: Long, state: AttachmentState, local: String?): String? {
        if (state == AttachmentState.DOWNLOADED && local != null && storage.exists(local)) {
            return local
        }
        return (download(id) as? DownloadResult.Ready)?.path
    }

    companion object {
        /** All the downloads of one forward together may take this long. */
        const val DOWNLOAD_TIMEOUT_MILLIS = 30_000L
    }
}
