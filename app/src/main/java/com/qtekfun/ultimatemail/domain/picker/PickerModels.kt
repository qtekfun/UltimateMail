// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.picker

import com.qtekfun.ultimatemail.data.local.model.FolderRole

/** A message as the user sees it: the folder it is listed in and its UID there. */
data class MessageRef(val folderPath: String, val uid: Long)

/**
 * What the move / label picker is asked to do: the messages of one account. For a conversation,
 * pass every message of it. Open the picker with it through `MoveLabelDialog`.
 */
data class PickerRequest(val accountId: Long, val messages: List<MessageRef>) {
    init {
        require(messages.isNotEmpty()) { "Nothing to move" }
    }
}

/** Gmail accounts label messages (several at once); other providers keep a message in a folder. */
enum class PickerMode { LABELS, FOLDERS }

/** Gmail's system label names, as the X-GM-LABELS extension spells them. */
object GmailLabels {
    /** The label that makes a message show in the Inbox; removing it archives the message. */
    const val INBOX = "\\Inbox"

    /** Folder prefixes that hold Gmail's own pseudo-labels, which are not labels the user owns. */
    val systemPrefixes = listOf("[Gmail]/", "[Google Mail]/")
}

/**
 * One place a message can go. [value] is what the queued operation carries: the folder path for a
 * move, the label for ADD_LABEL / REMOVE_LABEL (Gmail's Inbox is `\Inbox`, not its path).
 * A disabled destination is shown but cannot be chosen: every selected message is in it already.
 */
data class PickerFolder(
    val path: String,
    val name: String,
    val role: FolderRole,
    val isLabel: Boolean,
    val depth: Int,
    val value: String,
    val enabled: Boolean = true,
    /**
     * Chosen by moving the messages there instead of toggling a label: Gmail's Trash and Spam
     * in label mode (they are folders, not labels a client can set).
     */
    val moveTarget: Boolean = false
)

/** One row of the picker list when there is no search. */
sealed interface PickerRow {
    /** A parent that cannot be chosen but explains the nesting of the rows below it. */
    data class Group(val path: String, val name: String, val depth: Int) : PickerRow

    data class Destination(val folder: PickerFolder) : PickerRow
}
