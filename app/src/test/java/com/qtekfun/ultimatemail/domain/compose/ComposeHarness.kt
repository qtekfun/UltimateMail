// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.data.local.entity.AccountEntity
import com.qtekfun.ultimatemail.data.local.folder
import com.qtekfun.ultimatemail.data.local.message
import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.domain.conversation.RecordingScheduler
import com.qtekfun.ultimatemail.domain.mail.MailAddress
import com.qtekfun.ultimatemail.domain.mail.MailFolderRole
import com.qtekfun.ultimatemail.sync.engine.DownloadAttachment
import com.qtekfun.ultimatemail.sync.engine.EngineHarness
import com.qtekfun.ultimatemail.sync.engine.LoadMessageBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope

/** The composer's classes wired to an in-memory database, the fake server and in-memory files. */
class ComposeHarness(scope: TestScope, authType: AuthType = AuthType.PASSWORD) {
    val engineHarness = EngineHarness(scope, authType)
    val db get() = engineHarness.db
    val clock get() = engineHarness.clock
    val server get() = engineHarness.server
    val files get() = engineHarness.outboxFiles
    val scheduler = RecordingScheduler()
    val attachmentSource = FakeAttachmentSource()
    var quotes = ENGLISH_QUOTES

    private val io = Dispatchers.Unconfined
    private val drafts get() = db.draftDao()
    private val pending get() = db.pendingOperationDao()

    val repository = DraftRepository(drafts, files, clock, io)
    val serverSync = DraftServerSync(
        db,
        engineHarness.queue,
        engineHarness.marker,
        scheduler,
        clock,
        io
    )
    val engine = ComposeEngine(db, repository, serverSync, { quotes }, clock, io)
    val attachments = DraftAttachments(drafts, attachmentSource, files, io)
    val download = DownloadAttachment(
        db.attachmentDao(),
        db.messageDao(),
        engineHarness.sessions,
        engineHarness.storage
    )
    val forwardAttachments =
        ForwardAttachments(db.attachmentDao(), download, engineHarness.storage, attachments, io)
    val serverDrafts = ServerDraftImport(
        db,
        LoadMessageBody(db.messageDao(), engineHarness.sessions, engineHarness.bodyStore),
        forwardAttachments,
        clock,
        io
    )
    val opener = ComposeOpener(engine, attachments, forwardAttachments, serverDrafts)
    val send = SendDraft(db, engineHarness.queue, files, scheduler, clock, io)
    val actions = OutboxActions(db, engineHarness.queue, repository, scheduler, clock, io)
    val state = ComposeState(drafts, db.messageDao(), pending)
    val suggestions = RecipientSuggestions(db.messageDao(), clock, io)

    var accountId = 0L
        private set

    /** Adds the account with Sent, Drafts and Inbox on the server and in Room. */
    suspend fun addAccount(entity: AccountEntity? = null): Long {
        accountId =
            if (entity == null) engineHarness.addAccount() else engineHarness.addAccount(entity)
        server.folder("INBOX", MailFolderRole.INBOX)
        server.folder("Sent", MailFolderRole.SENT)
        server.folder("Drafts", MailFolderRole.DRAFTS)
        db.folderDao().upsert(
            listOf(
                folder(accountId, "INBOX"),
                folder(accountId, "Sent", FolderRole.SENT),
                folder(accountId, "Drafts", FolderRole.DRAFTS)
            )
        )
        return accountId
    }

    /** A received message in Room, to reply to; its row id is returned. */
    suspend fun receive(
        uid: Long = 1,
        subject: String = "Hello",
        body: String? = "Hi Ana,\nsee you.",
        to: List<String> = listOf("ana@example.test"),
        cc: List<String> = emptyList(),
        account: Long = accountId
    ): Long {
        val row = message(account, uid, subject = subject, bodyText = body)
            .copy(toAddresses = to, ccAddresses = cc, referenceIds = listOf("<r0@example.test>"))
        db.messageDao().upsert(listOf(row))
        return checkNotNull(db.messageDao().get(account, "INBOX", uid)).id
    }

    suspend fun writeTo(vararg recipients: String): Draft {
        val draft = checkNotNull(engine.newMessage(accountId))
        engine.save(
            draft.id,
            DraftEdit(
                recipients.map {
                    MailAddress(it)
                },
                emptyList(),
                emptyList(),
                "Subject",
                "Text"
            )
        )
        return checkNotNull(repository.get(draft.id))
    }

    fun close() = engineHarness.close()
}
