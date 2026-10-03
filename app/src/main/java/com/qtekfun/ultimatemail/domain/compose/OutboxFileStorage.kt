// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import java.io.InputStream

/** The outcome of copying a file into the outbox storage. */
sealed interface StoreResult {
    data class Stored(val path: String, val size: Long) : StoreResult

    /** The content is longer than the allowance given to [OutboxFileStorage.write]. */
    data object TooLarge : StoreResult

    /** The copy failed (disk full, source unreadable). Nothing was kept. */
    data object Failed : StoreResult
}

/**
 * Where the files attached to drafts live until the message is sent or the draft is discarded:
 * app-private storage excluded from backups (the real one is `data.local.FileOutboxStorage`).
 * Files are grouped by draft, so removing a draft removes its files whichever account it now
 * belongs to.
 */
interface OutboxFileStorage {
    /**
     * Copies [source] into a new file of [draftId] named after [displayName] (made safe for the
     * file system), refusing and keeping nothing if it has more than [maxBytes] bytes. The
     * caller closes [source].
     */
    fun write(draftId: Long, displayName: String, source: InputStream, maxBytes: Long): StoreResult

    /** The whole file, for building the message to send; null when it is gone. */
    fun read(path: String): ByteArray?

    fun exists(path: String): Boolean

    fun delete(path: String)

    /** Removes every file of the draft. */
    fun deleteDraft(draftId: Long)
}
