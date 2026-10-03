// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatemail.domain.compose.ComposeState
import com.qtekfun.ultimatemail.domain.compose.OutboxActions
import com.qtekfun.ultimatemail.domain.compose.OutboxChange
import com.qtekfun.ultimatemail.domain.compose.OutboxEntry
import com.qtekfun.ultimatemail.domain.compose.OutboxReason
import com.qtekfun.ultimatemail.domain.compose.OutboxState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How the send of one outbox message is going. */
sealed interface OutboxRowStatus {
    /** Waiting for the queue; [reason] says why it has not gone yet (null: just not its turn). */
    data class Waiting(val reason: OutboxReason?, val nextAttemptAt: Instant?) : OutboxRowStatus

    data object Sending : OutboxRowStatus

    /** Refused for good: retry, edit or discard. */
    data class Failed(val reason: OutboxReason) : OutboxRowStatus
}

/** One message of the outbox as the list shows it. [toString] shows nothing of the message. */
data class OutboxRow(
    val draftId: Long,
    val accountId: Long,
    val subject: String,
    /** The first recipient, for the line under the subject. */
    val recipient: String?,
    val recipientCount: Int,
    val status: OutboxRowStatus
) {
    override fun toString(): String = "OutboxRow(draft=$draftId)"
}

/** What the outbox asks the user. */
sealed interface OutboxPrompt {
    /** The message may already have gone out: it cannot be edited or discarded. */
    data object MaybeSent : OutboxPrompt

    /** Confirm throwing the message away. */
    data class ConfirmDiscard(val draftId: Long) : OutboxPrompt
}

data class OutboxUiState(
    val loaded: Boolean = false,
    val rows: List<OutboxRow> = emptyList(),
    val prompt: OutboxPrompt? = null
)

/** The reading of an [OutboxState] for the list. */
internal fun OutboxState.toStatus(): OutboxRowStatus = when (this) {
    is OutboxState.Queued ->
        OutboxRowStatus.Waiting(reason?.let { OutboxReason.of(it) }, nextAttemptAt)

    OutboxState.Sending -> OutboxRowStatus.Sending

    is OutboxState.Failed -> OutboxRowStatus.Failed(OutboxReason.of(reason))
}

/**
 * The Outbox (RF-07): the messages that are queued, being sent or failed, with the actions the
 * engine allows: retry a failed one, take it back to edit, or discard it. A message that may
 * already have been sent is never edited or discarded (the engine says
 * [OutboxChange.MAY_BE_SENT] and the user is told to check Sent first).
 */
@HiltViewModel
class OutboxViewModel @Inject constructor(
    composeState: ComposeState,
    private val actions: OutboxActions,
    private val entry: ComposeEntry
) : ViewModel() {
    private val prompt = MutableStateFlow<OutboxPrompt?>(null)

    val state: StateFlow<OutboxUiState> = combine(
        composeState.observeOutbox().map { list -> list.map { it.toRow() } },
        prompt
    ) { rows, asking -> OutboxUiState(loaded = true, rows = rows, prompt = asking) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), OutboxUiState())

    fun retry(draftId: Long) {
        viewModelScope.launch { actions.retry(draftId) }
    }

    /** Takes the message back and opens the composer on it. */
    fun edit(draftId: Long) {
        viewModelScope.launch {
            when (actions.editAgain(draftId)) {
                OutboxChange.DONE -> entry.request(ComposeStart.Draft(draftId))
                OutboxChange.MAY_BE_SENT -> prompt.value = OutboxPrompt.MaybeSent
                OutboxChange.MISSING -> Unit
            }
        }
    }

    fun requestDiscard(draftId: Long) {
        prompt.value = OutboxPrompt.ConfirmDiscard(draftId)
    }

    fun confirmDiscard() {
        val asking = prompt.value as? OutboxPrompt.ConfirmDiscard ?: return
        prompt.value = null
        viewModelScope.launch {
            if (actions.discard(asking.draftId) == OutboxChange.MAY_BE_SENT) {
                prompt.value = OutboxPrompt.MaybeSent
            }
        }
    }

    fun dismissPrompt() {
        prompt.update { null }
    }

    private fun OutboxEntry.toRow(): OutboxRow {
        val first = draft.recipients.firstOrNull()
        return OutboxRow(
            draftId = draft.id,
            accountId = draft.accountId,
            subject = draft.subject,
            recipient = first?.let { it.name?.takeIf { name -> name.isNotBlank() } ?: it.address },
            recipientCount = draft.recipients.size,
            status = state.toStatus()
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
