// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.data.local.dao.DraftDao
import com.qtekfun.ultimatemail.data.local.model.DraftState
import com.qtekfun.ultimatemail.di.IoDispatcher
import com.qtekfun.ultimatemail.domain.mail.MailAddress
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** What the composer lets the user change in a draft; one value of it is one autosave. */
data class DraftEdit(
    val to: List<MailAddress>,
    val cc: List<MailAddress>,
    val bcc: List<MailAddress>,
    val subject: String,
    val body: String
) {
    override fun toString(): String = "DraftEdit(REDACTED)"
}

/** How a call that changes a draft ended. */
enum class DraftChange {
    /** Stored. */
    SAVED,

    /** The draft no longer exists (sent, discarded). */
    MISSING,

    /** The draft is in the outbox: the composer no longer owns it. */
    NOT_EDITABLE
}

/**
 * Drafts in Room (RF-07): read, observe, save and remove. Only a draft in the
 * [DraftState.EDITING] state can be changed; once `SendDraft` queued it, saves are refused
 * ([DraftChange.NOT_EDITABLE]), which is what keeps a late autosave from changing a message that
 * is being sent.
 */
class DraftRepository @Inject constructor(
    private val dao: DraftDao,
    private val files: OutboxFileStorage,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    suspend fun get(id: Long): Draft? = withContext(io) { dao.get(id)?.toDraft() }

    fun observe(id: Long): Flow<Draft?> = dao.observe(id).map { it?.toDraft() }

    /** Stores the user's changes: bumps the revision and marks the draft as not yet uploaded. */
    suspend fun save(id: Long, edit: DraftEdit): DraftChange = change(id) {
        it.copy(
            to = edit.to,
            cc = edit.cc,
            bcc = edit.bcc,
            subject = edit.subject,
            body = edit.body
        )
    }

    /** Applies [transform] to an editable draft and stores the result like a user edit. */
    internal suspend fun change(id: Long, transform: (Draft) -> Draft): DraftChange =
        withContext(io) {
            val current = dao.get(id)?.toDraft() ?: return@withContext DraftChange.MISSING
            if (current.state != DraftState.EDITING) return@withContext DraftChange.NOT_EDITABLE
            val next = transform(current).copy(
                revision = current.revision + 1,
                dirty = true,
                updatedAt = clock.instant()
            )
            dao.update(next.toEntity())
            DraftChange.SAVED
        }

    /** Removes the draft, its attachment rows and its files. */
    suspend fun delete(id: Long) = withContext(io) {
        dao.delete(id)
        files.deleteDraft(id)
    }
}
