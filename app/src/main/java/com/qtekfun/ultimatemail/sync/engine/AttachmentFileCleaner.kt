// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.dao.AttachmentDao
import javax.inject.Inject

/**
 * Deletes downloaded attachment files whose attachment row no longer exists: pruning, expunges
 * and moves delete messages (and, by cascade, their rows) but not the files (RF-10). Files of
 * rows that still exist are never touched.
 */
class AttachmentFileCleaner @Inject constructor(
    private val attachments: AttachmentDao,
    private val storage: AttachmentStorage
) {
    /** Removes the orphan files of [accountId]; returns how many were deleted. */
    suspend fun clean(accountId: Long): Int {
        // Files first, rows second: a download that starts meanwhile writes a file we did not
        // list, so a file is never judged against rows read before it existed.
        val files = storage.stored(accountId)
        if (files.isEmpty()) return 0
        val known = attachments.idsOfAccount(accountId).toSet()
        val orphans = files.filter { it.attachmentId !in known }
        orphans.forEach { storage.delete(it.path) }
        return orphans.size
    }
}
