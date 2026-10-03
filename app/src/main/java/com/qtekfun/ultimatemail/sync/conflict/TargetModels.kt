// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.conflict

/** Operations that name one message and can outlive its UID. */
enum class TargetKind { MOVE, ADD_LABEL, REMOVE_LABEL, DELETE }

/**
 * A queued operation. [argument] is the destination folder for MOVE and the label for the label
 * kinds; it is ignored for DELETE. [identity] comes from the local row the user acted on.
 */
data class TargetedOperation(
    val id: Long,
    val accountId: Long,
    val kind: TargetKind,
    val folderPath: String,
    val uid: Long,
    val identity: MessageIdentity,
    val argument: String = ""
)

/** A message the server reported in a folder (plain data, no mail-client classes). */
data class ServerMessage(
    val folderPath: String,
    val uid: Long,
    val identity: MessageIdentity,
    val labels: Set<String> = emptySet()
)

/** What to do with a [TargetedOperation] after looking at the server. */
sealed interface TargetResolution {
    /** Send the operation, addressing the message by [uid] in the operation's folder. */
    data class Apply(val uid: Long) : TargetResolution

    /** The server already reflects the operation: complete it without sending anything. */
    data object AlreadyApplied : TargetResolution

    /** The message is gone: drop the operation and tell the user. */
    data class Discard(val notice: SyncNotice.MessageVanished) : TargetResolution
}
