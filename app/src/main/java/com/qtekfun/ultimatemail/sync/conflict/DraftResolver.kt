// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.conflict

/**
 * A local draft against the server. [baseVersion] is the server version token the local copy was
 * made from (null if it was never uploaded); [dirty] says the user edited it since.
 */
data class LocalDraft(
    val accountId: Long,
    val key: String,
    val baseVersion: String?,
    val dirty: Boolean
)

/** What to do with a draft after seeing the server's version token (null if it is gone). */
sealed interface DraftDecision {
    /** Replace the server copy with the local one. */
    data object UploadLocal : DraftDecision

    /** The local copy has no edits: take the server one. */
    data object AcceptServer : DraftDecision

    /** The local copy has no edits and the draft was deleted elsewhere: drop it. */
    data object DiscardLocal : DraftDecision

    /** Both versions changed: keep the server copy, store the local one as a second draft. */
    data class KeepBoth(val notice: SyncNotice.DraftConflict) : DraftDecision
}

/** Rule 3: a draft edited on two devices is never overwritten. */
object DraftResolver {
    fun resolve(draft: LocalDraft, serverVersion: String?): DraftDecision = when {
        !draft.dirty ->
            if (serverVersion == null) DraftDecision.DiscardLocal else DraftDecision.AcceptServer

        // Unchanged on the server, or deleted there while edited here: the edit is the newest.
        serverVersion == null || serverVersion == draft.baseVersion -> DraftDecision.UploadLocal

        else -> DraftDecision.KeepBoth(SyncNotice.DraftConflict(draft.accountId, draft.key))
    }
}
