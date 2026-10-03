// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.inbox

/** What can be done to a conversation of a list, by swipe or from the selection bar. */
enum class RowChange {
    ARCHIVE,
    DELETE,
    MARK_READ,
    MARK_UNREAD,
    STAR,
    UNSTAR;

    /** Whether the conversation leaves the list it is in. */
    val leavesList: Boolean get() = this == ARCHIVE || this == DELETE
}
