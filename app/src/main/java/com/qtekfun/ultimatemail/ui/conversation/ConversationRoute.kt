// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.conversation

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatemail.ui.nav.Screen
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Connects [ConversationScreen] to [ConversationViewModel] and does what needs an Android
 * context: opening and sharing downloaded attachments through the FileProvider, and saving them
 * to a place the reader picks. [onBack] leaves the conversation.
 */
@Composable
fun ConversationRoute(
    screen: Screen.Conversation,
    viewModel: ConversationViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val saveAs = rememberAttachmentSaver(onResult = viewModel::report)

    LaunchedEffect(screen) { viewModel.open(screen.ref) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                ConversationEvent.Close -> onBack()

                is ConversationEvent.AttachmentReady -> {
                    val done = when (event.action) {
                        AttachmentAction.OPEN ->
                            AttachmentIntents.open(context, event.path, event.mimeType, event.name)

                        AttachmentAction.SHARE ->
                            AttachmentIntents.share(context, event.path, event.mimeType, event.name)

                        AttachmentAction.SAVE -> {
                            saveAs(event)
                            true
                        }
                    }
                    if (!done) viewModel.report(NoticeKind.NO_APP_FOR_ATTACHMENT)
                }
            }
        }
    }

    ConversationScreen(
        state = state.takeIf { it.ref == screen.ref } ?: ConversationState(),
        actions = ConversationScreenActions(
            onBack = onBack,
            onToggleStar = viewModel::toggleStar,
            onMarkUnread = viewModel::markUnread,
            onArchive = viewModel::archive,
            onDelete = viewModel::delete,
            onCompose = viewModel::compose,
            onToggleMessage = viewModel::toggle,
            onToggleDetails = viewModel::toggleDetails,
            onToggleQuoted = viewModel::toggleQuoted,
            onAllowRemote = viewModel::allowRemoteContent,
            onToggleOriginalColors = viewModel::toggleOriginalColors,
            onRetryBody = viewModel::ensureBody,
            onAttachment = viewModel::attachment
        )
    )
}

/**
 * Lets the reader pick where to save a downloaded attachment (a system "create document" screen),
 * then copies the file there. [onResult] gets whether it worked. Call the result with the event.
 */
@Composable
private fun rememberAttachmentSaver(
    onResult: (NoticeKind) -> Unit
): (ConversationEvent.AttachmentReady) -> Unit {
    val context = LocalContext.current
    val coroutines = rememberCoroutineScope()
    var pending by remember { mutableStateOf<ConversationEvent.AttachmentReady?>(null) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) {
            val ready = pending
            pending = null
            if (it != null && ready != null) {
                coroutines.launch {
                    val saved = withContext(Dispatchers.IO) { copyTo(context, ready.path, it) }
                    onResult(
                        if (saved) NoticeKind.ATTACHMENT_SAVED else NoticeKind.ATTACHMENT_NOT_SAVED
                    )
                }
            }
        }
    return { ready ->
        pending = ready
        launcher.launch(ready.name.ifBlank { File(ready.path).name })
    }
}

private fun copyTo(context: Context, path: String, target: Uri): Boolean = runCatching {
    context.contentResolver.openOutputStream(target)?.use { out ->
        File(path).inputStream().use { it.copyTo(out) }
    } != null
}.getOrDefault(false)
