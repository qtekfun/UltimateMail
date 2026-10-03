// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatemail.data.local.model.DraftState
import com.qtekfun.ultimatemail.di.ApplicationScope
import com.qtekfun.ultimatemail.di.IoDispatcher
import com.qtekfun.ultimatemail.domain.account.AccountListing
import com.qtekfun.ultimatemail.domain.compose.AddAttachmentResult
import com.qtekfun.ultimatemail.domain.compose.ComposeEngine
import com.qtekfun.ultimatemail.domain.compose.DraftAttachments
import com.qtekfun.ultimatemail.domain.compose.DraftChange
import com.qtekfun.ultimatemail.domain.compose.DraftEdit
import com.qtekfun.ultimatemail.domain.compose.DraftServerSync
import com.qtekfun.ultimatemail.domain.compose.OutboxFileStorage
import com.qtekfun.ultimatemail.domain.compose.RecipientFields
import com.qtekfun.ultimatemail.domain.compose.RecipientFieldState
import com.qtekfun.ultimatemail.domain.compose.RecipientSuggestions
import com.qtekfun.ultimatemail.domain.compose.SendCheck
import com.qtekfun.ultimatemail.domain.compose.SendConfirmations
import com.qtekfun.ultimatemail.domain.compose.SendDraft
import com.qtekfun.ultimatemail.domain.compose.SendInput
import com.qtekfun.ultimatemail.domain.compose.SendResult
import com.qtekfun.ultimatemail.domain.compose.SendValidation
import com.qtekfun.ultimatemail.domain.mail.MailAddress
import com.qtekfun.ultimatemail.ui.conversation.NoticeCenter
import com.qtekfun.ultimatemail.ui.conversation.NoticeKind
import com.qtekfun.ultimatemail.ui.conversation.PendingUndo
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The composer screen (RF-07, RF-08). The draft lives in Room (the engine owns it); this holds
 * the text while it is being edited, autosaves it through [ComposeEngine.autosave] and decides
 * what the buttons do. Only the draft id has to survive process death.
 *
 * **Leaving.** Back saves the draft and says "Draft saved" (it is offered in Drafts); a draft
 * the user never touched is dropped silently so empty messages do not pile up. Discard asks
 * first. Both ask the server copy to be refreshed or removed.
 *
 * **Sending** is undoable: the composer closes at once and the app's snackbar says "Sending..."
 * with Undo for [UNDO_SEND_MILLIS]. Only when that window ends is the draft handed to the queue
 * ([SendDraft]); Undo reopens the very same draft, nothing was queued. The timer runs in the
 * application scope, so a send is not lost when the screen goes away; if the process itself
 * dies in that window, the message is still a draft in Drafts, never lost and never sent twice.
 *
 * It is scoped to the activity like the other view models, so the undo outlives the screen.
 */
// One function per thing the composer can do on the screen; they share the draft's state.
@Suppress("TooManyFunctions")
@HiltViewModel
class ComposerViewModel @Inject constructor(
    private val engine: ComposeEngine,
    private val attachments: DraftAttachments,
    private val suggestions: RecipientSuggestions,
    private val sendDraft: SendDraft,
    private val serverSync: DraftServerSync,
    private val files: OutboxFileStorage,
    private val accounts: AccountListing,
    private val notices: NoticeCenter,
    private val entry: ComposeEntry,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher
) : ViewModel() {
    private class PendingSend(val draftId: Long, val noticeId: Long, val timer: Job)

    private val mutable = MutableStateFlow(ComposerState())
    val state: StateFlow<ComposerState> = mutable.asStateFlow()

    private val edits = MutableSharedFlow<DraftEdit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private var autosave: Job? = null
    private var observers: Job? = null
    private var suggesting: Job? = null
    private var pendingSend: PendingSend? = null

    // Not state of the screen: what close and send need to decide.
    private var touched = false
    private var untouchedDraft = false
    private var template: String? = null
    private var confirmations = SendConfirmations()

    // ---- Opening -------------------------------------------------------------------------

    /** Shows the draft [draftId]; does nothing if it is already the one being edited. */
    fun load(draftId: Long) {
        val now = mutable.value
        val busy = now.phase == ComposerPhase.LOADING || now.phase == ComposerPhase.EDITING
        if (now.draftId == draftId && busy) return
        viewModelScope.launch { open(draftId) }
    }

    private suspend fun open(id: Long) {
        leaveEditing()
        cancelPendingSend(id)
        mutable.value = ComposerState(draftId = id)
        val draft = engine.open(id)?.takeIf { it.state == DraftState.EDITING }
        if (draft == null) {
            mutable.value = ComposerState(phase = ComposerPhase.GONE, draftId = id)
            return
        }
        touched = false
        untouchedDraft = draft.revision == 0
        template = draft.body.takeIf { untouchedDraft }
        confirmations = SendConfirmations()
        mutable.value = ComposerState(
            phase = ComposerPhase.EDITING,
            draftId = id,
            kind = draft.kind,
            senderId = draft.accountId,
            to = RecipientFieldState.of(draft.to),
            cc = RecipientFieldState.of(draft.cc),
            bcc = RecipientFieldState.of(draft.bcc),
            showCcBcc = draft.cc.isNotEmpty() || draft.bcc.isNotEmpty(),
            subject = draft.subject,
            body = draft.body
        )
        observe(id)
        startAutosave(id)
    }

    /** Another draft is being opened while this one is on screen: keep what was typed. */
    private suspend fun leaveEditing() {
        val now = mutable.value
        if (now.phase != ComposerPhase.EDITING) return
        stopAutosave()
        if (touched) engine.save(now.draftId, editOf(now))
    }

    private fun observe(id: Long) {
        observers?.cancel()
        observers = viewModelScope.launch {
            launch {
                attachments.observe(id).collect { list ->
                    mutable.update { it.copy(attachments = list) }
                }
            }
            launch {
                accounts.observe().collect { list ->
                    val senders = list.map { SenderOption(it.id, it.email, it.displayName) }
                    mutable.update { it.copy(senders = senders) }
                }
            }
        }
    }

    // ---- Editing -------------------------------------------------------------------------

    fun onInput(kind: RecipientKind, text: String) {
        val next = RecipientFields.typed(mutable.value.field(kind), text)
        edited { it.withField(kind, next) }
        suggest(kind, next.input)
    }

    /** The user left the field or pressed Done: what is typed becomes chips. */
    fun commit(kind: RecipientKind) {
        val field = mutable.value.field(kind)
        if (field.input.isBlank()) return
        edited { it.withField(kind, RecipientFields.commit(field)).copy(suggestions = null) }
    }

    fun pickSuggestion(kind: RecipientKind, address: MailAddress) {
        edited {
            it.withField(kind, RecipientFields.pick(it.field(kind), address))
                .copy(suggestions = null)
        }
    }

    fun removeChip(kind: RecipientKind, index: Int) {
        edited { it.withField(kind, RecipientFields.remove(it.field(kind), index)) }
    }

    fun showCcBcc() {
        mutable.update { it.copy(showCcBcc = true) }
    }

    fun onSubject(text: String) = edited { it.copy(subject = text) }

    fun onBody(text: String) = edited { it.copy(body = text) }

    private fun edited(change: (ComposerState) -> ComposerState) {
        mutable.update { change(it).copy(message = null) }
        touched = true
        confirmations = SendConfirmations()
        edits.tryEmit(editOf(mutable.value))
    }

    private fun editOf(s: ComposerState) =
        DraftEdit(s.to.addresses, s.cc.addresses, s.bcc.addresses, s.subject, s.body)

    private fun commitInputs() {
        RecipientKind.entries.forEach { commit(it) }
    }

    private fun suggest(kind: RecipientKind, typed: String) {
        suggesting?.cancel()
        val query = typed.trim()
        if (query.isEmpty()) {
            mutable.update { it.copy(suggestions = null) }
            return
        }
        suggesting = viewModelScope.launch {
            val s = mutable.value
            val own = s.senders.firstOrNull { it.id == s.senderId }?.email
            val taken = s.field(kind).addresses.map { it.address.lowercase() }.toSet()
            val items = suggestions.suggest(s.senderId, query, own, SUGGESTION_LIMIT)
                .filter { it.address.lowercase() !in taken }
            mutable.update {
                it.copy(
                    suggestions = items.takeIf { list -> list.isNotEmpty() }
                        ?.let { list -> RecipientSuggestionList(kind, list) }
                )
            }
        }
    }

    private fun startAutosave(id: Long) {
        autosave?.cancel()
        autosave = viewModelScope.launch { engine.autosave(id, edits) }
    }

    private fun stopAutosave() {
        autosave?.cancel()
        autosave = null
    }

    // ---- Sender and attachments ----------------------------------------------------------

    /**
     * Changes the account the message is sent from. The text is saved first so that the swap of
     * the signature block works on what the user sees, then the engine swaps only that block.
     */
    fun selectSender(accountId: Long) {
        val now = mutable.value
        if (now.phase != ComposerPhase.EDITING || accountId == now.senderId) return
        viewModelScope.launch {
            stopAutosave()
            commitInputs()
            engine.save(now.draftId, editOf(mutable.value))
            when (engine.changeSender(now.draftId, accountId)) {
                DraftChange.SAVED -> engine.open(now.draftId)?.let { draft ->
                    touched = true
                    mutable.update { it.copy(senderId = draft.accountId, body = draft.body) }
                }

                DraftChange.MISSING, DraftChange.NOT_EDITABLE ->
                    mutable.update { it.copy(message = ComposerMessage.DraftGone) }
            }
            startAutosave(now.draftId)
        }
    }

    /** Attaches the files the picker returned (the draft keeps its own copies). */
    fun attach(uris: List<String>) {
        val id = mutable.value.draftId
        if (mutable.value.phase != ComposerPhase.EDITING || uris.isEmpty()) return
        touched = true
        viewModelScope.launch {
            uris.forEach { uri ->
                val message = when (val result = attachments.add(id, uri)) {
                    is AddAttachmentResult.Added -> null
                    is AddAttachmentResult.TooLarge -> ComposerMessage.AttachmentTooLarge(
                        result.limitBytes
                    )
                    AddAttachmentResult.Unreadable -> ComposerMessage.AttachmentUnreadable
                    AddAttachmentResult.DraftMissing, AddAttachmentResult.NotEditable ->
                        ComposerMessage.DraftGone
                }
                if (message != null) mutable.update { it.copy(message = message) }
            }
        }
    }

    fun removeAttachment(attachmentId: Long) {
        touched = true
        viewModelScope.launch { attachments.remove(attachmentId) }
    }

    fun dismissMessage() {
        mutable.update { it.copy(message = null) }
    }

    // ---- Sending -------------------------------------------------------------------------

    /** The Send button: checks the message, asks what needs asking, then starts the undo window. */
    fun send() {
        if (mutable.value.phase != ComposerPhase.EDITING) return
        viewModelScope.launch {
            commitInputs()
            proceedWithSend()
        }
    }

    /** "Send anyway" on an empty subject or body, or "Discard" on its question. */
    fun confirm() {
        val dialog = mutable.value.dialog ?: return
        mutable.update { it.copy(dialog = null) }
        when (dialog) {
            ComposerDialog.EMPTY_SUBJECT -> {
                confirmations = confirmations.copy(emptySubject = true)
                send()
            }

            ComposerDialog.EMPTY_BODY -> {
                confirmations = confirmations.copy(emptyBody = true)
                send()
            }

            ComposerDialog.DISCARD -> discard()
        }
    }

    fun dismissDialog() {
        mutable.update { it.copy(dialog = null) }
    }

    private suspend fun proceedWithSend() {
        val now = mutable.value
        val missing = withContext(io) { now.attachments.any { !files.exists(it.filePath) } }
        val input = SendInput(
            now.to,
            now.cc,
            now.bcc,
            now.subject,
            now.body,
            now.kind,
            template,
            now.attachments.size,
            missing
        )
        when (SendValidation.check(input, confirmations)) {
            SendCheck.NoRecipients -> tell(ComposerMessage.NoRecipients)
            SendCheck.InvalidRecipient -> tell(ComposerMessage.InvalidRecipient)
            SendCheck.AttachmentMissing -> tell(ComposerMessage.AttachmentMissing)
            SendCheck.ConfirmEmptySubject -> ask(ComposerDialog.EMPTY_SUBJECT)
            SendCheck.ConfirmEmptyBody -> ask(ComposerDialog.EMPTY_BODY)
            SendCheck.Ready -> beginUndoWindow(now)
        }
    }

    private fun tell(message: ComposerMessage) = mutable.update { it.copy(message = message) }

    private fun ask(dialog: ComposerDialog) = mutable.update { it.copy(dialog = dialog) }

    /** Saves the draft, closes the composer and gives the user [UNDO_SEND_MILLIS] to take it back. */
    private suspend fun beginUndoWindow(now: ComposerState) {
        stopAutosave()
        val id = now.draftId
        val change = engine.save(id, editOf(now))
        if (change != DraftChange.SAVED) return tell(ComposerMessage.DraftGone)
        val undo = PendingUndo(emptySet(), onCommit = { sendNow(id) }) { reopen() }
        val noticeId = notices.post(
            NoticeKind.SENDING,
            undo = undo,
            holdUntilCleared = true
        )
        val timer = appScope.launch {
            delay(UNDO_SEND_MILLIS)
            notices.commit(noticeId)
        }
        pendingSend = PendingSend(id, noticeId, timer)
        finish(id)
    }

    /** Undo: the same draft is opened again, nothing had been queued. */
    private fun reopen() {
        val pending = pendingSend ?: return
        pending.timer.cancel()
        pendingSend = null
        entry.request(ComposeStart.Draft(pending.draftId))
    }

    /** The window ended: the draft goes to the queue (and the sync that sends it is requested). */
    private fun sendNow(id: Long) {
        pendingSend = null
        appScope.launch {
            if (sendDraft(id) !is SendResult.Queued) notices.post(NoticeKind.SEND_PROBLEM)
        }
    }

    /** The draft is being edited again (opened from Drafts in the window): no send, then. */
    private fun cancelPendingSend(draftId: Long) {
        val pending = pendingSend?.takeIf { it.draftId == draftId } ?: return
        pending.timer.cancel()
        notices.takeUndo(pending.noticeId)
        pendingSend = null
    }

    // ---- Leaving -------------------------------------------------------------------------

    /** Back or the close button: the draft is saved and offered in Drafts. */
    fun close() {
        val now = mutable.value
        if (now.phase != ComposerPhase.EDITING) {
            finish(now.draftId)
            return
        }
        viewModelScope.launch {
            commitInputs()
            stopAutosave()
            val id = now.draftId
            if (!touched && untouchedDraft) {
                engine.discard(id)
            } else {
                saveAndTell(id)
            }
            finish(id)
        }
    }

    private suspend fun saveAndTell(id: Long) {
        val saved = !touched || engine.save(id, editOf(mutable.value)) == DraftChange.SAVED
        if (saved) {
            serverSync.request(id, force = true)
            if (touched) notices.post(NoticeKind.DRAFT_SAVED)
        }
    }

    fun requestDiscard() {
        if (mutable.value.phase == ComposerPhase.EDITING) ask(ComposerDialog.DISCARD)
    }

    private fun discard() {
        val id = mutable.value.draftId
        stopAutosave()
        viewModelScope.launch {
            engine.discard(id)
            finish(id)
        }
    }

    /** The screen went back: forget that the composer was finished. */
    fun acknowledgeFinished() {
        if (mutable.value.phase == ComposerPhase.FINISHED) mutable.value = ComposerState()
    }

    private fun finish(draftId: Long) {
        observers?.cancel()
        suggesting?.cancel()
        stopAutosave()
        mutable.value = ComposerState(phase = ComposerPhase.FINISHED, draftId = draftId)
    }

    companion object {
        /** How long the user can take a send back (the snackbar's window). */
        const val UNDO_SEND_MILLIS = 5_000L
        private const val SUGGESTION_LIMIT = 5
    }
}
