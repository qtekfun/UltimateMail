// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.mail

/**
 * An authenticated IMAP session on one account (RF-02, RF-05, RF-10). Calls are serialized: a
 * session does one thing at a time. Close it when done.
 */
@Suppress("TooManyFunctions") // One method per IMAP operation the app needs; splitting hides them.
interface MailSession {
    suspend fun listFolders(): MailResult<List<MailFolder>>

    suspend fun folderStatus(folder: String): MailResult<FolderStatus>

    /** Headers of the messages in [range], in UID order. Reading does not mark them as seen. */
    suspend fun fetchHeaders(folder: String, range: UidRange): MailResult<List<MessageHeader>>

    /** The text and HTML parts and the attachment list of one message. */
    suspend fun fetchBody(folder: String, uid: Long): MailResult<MessageBody>

    /** The decoded bytes of one attachment, by the [AttachmentInfo.partId] of [fetchBody]. */
    suspend fun fetchAttachment(folder: String, uid: Long, partId: String): MailResult<ByteArray>

    /** Sets (or clears, if [enabled] is false) [flags]. Missing UIDs are reported, not fatal. */
    suspend fun setFlags(
        folder: String,
        uids: Set<Long>,
        flags: Set<MailFlag>,
        enabled: Boolean
    ): MailResult<UidOperationResult>

    /** Moves messages; only those messages leave the source folder, never others. */
    suspend fun move(
        folder: String,
        uids: Set<Long>,
        target: String
    ): MailResult<UidOperationResult>

    suspend fun copy(
        folder: String,
        uids: Set<Long>,
        target: String
    ): MailResult<UidOperationResult>

    /**
     * Deletes the messages for good (flag and expunge just them). Only call this for an
     * explicit user action; "delete" in the UI normally means a move to Trash.
     */
    suspend fun delete(folder: String, uids: Set<Long>): MailResult<UidOperationResult>

    /** Gmail only; [MailResult.Unsupported] elsewhere. */
    suspend fun addLabels(
        folder: String,
        uids: Set<Long>,
        labels: Set<String>
    ): MailResult<UidOperationResult>

    /** Gmail only; [MailResult.Unsupported] elsewhere. */
    suspend fun removeLabels(
        folder: String,
        uids: Set<Long>,
        labels: Set<String>
    ): MailResult<UidOperationResult>

    /** Stores a draft; returns its new UID when the server reports it (UIDPLUS), else null. */
    suspend fun appendDraft(folder: String, message: OutgoingMessage): MailResult<Long?>

    suspend fun close()
}
