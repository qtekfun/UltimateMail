// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.conflict

/**
 * Something the user must be told about after a conflict was settled. Carries no text: the UI
 * maps each case to a string and uses the ids to find the account and folder.
 */
sealed interface SyncNotice {
    /** The message of operation [operationId] is gone from the server, so it was discarded. */
    data class MessageVanished(val accountId: Long, val folderPath: String, val operationId: Long) :
        SyncNotice

    /** The draft [draftKey] was edited elsewhere too: both versions were kept. */
    data class DraftConflict(val accountId: Long, val draftKey: String) : SyncNotice

    /** The folder was invalidated (UIDVALIDITY changed) and is being downloaded again. */
    data class FolderReset(val accountId: Long, val folderPath: String) : SyncNotice
}
