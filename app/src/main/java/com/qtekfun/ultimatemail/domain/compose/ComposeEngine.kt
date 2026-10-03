// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.data.local.dao.AccountDao
import com.qtekfun.ultimatemail.data.local.dao.DraftDao
import com.qtekfun.ultimatemail.data.local.dao.MessageDao
import com.qtekfun.ultimatemail.data.local.entity.AccountEntity
import com.qtekfun.ultimatemail.data.local.entity.MessageEntity
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
@Suppress("LongParameterList")
class ComposeEngine @Inject constructor(
    private val drafts: DraftDao,
    private val accounts: AccountDao,
    private val messages: MessageDao,
    private val repository: DraftRepository,
    private val serverSync: DraftServerSync,
    private val templates: QuoteTemplatesProvider,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    private val quotes = QuoteBuilder(templates)

    /** A new, empty message from [accountId] with the account signature; null if no such account. */
    suspend fun newMessage(accountId: Long, to: List<MailAddress> = emptyList()): Draft? =
        withContext(io) {
            val account = accounts.get(accountId) ?: return@withContext null
            create(
                account,
                DraftKind.NEW,
                source = null,
                to = to,
                cc = emptyList(),
                subject = "",
                text = ""
            )
        }

    /** Starts a reply, reply all or forward of the message in [request]; null if it is gone. */
    suspend fun start(request: ComposeRequest): Draft? = withContext(io) {
        val account = accounts.get(request.accountId) ?: return@withContext null
        val row = messages.getById(request.messageId) ?: return@withContext null
        val source = row.toSource()
        val kind = when (request.mode) {
            ComposeMode.REPLY -> DraftKind.REPLY
            ComposeMode.REPLY_ALL -> DraftKind.REPLY_ALL
            ComposeMode.FORWARD -> DraftKind.FORWARD
        }
        val forward = kind == DraftKind.FORWARD
        val recipients = ReplyRecipientsRule.of(kind, source, setOf(account.email))
        val headers = if (forward) null else ReferenceChain.forReply(source)
        create(
            account = account,
            kind = kind,
            source = DraftSource(source.accountId, source.folderPath, source.uid, source.messageId),
            to = recipients.to,
            cc = recipients.cc,
            subject = if (forward) {
                ComposeSubject.forward(
                    source.subject
                )
            } else {
                ComposeSubject.reply(source.subject)
            },
            text = "\n\n" + if (forward) quotes.forward(source) else quotes.reply(source),
            inReplyTo = headers?.inReplyTo,
            references = headers?.references.orEmpty()
        )
    }

    private suspend fun create(
        account: AccountEntity,
        kind: DraftKind,
        source: DraftSource?,
        to: List<MailAddress>,
        cc: List<MailAddress>,
        subject: String,
        text: String,
        inReplyTo: String? = null,
        references: List<String> = emptyList()
    ): Draft {
        val settings = signatureOf(account)
        val now = clock.instant()
        val draft = Draft(
            id = 0,
            key = DraftMessageIds.newKey(),
            accountId = account.id,
            kind = kind,
            state = DraftState.EDITING,
            to = to,
            cc = cc,
            bcc = emptyList(),
            subject = subject,
            body = "",
            inReplyTo = inReplyTo,
            references = references,
            source = source,
            signatureText = settings.takeIf { it.hasBlock() }?.text,
            signatureBeforeQuote = settings.beforeQuote,
            serverMessageId = null,
            dirty = true,
            revision = 0,
            outgoingMessageId = null,
            smtpAcceptedAt = null,
            createdAt = now,
            updatedAt = now
        )
        val body = SignatureEditor.apply(text, draft.composeKind, settings)
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
        val next = signatureOf(account)
        repository.change(id) {
            it.copy(
                accountId = accountId,
                body = SignatureEditor.replace(it.body, it.signatureSettings, next, it.composeKind),
                signatureText = next.takeIf { settings -> settings.hasBlock() }?.text,
                signatureBeforeQuote = next.beforeQuote,
                serverMessageId = null
            )
        }
    }

    /** Throws the draft away: its row, its attachment files and any waiting server save. */
    suspend fun discard(id: Long) {
        val draft = repository.get(id) ?: return
        serverSync.forgetServerCopy(draft)
        repository.delete(id)
    }

    companion object {
        /** The pause after the last change before the composer's text is stored. */
        const val AUTOSAVE_DEBOUNCE_MILLIS = 800L
    }

    private fun signatureOf(account: AccountEntity) =
        SignatureSettings(account.signature, account.signatureEnabled, account.signatureBeforeQuote)

    private fun SignatureSettings.hasBlock() = enabled && text.isNotBlank()

    private fun MessageEntity.toSource() = ComposeSource(
        accountId = accountId,
        folderPath = folderPath,
        uid = uid,
        messageId = messageId,
        from = senderAddress.takeIf { it.isNotBlank() }
            ?.let { MailAddress(it, senderName.ifBlank { null }) },
        to = toAddresses.map { MailAddress(it) },
        cc = ccAddresses.map { MailAddress(it) },
        subject = subject,
        sentAt = sentAt,
        inReplyTo = inReplyTo,
        references = referenceIds,
        bodyText = bodyText ?: bodyHtml?.let(HtmlText::toPlain)
    )
}
