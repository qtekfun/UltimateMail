// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.conversation

import com.qtekfun.ultimatemail.data.local.model.AttachmentState
import com.qtekfun.ultimatemail.data.settings.RemoteContentPolicy
import com.qtekfun.ultimatemail.domain.inbox.LabelSummary
import java.time.Instant

/** Why the body of a message could not be loaded. */
enum class BodyFailure {
    /** No connection, or the server did not answer: worth retrying later. */
    OFFLINE,

    /** The account has to sign in again. */
    SIGN_IN,

    /** The message is not on the server any more. */
    GONE,

    OTHER
}

/** A body that is not in Room yet: being fetched, or the fetch failed. */
sealed interface BodyLoad {
    data object Loading : BodyLoad

    data class Failed(val reason: BodyFailure) : BodyLoad
}

/** What the screen remembers about a conversation beyond what Room holds. */
data class ConversationLocal(
    /** Ids of the messages shown open. */
    val expanded: Set<Long> = emptySet(),
    /** Ids of the messages whose full To and Cc are shown. */
    val detailsShown: Set<Long> = emptySet(),
    /** Ids of the messages whose quoted earlier mail is unfolded. */
    val quotedShown: Set<Long> = emptySet(),
    /** Ids of the messages whose remote content the reader allowed. */
    val remoteAllowed: Set<Long> = emptySet(),
    /** Ids of the messages the reader wants in the colours the sender chose, in dark theme. */
    val originalColors: Set<Long> = emptySet(),
    val bodyLoads: Map<Long, BodyLoad> = emptyMap(),
    /** Ids of the attachments being downloaded right now. */
    val downloading: Set<Long> = emptySet()
)

/** An image part of a message that `cid:` references can be served from. */
data class CidFile(val path: String, val mimeType: String)

/** The body area of an open message. */
sealed interface BodyView {
    data object Loading : BodyView

    /** [preview] is the snippet kept with the headers, shown while the body is out of reach. */
    data class Failed(val reason: BodyFailure, val preview: String) : BodyView

    data class Ready(
        val body: PreparedBody,
        val quotedShown: Boolean,
        val remoteAllowed: Boolean,
        val remotePolicy: RemoteContentPolicy = RemoteContentPolicy.NEVER,
        val originalColors: Boolean = false
    ) : BodyView {
        val rendered: RenderedBody get() = body.shown(quotedShown)

        /** The notice offering "load images": something was blocked, not yet allowed. */
        val remoteBanner: RemoteBanner?
            get() = RemoteBanners.of(remotePolicy, body.hadBlockedRemoteContent, remoteAllowed)
    }
}

data class AttachmentView(
    val id: Long,
    /** May be empty: the sender gave no name. */
    val name: String,
    val mimeType: String,
    val size: Long,
    val kind: AttachmentKind,
    val state: AttachmentState
)

data class MessageView(
    val id: Long,
    val senderName: String,
    val senderAddress: String,
    val snippet: String,
    val sentAt: Instant,
    val unread: Boolean,
    val flagged: Boolean,
    val pendingSync: Boolean,
    val expanded: Boolean,
    val recipients: RecipientSummary,
    val to: List<String>,
    val cc: List<String>,
    val detailsShown: Boolean,
    /** Null while the message is collapsed. */
    val body: BodyView?,
    val attachments: List<AttachmentView>,
    /** Normalized Content-ID to the downloaded image, for the `cid:` images of the HTML. */
    val cidFiles: Map<String, CidFile>
) {
    /** The name to show: the display name, or the address when there is none. */
    val sender: String get() = senderName.ifBlank { senderAddress }
}

/** Everything the conversation screen draws. */
data class ConversationView(
    val subject: String,
    val labels: LabelSummary,
    val messages: List<MessageView>,
    /** The newest message: the one star, mark unread, reply and the like apply to. */
    val newest: MessageView?,
    val targets: FolderTargets
)
