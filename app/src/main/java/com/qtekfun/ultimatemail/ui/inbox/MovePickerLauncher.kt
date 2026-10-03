// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.inbox

import com.qtekfun.ultimatemail.domain.inbox.MessageHandle
import com.qtekfun.ultimatemail.ui.conversation.NoticeCenter
import com.qtekfun.ultimatemail.ui.conversation.NoticeKind
import javax.inject.Inject

/** What to move: the messages (all of one account) of the conversations the user picked. */
data class MovePickerRequest(val accountId: Long, val messages: List<MessageHandle>)

/**
 * Opens the folder/label picker (T17) for the swipe "Move" and the selection bar. A seam: until
 * the picker is bound in `MovePickerModule`, [SnackbarMovePickerLauncher] says "coming soon".
 */
fun interface MovePickerLauncher {
    fun open(request: MovePickerRequest)
}

/** The stand-in until the picker exists. */
class SnackbarMovePickerLauncher @Inject constructor(private val notices: NoticeCenter) :
    MovePickerLauncher {
    override fun open(request: MovePickerRequest) {
        notices.post(NoticeKind.MOVE_SOON)
    }
}
