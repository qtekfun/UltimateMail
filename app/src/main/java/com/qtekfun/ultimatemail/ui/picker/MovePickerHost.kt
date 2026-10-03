// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.picker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatemail.domain.picker.MoveLabelActions
import com.qtekfun.ultimatemail.ui.conversation.NoticeCenter
import com.qtekfun.ultimatemail.ui.conversation.NoticeKind
import com.qtekfun.ultimatemail.ui.conversation.PendingUndo
import dagger.hilt.android.EntryPointAccessors

/**
 * Shows the move/label picker over the current screen when something asked for it, and when it
 * is applied offers "Undo" in the one snackbar of the app.
 */
@Composable
fun MovePickerHost() {
    val context = LocalContext.current
    val entry = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            MovePickerHostEntryPoint::class.java
        )
    }
    val request by entry.requests().request.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    request?.let { asked ->
        MoveLabelDialog(
            request = asked,
            onDismiss = entry.requests()::close,
            onApplied = { result ->
                entry.notices().post(
                    kind = NoticeKind.CUSTOM,
                    text = result.message(resources),
                    undo = PendingUndo(setOf(result.accountId)) { entry.actions().undo(result) }
                )
            }
        )
    }
}
