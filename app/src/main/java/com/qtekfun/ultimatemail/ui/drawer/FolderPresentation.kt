// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.drawer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.qtekfun.ultimatemail.R
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.ui.components.MailIcons

/** Localized name of a special folder; other folders show the name the server gave them. */
@Composable
internal fun FolderRole.displayName(): String? {
    val res = when (this) {
        FolderRole.INBOX -> R.string.folder_inbox
        FolderRole.DRAFTS -> R.string.folder_drafts
        FolderRole.SENT -> R.string.folder_sent
        FolderRole.ARCHIVE -> R.string.folder_archive
        FolderRole.TRASH -> R.string.folder_trash
        FolderRole.JUNK -> R.string.folder_junk
        FolderRole.ALL_MAIL -> R.string.folder_all_mail
        FolderRole.STARRED -> R.string.folder_starred
        FolderRole.OTHER -> return null
    }
    return stringResource(res)
}

/** The icon of a folder in the side menu: by role, else a label or a plain folder. */
internal fun FolderRole.icon(isLabel: Boolean): ImageVector = when (this) {
    FolderRole.INBOX -> MailIcons.Inbox
    FolderRole.DRAFTS -> Icons.Filled.Edit
    FolderRole.SENT -> Icons.AutoMirrored.Filled.Send
    FolderRole.ARCHIVE -> MailIcons.Archive
    FolderRole.ALL_MAIL -> Icons.Filled.MailOutline
    FolderRole.STARRED -> Icons.Filled.Star
    FolderRole.TRASH -> Icons.Filled.Delete
    FolderRole.JUNK -> Icons.Filled.Warning
    FolderRole.OTHER -> if (isLabel) MailIcons.Label else MailIcons.Folder
}
