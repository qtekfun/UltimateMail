// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.queue

import com.qtekfun.ultimatemail.data.local.model.OperationType

/**
 * A change the user made, to be queued. [payload] depends on [type]: a [FlagChange] encoding for
 * SET_FLAGS, the destination folder path for MOVE, free-form for the rest.
 */
data class NewOperation(
    val accountId: Long,
    val type: OperationType,
    val folderPath: String,
    val uid: Long,
    val payload: String
)
