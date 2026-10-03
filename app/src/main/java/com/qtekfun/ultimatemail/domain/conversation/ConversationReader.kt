// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.conversation

import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.entity.AttachmentEntity
import com.qtekfun.ultimatemail.data.local.entity.FolderEntity
import com.qtekfun.ultimatemail.data.local.entity.MessageEntity
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Which conversation is open: the thread of a folder of an account. */
data class ConversationRef(val accountId: Long, val folderPath: String, val threadId: String)

/** What Room holds about an open conversation. [messages] run oldest to newest. */
data class ConversationData(
    val accountEmail: String?,
    val messages: List<MessageEntity>,
    /** Attachments by message id. */
    val attachments: Map<Long, List<AttachmentEntity>>,
    val folders: List<FolderEntity>
) {
    companion object {
        val EMPTY = ConversationData(null, emptyList(), emptyMap(), emptyList())
    }
}

/** The messages of one conversation with their attachments, straight from Room (RF-03). */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationReader @Inject constructor(database: UltimateMailDatabase) {
    private val messages = database.messageDao()
    private val attachments = database.attachmentDao()
    private val folders = database.folderDao()
    private val accounts = database.accountDao()

    /** Re-emits whenever the messages, their attachments or the account's folders change. */
    fun observe(ref: ConversationRef): Flow<ConversationData> =
        messages.observeThread(ref.accountId, ref.folderPath, ref.threadId).flatMapLatest { list ->
            if (list.isEmpty()) {
                flowOf(ConversationData.EMPTY)
            } else {
                combine(
                    attachments.observeForMessages(list.map { it.id }),
                    folders.observeAll(ref.accountId),
                    accounts.observe(ref.accountId)
                ) { files, allFolders, account ->
                    ConversationData(
                        accountEmail = account?.email,
                        messages = list,
                        attachments = files.groupBy { it.messageId },
                        folders = allFolders
                    )
                }
            }
        }
}
