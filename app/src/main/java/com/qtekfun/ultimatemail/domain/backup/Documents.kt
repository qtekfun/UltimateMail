// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.backup

/** Where a backup is written: a document the user picked with the system file creator. */
interface DocumentSink {
    /** Writes [bytes] to [uri], replacing its content; false when that is not possible. */
    suspend fun write(uri: String, bytes: ByteArray): Boolean
}

/** What reading a document gave. */
sealed interface DocumentRead {
    class Bytes(val bytes: ByteArray) : DocumentRead

    /** The document is larger than the limit asked for. */
    data object TooLarge : DocumentRead

    data object Unreadable : DocumentRead
}

/** Where a backup is read from: a document the user picked with the system file picker. */
interface DocumentSource {
    /** Reads [uri], giving up with [DocumentRead.TooLarge] past [maxBytes]. */
    suspend fun read(uri: String, maxBytes: Int): DocumentRead
}

/**
 * Folder choices of imported accounts that wait for the first folder sync: the folders of a new
 * account are not known until the server lists them (RF-12).
 */
interface PendingFolderChoices {
    /** Remembers [choices] (path to synced) for [accountId], replacing earlier ones. */
    fun save(accountId: Long, choices: Map<String, Boolean>)

    /** What waits for [accountId]; empty when nothing does. */
    fun peek(accountId: Long): Map<String, Boolean>

    fun clear(accountId: Long)
}

/** Used where nothing is imported: no choices wait and none are kept. */
object NoPendingFolderChoices : PendingFolderChoices {
    override fun save(accountId: Long, choices: Map<String, Boolean>) = Unit

    override fun peek(accountId: Long): Map<String, Boolean> = emptyMap()

    override fun clear(accountId: Long) = Unit
}
