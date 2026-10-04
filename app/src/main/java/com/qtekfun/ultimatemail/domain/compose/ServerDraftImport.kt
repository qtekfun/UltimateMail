// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.model.DraftKind
import com.qtekfun.ultimatemail.data.local.model.DraftState
import com.qtekfun.ultimatemail.di.IoDispatcher
import com.qtekfun.ultimatemail.sync.engine.BodyResult
import com.qtekfun.ultimatemail.sync.engine.LoadMessageBody
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** How [ServerDraftImport.open] ended. */
sealed interface ServerDraftOpen {
    /** The composer can show [draft]; [attachmentsSkipped] files of the copy did not come. */
    data class Opened(val draft: Draft, val attachmentsSkipped: Int) : ServerDraftOpen

    /** The copy is gone, or its text could not be loaded (offline): nothing was changed. */
    data object Unavailable : ServerDraftOpen
}

/**
 * Opens a draft that exists only in the server's Drafts folder (written on another device or by
 * another app) in the composer (RF-07). The server copy becomes a local draft that remembers it
 * ([Draft.serverMessageId]), so that the next save replaces it (the same SAVE_DRAFT as for any
 * draft, which removes the copy it knows) and sending removes it, leaving no duplicate. A copy
 * made by this app keeps its draft key, so every device still sees one draft; one made by
 * another app gets a new key, and the executor deletes the old copy by its Message-ID.
 *
 * The text is loaded before anything is stored (a body that is not on the device is fetched), so
 * a draft is never opened empty and then saved over the real text. Plain text only: the plain
 * part, or a plain reading of the HTML part. Bcc is not in Room and does not come along, and the
 * message is opened as a new message that keeps In-Reply-To and References (the original is
 * not flagged as answered when it is sent). Opening the same copy twice gives the same draft.
 */
class ServerDraftImport @Inject constructor(
    database: UltimateMailDatabase,
    private val loadBody: LoadMessageBody,
    private val attachments: ForwardAttachments,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    private val drafts = database.draftDao()
    private val accounts = database.accountDao()
    private val messages = database.messageDao()

    suspend fun open(messageRowId: Long): ServerDraftOpen = withContext(io) {
        val gone = ServerDraftOpen.Unavailable
        val row = messages.getById(messageRowId) ?: return@withContext gone
        val copyId = row.messageId?.takeIf { it.isNotBlank() } ?: return@withContext gone
        val account = accounts.get(row.accountId) ?: return@withContext gone
        if (loadBody(row.id) !is BodyResult.Loaded) return@withContext gone
        val key = DraftMessageIds.keyOf(copyId)
        val known = drafts.getByKey(key.orEmpty())
            ?: drafts.getByServerMessageId(account.id, copyId)
        if (known != null) {
            // Already opened, or this device's own draft; one being sent cannot be edited.
            return@withContext known.takeIf { it.state == DraftState.EDITING }
                ?.let { ServerDraftOpen.Opened(it.toDraft(), 0) } ?: ServerDraftOpen.Unavailable
        }
        // The body was just stored by loadBody: read the row again to get it.
        val source = (messages.getById(row.id) ?: row).toComposeSource()
        val now = clock.instant()
        val draft = Draft(
            id = 0,
            key = key ?: DraftMessageIds.newKey(),
            accountId = account.id,
            kind = DraftKind.NEW,
            state = DraftState.EDITING,
            to = source.to,
            cc = source.cc,
            bcc = emptyList(),
            subject = row.subject,
            body = source.bodyText.orEmpty(),
            inReplyTo = row.inReplyTo,
            references = row.referenceIds,
            source = null,
            // The signature, if any, is already in the text the user wrote.
            signatureText = null,
            signatureBeforeQuote = account.signatureBeforeQuote,
            serverMessageId = copyId,
            dirty = false,
            // Not 0: the composer discards a revision-0 draft that was never touched, and that
            // would delete the server copy of a draft the user only looked at.
            revision = 1,
            outgoingMessageId = null,
            smtpAcceptedAt = null,
            createdAt = now,
            updatedAt = now
        )
        val stored = draft.copy(id = drafts.insert(draft.toEntity()))
        val carried = attachments.attach(stored.id, row.id)
        ServerDraftOpen.Opened(stored, carried.skipped)
    }
}
