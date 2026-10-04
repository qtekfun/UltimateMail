// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.conversation

import com.qtekfun.ultimatemail.domain.conversation.ComposeMode

/** What the conversation screen can do. */
data class ConversationScreenActions(
    val onBack: () -> Unit,
    val onToggleStar: () -> Unit,
    val onMarkUnread: () -> Unit,
    val onArchive: () -> Unit,
    val onDelete: () -> Unit,
    val onMove: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onComposeNew: () -> Unit,
    val onCompose: (ComposeMode) -> Unit,
    val onToggleMessage: (Long) -> Unit,
    val onToggleDetails: (Long) -> Unit,
    val onToggleQuoted: (Long) -> Unit,
    val onAllowRemote: (Long) -> Unit,
    val onToggleOriginalColors: (Long) -> Unit,
    val onRetryBody: (Long) -> Unit,
    val onAttachment: (id: Long, action: AttachmentAction) -> Unit
)
