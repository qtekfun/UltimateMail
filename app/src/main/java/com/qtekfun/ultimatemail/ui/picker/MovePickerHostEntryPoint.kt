// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.picker

import com.qtekfun.ultimatemail.domain.picker.MoveLabelActions
import com.qtekfun.ultimatemail.ui.conversation.NoticeCenter
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** How [MovePickerHost] reaches the app's singletons from a composable. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface MovePickerHostEntryPoint {
    fun requests(): PickerRequests

    fun actions(): MoveLabelActions

    fun notices(): NoticeCenter
}
