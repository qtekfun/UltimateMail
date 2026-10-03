// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.picker

import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.entity.AccountEntity
import com.qtekfun.ultimatemail.domain.folder.FolderListing
import com.qtekfun.ultimatemail.domain.folder.FolderTree
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** What the picker reads from Room: the account, its folders and the labels of the messages. */
class PickerSource @Inject constructor(
    database: UltimateMailDatabase,
    private val folderListing: FolderListing
) {
    private val accounts = database.accountDao()
    private val messages = database.messageDao()

    suspend fun account(accountId: Long): AccountEntity? = accounts.get(accountId)

    /** The folders of the account, kept up to date while the picker is open. */
    fun observeTree(accountId: Long): Flow<FolderTree> = folderListing.observe(accountId)

    /**
     * The labels each message of [request] has, in the same order, including the label of the
     * folder it is listed in ([PickerCandidates.implicitLabel]). Messages that are not stored
     * (any more) count as having none.
     */
    suspend fun labelsOf(request: PickerRequest, tree: FolderTree): List<Set<String>> =
        request.messages.map { ref ->
            val stored = messages.get(request.accountId, ref.folderPath, ref.uid)?.labels.orEmpty()
            val implicit = PickerCandidates.implicitLabel(tree, ref.folderPath)
            if (implicit == null) stored.toSet() else stored.toSet() + implicit
        }

    /** The picker mode of [account] given its [tree]. */
    fun modeOf(account: AccountEntity, tree: FolderTree): PickerMode = PickerModeDetector.detect(
        account.authType,
        account.imapHost,
        hasLabelFolders = (tree.special + tree.nodes).any { it.isLabel }
    )
}
