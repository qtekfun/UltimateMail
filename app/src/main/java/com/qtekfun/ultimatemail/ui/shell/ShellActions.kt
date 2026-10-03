// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.shell

import com.qtekfun.ultimatemail.ui.drawer.DrawerActions
import com.qtekfun.ultimatemail.ui.inbox.InboxActions

/** What the main screen can do: the menu, the list, and following the drawer's swipes. */
data class ShellActions(
    val onMenuOpenChange: (Boolean) -> Unit,
    val drawer: DrawerActions,
    val inbox: InboxActions
)
