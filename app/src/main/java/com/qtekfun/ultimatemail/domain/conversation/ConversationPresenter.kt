// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.conversation

import com.qtekfun.ultimatemail.data.local.entity.AttachmentEntity
import com.qtekfun.ultimatemail.data.local.entity.MessageEntity
import com.qtekfun.ultimatemail.data.local.model.AttachmentState
import com.qtekfun.ultimatemail.data.settings.RemoteContentPolicy
import com.qtekfun.ultimatemail.domain.inbox.LabelPresentation

/**
 * Builds what the conversation screen draws from what Room holds ([ConversationData]) and what
 * the screen remembers ([ConversationLocal]). It holds no state of its own besides the cache of
 * [BodyPreparer], so the same inputs always give the same view.
 */
class ConversationPresenter(private val preparer: BodyPreparer = BodyPreparer()) {
    fun present(
        data: ConversationData,
        local: ConversationLocal,
        folderPath: String,
        remotePolicy: RemoteContentPolicy = RemoteContentPolicy.NEVER
    ): ConversationView {
        val own = setOfNotNull(data.accountEmail)
        val views = data.messages.map { message ->
            messageView(message, data.attachments[message.id].orEmpty(), local, own, remotePolicy)
        }
        val hiddenLabels = data.folders.filter { it.path == folderPath }
            .flatMap { listOf(it.path, it.name) }.toSet()
        return ConversationView(
            subject = data.messages.lastOrNull { it.subject.isNotBlank() }?.subject.orEmpty(),
            labels = LabelPresentation.summarize(
                labels = data.messages.flatMap { it.labels },
                hidden = hiddenLabels,
                maxChips = MAX_LABELS
            ),
            messages = views,
            newest = views.lastOrNull(),
            targets = FolderTargets.resolve(data.folders, folderPath)
        )
    }

    private fun messageView(
        message: MessageEntity,
        attachments: List<AttachmentEntity>,
        local: ConversationLocal,
        own: Set<String>,
        remotePolicy: RemoteContentPolicy
    ): MessageView {
        val expanded = message.id in local.expanded
        return MessageView(
            id = message.id,
            senderName = message.senderName,
            senderAddress = message.senderAddress,
            snippet = message.snippet,
            sentAt = message.sentAt,
            unread = !message.seen,
            flagged = message.flagged,
            pendingSync = message.pendingSync,
            expanded = expanded,
            recipients = RecipientSummary.of(message.toAddresses, message.ccAddresses, own),
            to = message.toAddresses,
            cc = message.ccAddresses,
            detailsShown = message.id in local.detailsShown,
            body = if (expanded) bodyView(message, local, remotePolicy) else null,
            attachments = listedAttachments(message, attachments, local),
            cidFiles = cidFiles(attachments)
        )
    }

    private fun bodyView(
        message: MessageEntity,
        local: ConversationLocal,
        remotePolicy: RemoteContentPolicy
    ): BodyView {
        val cached = message.bodyText != null || message.bodyHtml != null
        val load = local.bodyLoads[message.id]
        return when {
            cached -> {
                val remote = message.id in local.remoteAllowed
                BodyView.Ready(
                    preparer.prepare(message.id, message.bodyText, message.bodyHtml, remote),
                    quotedShown = message.id in local.quotedShown,
                    remoteAllowed = remote,
                    remotePolicy = remotePolicy,
                    originalColors = message.id in local.originalColors
                )
            }

            load is BodyLoad.Failed -> BodyView.Failed(load.reason, message.snippet)

            // Nothing yet, or the fetch is on its way.
            else -> BodyView.Loading
        }
    }

    /** Attachments the reader can act on: the ones shown inside the message are not listed. */
    private fun listedAttachments(
        message: MessageEntity,
        attachments: List<AttachmentEntity>,
        local: ConversationLocal
    ): List<AttachmentView> {
        val shownInline = message.bodyHtml?.let(ContentIds::referencedIn).orEmpty()
        return attachments
            .filterNot { it.inline && it.contentId != null && it.contentId in shownInline }
            .map { file ->
                AttachmentView(
                    id = file.id,
                    name = file.fileName,
                    mimeType = file.mimeType,
                    size = file.size,
                    kind = AttachmentKind.of(file.mimeType, file.fileName),
                    state = effectiveState(file, local)
                )
            }
    }

    /** A download that is no longer running (the app died) can be started again. */
    private fun effectiveState(file: AttachmentEntity, local: ConversationLocal) =
        if (file.state == AttachmentState.DOWNLOADING && file.id !in local.downloading) {
            AttachmentState.REMOTE
        } else {
            file.state
        }

    private fun cidFiles(attachments: List<AttachmentEntity>): Map<String, CidFile> =
        attachments.mapNotNull { file ->
            val id = file.contentId
            val path = file.localPath
            if (id != null && path != null && isServableImage(file)) {
                id to CidFile(path, file.mimeType)
            } else {
                null
            }
        }.toMap()

    private fun isServableImage(file: AttachmentEntity) =
        file.state == AttachmentState.DOWNLOADED &&
            file.mimeType.startsWith("image/", ignoreCase = true)

    private companion object {
        const val MAX_LABELS = 8
    }
}
