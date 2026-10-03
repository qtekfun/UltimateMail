// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.inbox

import com.qtekfun.ultimatemail.domain.inbox.MessageHandle
import com.qtekfun.ultimatemail.domain.picker.MessageRef
import com.qtekfun.ultimatemail.domain.picker.PickerRequest
import com.qtekfun.ultimatemail.ui.picker.PickerRequests
import javax.inject.Inject

/** What to move: the messages (all of one account) of the conversations the user picked. */
data class MovePickerRequest(val accountId: Long, val messages: List<MessageHandle>)

/**
 * Opens the folder/label picker (T17) for the swipe "Move" and the selection bar.
 */
fun interface MovePickerLauncher {
    fun open(request: MovePickerRequest)
}

/** Opens the real picker (T17) through [PickerRequests]; [MovePickerHost] draws it. */
class DialogMovePickerLauncher @Inject constructor(private val requests: PickerRequests) :
    MovePickerLauncher {
    override fun open(request: MovePickerRequest) {
        requests.open(
            PickerRequest(
                request.accountId,
                request.messages.map { MessageRef(it.folderPath, it.uid) }
            )
        )
    }
}
