// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.shell

import androidx.compose.runtime.Composable
import com.qtekfun.ultimatemail.ui.drawer.DrawerActions
import com.qtekfun.ultimatemail.ui.inbox.InboxActions

/** What the main screen can do: the menu, the list, and following the drawer's swipes. */
data class ShellActions(
    val onMenuOpenChange: (Boolean) -> Unit,
    val drawer: DrawerActions,
    val inbox: InboxActions
)

/** What the composer feature adds to the main screen (T18b). */
data class ShellCompose(
    /** Messages in the outbox: the side menu shows its entry only when there are some. */
    val outboxCount: Int = 0,
    /** The Compose button. */
    val onCompose: () -> Unit = {},
    /** Shown instead of the conversation list while the Drafts folder is selected. */
    val drafts: (@Composable () -> Unit)? = null
)
