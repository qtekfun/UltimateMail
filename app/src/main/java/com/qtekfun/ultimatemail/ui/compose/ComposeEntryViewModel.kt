// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatemail.domain.account.AccountListing
import com.qtekfun.ultimatemail.domain.account.AccountSummary
import com.qtekfun.ultimatemail.domain.compose.AccountChoice
import com.qtekfun.ultimatemail.domain.compose.ComposeEngine
import com.qtekfun.ultimatemail.domain.compose.ComposeOpener
import com.qtekfun.ultimatemail.domain.compose.ComposeState
import com.qtekfun.ultimatemail.domain.compose.Draft
import com.qtekfun.ultimatemail.domain.compose.DraftEdit
import com.qtekfun.ultimatemail.domain.compose.IncomingAccountChoice
import com.qtekfun.ultimatemail.domain.compose.IncomingCompose
import com.qtekfun.ultimatemail.domain.compose.OpenedDraft
import com.qtekfun.ultimatemail.ui.conversation.NoticeCenter
import com.qtekfun.ultimatemail.ui.conversation.NoticeKind
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A message from another app waiting for the user to say which account it is written from. */
data class IncomingChoice(val incoming: IncomingCompose, val accounts: List<AccountSummary>) {
    override fun toString(): String = "IncomingChoice(accounts=${accounts.size})"
}

/**
 * How every way into the composer ends up with an open draft: it takes the starts of
 * [ComposeEntry] (reply, forward, a draft, a mailto or share), creates the draft through
 * [ComposeEngine] and publishes its id on [opened] for the navigation. It is also where the
 * outbox badge and the notice of a send that failed for good come from, because it lives as
 * long as the activity.
 */
@HiltViewModel
class ComposeEntryViewModel @Inject constructor(
    private val entry: ComposeEntry,
    private val engine: ComposeEngine,
    private val opener: ComposeOpener,
    private val accounts: AccountListing,
    composeState: ComposeState,
    private val notices: NoticeCenter
) : ViewModel() {
    private val opening = Channel<Long>(Channel.BUFFERED)

    /** The ids of drafts the composer should be shown for, each delivered once. */
    val opened: Flow<Long> = opening.receiveAsFlow()

    private val shown = MutableStateFlow<Long?>(null)
    private val choice = MutableStateFlow<IncomingChoice?>(null)

    /** Set while a message from another app needs an account to be chosen. */
    val choosing: StateFlow<IncomingChoice?> = choice

    /** How many messages wait in the outbox (for the badge of the side menu). */
    val outboxCount: StateFlow<Int> = composeState.observeOutboxCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    init {
        viewModelScope.launch { entry.starts.collect { start(it) } }
        viewModelScope.launch {
            // Only a rise counts: the notice is about a send that has just failed.
            var known: Int? = null
            composeState.observeCounts().map { it.failed }.distinctUntilChanged().collect { now ->
                val before = known
                if (before != null && now > before) notices.post(NoticeKind.SEND_FAILED)
                known = now
            }
        }
    }

    /** The account the main screen shows now (null for the unified inbox): the default sender. */
    fun setShownAccount(accountId: Long?) {
        shown.value = accountId
    }

    /** Opens the composer for [start]; the draft id comes out of [opened]. */
    fun start(start: ComposeStart) {
        viewModelScope.launch {
            val draft = when (start) {
                is ComposeStart.Message -> shown(opener.message(start.request))
                is ComposeStart.ServerDraft -> shown(opener.serverDraft(start.messageRowId))
                is ComposeStart.New -> engine.newMessage(start.accountId)
                is ComposeStart.Draft -> engine.open(start.draftId)
                is ComposeStart.Incoming -> incoming(start.incoming)
            }
            when {
                draft != null -> opening.send(draft.id)
                start !is ComposeStart.Incoming -> notices.post(NoticeKind.COMPOSE_FAILED)
            }
        }
    }

    /** The draft of [opened] (null: none), with a notice if attachments did not come. */
    private fun shown(opened: OpenedDraft?): Draft? {
        if (opened != null && opened.attachmentsSkipped > 0) {
            notices.post(NoticeKind.ATTACHMENTS_SKIPPED)
        }
        return opened?.draft
    }

    /** The account was picked for the message waiting in [choosing]. */
    fun chooseAccount(accountId: Long) {
        val waiting = choice.value ?: return
        choice.value = null
        viewModelScope.launch { create(accountId, waiting.incoming)?.let { opening.send(it.id) } }
    }

    fun dismissChoice() {
        choice.value = null
    }

    private suspend fun incoming(incoming: IncomingCompose): Draft? {
        val list = accounts.observe().first()
        return when (val pick = IncomingAccountChoice.choose(list.map { it.id }, shown.value)) {
            AccountChoice.None -> {
                notices.post(NoticeKind.COMPOSE_FAILED)
                null
            }

            is AccountChoice.Use -> create(pick.accountId, incoming)

            is AccountChoice.Ask -> {
                choice.value = IncomingChoice(incoming, list)
                null
            }
        }
    }

    /** A new draft with everything the other app asked for already in it. */
    private suspend fun create(accountId: Long, incoming: IncomingCompose): Draft? {
        val draft = engine.newMessage(accountId, incoming.to) ?: return null
        val edit = DraftEdit(
            to = draft.to,
            cc = incoming.cc,
            bcc = incoming.bcc,
            subject = incoming.subject,
            // The text goes before the signature the engine already put in.
            body = incoming.body + draft.body
        )
        engine.save(draft.id, edit)
        val failed = opener.attachPicked(draft.id, incoming.attachments)
        if (failed > 0) notices.post(NoticeKind.ATTACHMENTS_SKIPPED)
        return engine.open(draft.id)
    }
}
