// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.inbox

import com.qtekfun.ultimatemail.domain.conversation.ReaderList

/**
 * The list a conversation opened from this state belongs to, for the arrows of the reading
 * screen: the same scope, and only the unread ones when the list was filtered to them.
 */
fun InboxState.readerList(): ReaderList? =
    scope?.let { ReaderList(it, unreadOnly = filter == InboxFilter.UNREAD) }
