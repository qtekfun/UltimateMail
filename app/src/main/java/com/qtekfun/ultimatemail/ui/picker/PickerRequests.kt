// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.picker

import com.qtekfun.ultimatemail.domain.picker.PickerRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The move/label picker being asked for, if any. The list screens ask for it from anywhere (a
 * swipe, the selection bar) and [MovePickerHost] shows it over whatever screen is on.
 */
@Singleton
class PickerRequests @Inject constructor() {
    private val current = MutableStateFlow<PickerRequest?>(null)

    val request: StateFlow<PickerRequest?> = current

    fun open(request: PickerRequest) {
        current.value = request
    }

    fun close() {
        current.value = null
    }
}
