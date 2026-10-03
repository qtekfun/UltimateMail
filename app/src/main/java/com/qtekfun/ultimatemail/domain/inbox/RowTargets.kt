// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

import com.qtekfun.ultimatemail.data.local.entity.FolderEntity
import com.qtekfun.ultimatemail.domain.conversation.FolderTargets

/**
 * The folders of each account shown in a list, to tell where "archive" and "delete" send a
 * conversation of that list and whether they apply to it (see [FolderTargets]).
 */
data class RowTargets(val folders: Map<Long, List<FolderEntity>> = emptyMap()) {
    /** The targets for [item]; null while its account's folders are not known. */
    fun of(item: ConversationItem): FolderTargets? =
        folders[item.accountId]?.let { FolderTargets.resolve(it, item.folderPath) }
}
