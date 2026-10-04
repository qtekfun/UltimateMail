// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.data.local.UltimateMailDatabase
import com.qtekfun.ultimatemail.data.local.entity.AccountEntity
import com.qtekfun.ultimatemail.data.local.model.DraftKind
import com.qtekfun.ultimatemail.data.local.model.DraftState
import com.qtekfun.ultimatemail.di.IoDispatcher
import com.qtekfun.ultimatemail.domain.conversation.ComposeMode
import com.qtekfun.ultimatemail.domain.conversation.ComposeRequest
import com.qtekfun.ultimatemail.domain.mail.MailAddress
import com.qtekfun.ultimatemail.domain.signature.SignatureEditor
import com.qtekfun.ultimatemail.domain.signature.SignatureSettings
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext

/**
 * The composer's engine (RF-07, RF-08): everything a composer screen needs that is not drawing.
 * The screen (T18b) holds a draft id and talks to these classes, all injectable:
 *
 * 1. **Start.** [newMessage], [start] (a [ComposeRequest] from the reader: reply, reply all or
 *    forward) or [open] (a draft from the Drafts list) give a [Draft]; keep its `id` (it survives
 *    process death, the composer is just a view of it). Recipients, subject prefix, quote,
 *    References and the signature are already in it.
 * 2. **Edit.** Show [observe]; on every change call `DraftRepository.save` (or
 *    [autosave], which debounces a flow of [DraftEdit]s) and `DraftServerSync.request`. Check
 *    recipients with `RecipientParser`, suggest them with `RecipientSuggestions`, attach files
 *    with `DraftAttachments`, switch the sender with [changeSender].
 * 3. **Leave.** [discard] deletes the draft; closing the composer without sending needs nothing
 *    but a final save and `DraftServerSync.request(id, force = true)`.
 * 4. **Send.** `SendDraft` moves the draft to the outbox; `OutboxActions` and `ComposeState` show
 *    and manage it.
 */
class ComposeEngine @Inject constructor(
    database: UltimateMailDatabase,
    private val repository: DraftRepository,
    private val serverSync: DraftServerSync,
    private val templates: QuoteTemplatesProvider,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    private val drafts = database.draftDao()
    private val accounts = database.accountDao()
    private val messages = database.messageDao()
    private val quotes = QuoteBuilder(templates)

    /** A new, empty message from [accountId] with the account signature; null if no such account. */
    suspend fun newMessage(accountId: Long, to: List<MailAddress> = emptyList()): Draft? =
        withContext(io) {
            val account = accounts.get(accountId) ?: return@withContext null
            store(account, blank(account, DraftKind.NEW).copy(to = to), text = "")
        }

    /** Starts a reply, reply all or forward of the message in [request]; null if it is gone. */
    suspend fun start(request: ComposeRequest): Draft? = withContext(io) {
        val account = accounts.get(request.accountId) ?: return@withContext null
        val row = messages.getById(request.messageId) ?: return@withContext null
        val source = row.toComposeSource()
        val kind = when (request.mode) {
            ComposeMode.REPLY -> DraftKind.REPLY
            ComposeMode.REPLY_ALL -> DraftKind.REPLY_ALL
            ComposeMode.FORWARD -> DraftKind.FORWARD
        }
        val recipients = ReplyRecipientsRule.of(kind, source, setOf(account.email))
        val draft = blank(account, kind).copy(
            to = recipients.to,
            cc = recipients.cc,
            source = DraftSource(source.accountId, source.folderPath, source.uid, source.messageId)
        )
        // The user's own line comes first, then the signature (above the quote by default).
        if (kind == DraftKind.FORWARD) {
            val text = "\n\n" + quotes.forward(source)
            store(account, draft.copy(subject = ComposeSubject.forward(source.subject)), text)
        } else {
            val headers = ReferenceChain.forReply(source)
            val replying = draft.copy(
                subject = ComposeSubject.reply(source.subject),
                inReplyTo = headers.inReplyTo,
                references = headers.references
            )
            store(account, replying, "\n\n" + quotes.reply(source))
        }
    }

    /** An empty draft of [kind] for [account], with its signature settings but no signature yet. */
    private fun blank(account: AccountEntity, kind: DraftKind): Draft {
        val settings = account.signatureSettings()
        val now = clock.instant()
        return Draft(
            id = 0,
            key = DraftMessageIds.newKey(),
            accountId = account.id,
            kind = kind,
            state = DraftState.EDITING,
            to = emptyList(),
            cc = emptyList(),
            bcc = emptyList(),
            subject = "",
            body = "",
            inReplyTo = null,
            references = emptyList(),
            source = null,
            signatureText = settings.takeIf { it.block != null }?.text,
            signatureBeforeQuote = settings.beforeQuote,
            serverMessageId = null,
            dirty = true,
            revision = 0,
            outgoingMessageId = null,
            smtpAcceptedAt = null,
            createdAt = now,
            updatedAt = now
        )
    }

    /** Puts [text] and the signature of [account] into [draft] and stores it. */
    private suspend fun store(account: AccountEntity, draft: Draft, text: String): Draft {
        val body = SignatureEditor.apply(text, draft.composeKind, account.signatureSettings())
        val stored = draft.copy(body = body)
        return stored.copy(id = drafts.insert(stored.toEntity()))
    }

    /** The draft [id] for the composer, or null if it is gone. */
    suspend fun open(id: Long): Draft? = repository.get(id)

    fun observe(id: Long): Flow<Draft?> = repository.observe(id)

    /**
     * Saves the edits of the composer as they come, debounced: a burst of keystrokes is one save,
     * [debounceMillis] after the last change. Each local save also asks [DraftServerSync] to
     * refresh the server copy, which that class throttles itself. The caller owns the debounce
     * and the lifetime: collect this in the composer's scope (it ends when the scope is
     * cancelled or [edits] completes) and give it a flow of the fields as the user changes them.
     */
    @OptIn(FlowPreview::class)
    suspend fun autosave(
        id: Long,
        edits: Flow<DraftEdit>,
        debounceMillis: Long = AUTOSAVE_DEBOUNCE_MILLIS
    ) {
        edits.debounce(debounceMillis).collectLatest {
            if (save(id, it) == DraftChange.SAVED) serverSync.request(id)
        }
    }

    /** Stores the edits; see [DraftRepository.save]. */
    suspend fun save(id: Long, edit: DraftEdit): DraftChange = repository.save(id, edit)

    /**
     * Changes the account the message is sent from (RF-08): the signature block of the old
     * account is replaced by the one of the new account, in the same place, and nothing else in
     * the text is touched. A copy of the draft on the old account's server is deleted; the next
     * server save puts it on the new one.
     */
    suspend fun changeSender(id: Long, accountId: Long): DraftChange = withContext(io) {
        val account = accounts.get(accountId) ?: return@withContext DraftChange.MISSING
        val current = repository.get(id) ?: return@withContext DraftChange.MISSING
        if (current.accountId == accountId) return@withContext DraftChange.SAVED
        if (current.state != DraftState.EDITING) return@withContext DraftChange.NOT_EDITABLE
        serverSync.forgetServerCopy(current)
        val next = account.signatureSettings()
        repository.change(id) { draft ->
            draft.copy(
                accountId = accountId,
                body = SignatureEditor.replace(
                    draft.body,
                    draft.signatureSettings,
                    next,
                    draft.composeKind
                ),
                signatureText = next.takeIf { it.block != null }?.text,
                signatureBeforeQuote = next.beforeQuote,
                serverMessageId = null
            )
        }
    }

    /**
     * Throws the draft away: its row, its attachment files, any waiting server save and (through
     * the operation queue) its copy on the server. With [onlyEditing], a message that already
     * went to the outbox is left alone (it is the send queue's business, see `OutboxActions`):
     * the list swipe uses that. Returns whether a draft was thrown away.
     */
    suspend fun discard(id: Long, onlyEditing: Boolean = false): Boolean {
        val draft = repository.get(id)?.takeIf { !onlyEditing || it.state == DraftState.EDITING }
            ?: return false
        serverSync.forgetServerCopy(draft)
        repository.delete(id)
        return true
    }

    companion object {
        /** The pause after the last change before the composer's text is stored. */
        const val AUTOSAVE_DEBOUNCE_MILLIS = 800L
    }
}

private fun AccountEntity.signatureSettings() =
    SignatureSettings(signature, signatureEnabled, signatureBeforeQuote)
