// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local.dao

/** The key of a conversation: enough to open it. */
data class ConversationKey(val accountId: Long, val folderPath: String, val threadId: String)
