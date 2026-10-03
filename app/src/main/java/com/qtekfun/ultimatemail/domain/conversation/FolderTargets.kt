// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.conversation

import com.qtekfun.ultimatemail.data.local.entity.FolderEntity
import com.qtekfun.ultimatemail.data.local.model.FolderRole

/**
 * Where "archive" and "delete" send a conversation, and whether they apply to the folder it is
 * in. [archivePath] and [trashPath] are the server paths of the account's special folders, null
 * when the account has none, in which case the action cannot be offered (never a permanent
 * delete as a stand-in).
 */
data class FolderTargets(
    val archivePath: String?,
    val trashPath: String?,
    val canArchive: Boolean,
    val canDelete: Boolean
) {
    companion object {
        /**
         * Archive goes to the folder with the Archive role, else to All mail (Gmail); delete goes
         * to Trash. Archiving from the archive itself, All mail or Trash is pointless, and so is
         * deleting from Trash.
         */
        fun resolve(folders: List<FolderEntity>, currentPath: String): FolderTargets {
            val archive = folders.firstOrNull { it.role == FolderRole.ARCHIVE }
                ?: folders.firstOrNull { it.role == FolderRole.ALL_MAIL }
            val trash = folders.firstOrNull { it.role == FolderRole.TRASH }
            val current = folders.firstOrNull { it.path == currentPath }?.role
            val archiveHere = current == FolderRole.ARCHIVE || current == FolderRole.ALL_MAIL ||
                current == FolderRole.TRASH
            return FolderTargets(
                archivePath = archive?.path,
                trashPath = trash?.path,
                canArchive = archive != null && !archiveHere,
                canDelete = trash != null && current != FolderRole.TRASH
            )
        }
    }
}
