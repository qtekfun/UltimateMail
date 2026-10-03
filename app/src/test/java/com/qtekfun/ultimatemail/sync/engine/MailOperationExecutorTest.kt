// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.entity.PendingOperationEntity
import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.data.local.model.OperationType
import com.qtekfun.ultimatemail.domain.account.AccountCredentials
import com.qtekfun.ultimatemail.domain.account.OAuthRefreshResult
import com.qtekfun.ultimatemail.domain.account.OAuthTokens
import com.qtekfun.ultimatemail.domain.mail.GmailMetadata
import com.qtekfun.ultimatemail.domain.mail.MailAddress
import com.qtekfun.ultimatemail.domain.mail.MailFolderRole
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MessageFlags
import com.qtekfun.ultimatemail.domain.mail.OutgoingMessage
import com.qtekfun.ultimatemail.domain.mail.RejectionKind
import com.qtekfun.ultimatemail.sync.conflict.SyncNotice
import com.qtekfun.ultimatemail.sync.queue.FlagChange
import com.qtekfun.ultimatemail.sync.queue.OperationOutcome
import java.time.Instant
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MailOperationExecutorTest {
    private var harness: EngineHarness? = null

    @AfterEach
    fun close() {
        harness?.close()
    }

    private suspend fun TestScope.start(
        authType: AuthType = AuthType.PASSWORD,
        setup: EngineHarness.() -> Unit = {}
    ): EngineHarness {
        val h = EngineHarness(this, authType)
        harness = h
        h.server.folder("INBOX", MailFolderRole.INBOX)
        h.server.folder("Archive", MailFolderRole.ARCHIVE)
        h.server.folder("Sent", MailFolderRole.SENT)
        h.setup()
        h.addAccount()
        h.engine.sync(h.accountId)
        h.server.log.clear()
        return h
    }

    private suspend fun EngineHarness.op(
        type: OperationType,
        payload: String,
        uid: Long = 1,
        folder: String = "INBOX"
    ): PendingOperationEntity {
        val id = operations.enqueue(
            PendingOperationEntity(
                accountId = accountId,
                type = type,
                folderPath = folder,
                uid = uid,
                payload = payload,
                createdAt = Instant.EPOCH
            )
        )
        return operations.get(id)!!
    }

    private val message = OutgoingMessage(
        from = MailAddress("ana@example.test"),
        to = listOf(MailAddress("bob@example.test")),
        subject = "Hi",
        text = "Hello"
    )

    private fun payload(withId: String = "<out@x>") = OutgoingPayload.encode(message.withId(withId))

    private fun OutgoingMessage.withId(id: String) = OutgoingMessage(
        from = from,
        to = to,
        subject = subject,
        text = text,
        messageId = id
    )

    private suspend fun EngineHarness.noticesSoFar(): List<SyncNotice> {
        val seen = mutableListOf<SyncNotice>()
        withTimeoutOrNull(1) { notices.notices.collect { seen += it } }
        return seen
    }

    // --- flags ---

    @Test
    fun `flags are set to the absolute values of the change and the pending mark is cleared`() =
        runTest {
            val h = start { server.deliver("INBOX", flags = MessageFlags(flagged = true)) }
            h.marker.mark(h.accountId, "INBOX", 1)

            val outcome = h.executor.execute(
                h.op(OperationType.SET_FLAGS, FlagChange(seen = true, flagged = false).encode())
            )

            assertEquals(OperationOutcome.Done, outcome)
            assertEquals(
                MessageFlags(seen = true),
                h.server.folder("INBOX").messages.getValue(1).flags
            )
            assertFalse(h.messages.get(h.accountId, "INBOX", 1)!!.pendingSync)
        }

    @Test
    fun `an untouched flag is not sent`() = runTest {
        val h = start { server.deliver("INBOX") }

        h.executor.execute(h.op(OperationType.SET_FLAGS, FlagChange(seen = true).encode()))

        assertEquals(1, h.server.logged("setFlags").size)
    }

    @Test
    fun `the pending mark stays while another operation waits for the message`() = runTest {
        val h = start { server.deliver("INBOX") }
        h.marker.mark(h.accountId, "INBOX", 1)
        val first = h.op(OperationType.SET_FLAGS, FlagChange(seen = true).encode())
        h.op(OperationType.ADD_LABEL, "Work")

        h.executor.execute(first)

        assertTrue(h.messages.get(h.accountId, "INBOX", 1)!!.pendingSync)
    }

    @Test
    fun `flags of a reset folder wait for the pull`() = runTest {
        val h = start { server.deliver("INBOX") }
        h.server.folder("INBOX").uidValidity = 99

        val outcome = h.executor.execute(
            h.op(OperationType.SET_FLAGS, FlagChange(seen = true).encode())
        )

        assertEquals(OperationOutcome.RetryLater("folder_reset"), outcome)
        assertTrue(h.server.logged("setFlags").isEmpty())
    }

    @Test
    fun `a failing status check of the folder is mapped like any failure`() = runTest {
        val h = start { server.deliver("INBOX") }
        h.server.failure = { name -> MailResult.Timeout.takeIf { name.startsWith("folderStatus") } }

        val outcome = h.executor.execute(
            h.op(OperationType.SET_FLAGS, FlagChange(seen = true).encode())
        )

        assertEquals(OperationOutcome.RetryLater("timeout"), outcome)
    }

    @Test
    fun `a malformed flag payload is rejected`() = runTest {
        val h = start { server.deliver("INBOX") }

        val outcome = h.executor.execute(h.op(OperationType.SET_FLAGS, "garbage"))

        assertEquals(OperationOutcome.Rejected("bad_payload"), outcome)
    }

    @Test
    fun `an operation on a message without a server uid waits`() = runTest {
        val h = start { server.deliver("INBOX") }

        val outcome = h.executor.execute(
            h.op(OperationType.SET_FLAGS, FlagChange(seen = true).encode(), uid = -5)
        )

        assertEquals(OperationOutcome.RetryLater("not_synced"), outcome)
        assertTrue(h.server.log.isEmpty())
    }

    // --- move, labels, delete ---

    @Test
    fun `a move puts the message in the destination folder`() = runTest {
        val h = start { server.deliver("INBOX") }

        val outcome = h.executor.execute(h.op(OperationType.MOVE, "Archive"))

        assertEquals(OperationOutcome.Done, outcome)
        assertTrue(h.server.folder("INBOX").messages.isEmpty())
        assertEquals(1, h.server.folder("Archive").messages.size)
    }

    @Test
    fun `a move the server did removes the row of the old folder`() = runTest {
        val h = start { server.deliver("INBOX") }
        assertTrue(h.messages.get(h.accountId, "INBOX", 1) != null)

        h.executor.execute(h.op(OperationType.MOVE, "Archive"))

        assertEquals(null, h.messages.get(h.accountId, "INBOX", 1))
    }

    @Test
    fun `a move that failed keeps the row of the old folder`() = runTest {
        val h = start { server.deliver("INBOX") }
        h.server.failure = { name -> MailResult.Timeout.takeIf { name.startsWith("move") } }

        val outcome = h.executor.execute(h.op(OperationType.MOVE, "Archive"))

        assertEquals(OperationOutcome.RetryLater("timeout"), outcome)
        assertTrue(h.messages.get(h.accountId, "INBOX", 1) != null)
    }

    @Test
    fun `repeating a move whose answer was lost does nothing and tells nobody`() = runTest {
        val h = start { server.deliver("INBOX", messageId = "<one@x>") }
        val move = h.op(OperationType.MOVE, "Archive")
        val row = h.messages.get(h.accountId, "INBOX", 1)!!
        assertEquals(OperationOutcome.Done, h.executor.execute(move))
        // The answer was lost, so in real life the queue never heard Done and the row is still
        // there; bring it back to repeat the attempt.
        h.messages.upsert(listOf(row.copy(id = 0)))
        h.server.log.clear()

        val again = h.executor.execute(move)

        assertEquals(OperationOutcome.Done, again)
        assertTrue(h.server.logged("move").isEmpty(), "not moved a second time")
        assertTrue(h.noticesSoFar().isEmpty())
    }

    @Test
    fun `a move of a message that vanished is dropped with a notice`() = runTest {
        val h = start { server.deliver("INBOX", messageId = "<one@x>") }
        h.server.expunge("INBOX", 1)
        val move = h.op(OperationType.MOVE, "Archive")

        val outcome = h.executor.execute(move)

        assertEquals(OperationOutcome.Done, outcome)
        assertEquals(
            listOf<SyncNotice>(SyncNotice.MessageVanished(h.accountId, "INBOX", move.id)),
            h.noticesSoFar()
        )
    }

    @Test
    fun `a UID that now belongs to another message is not touched`() = runTest {
        val h = start { server.deliver("INBOX", messageId = "<one@x>") }
        val folder = h.server.folder("INBOX")
        folder.messages[1] = folder.messages.getValue(1).copy(messageId = "<other@x>")

        val outcome = h.executor.execute(h.op(OperationType.DELETE, ""))

        assertEquals(OperationOutcome.Done, outcome)
        assertTrue(h.server.logged("delete").isEmpty(), "the other message stays")
    }

    @Test
    fun `labels are added and removed and a label already in place is not sent again`() = runTest {
        val h = start {
            server.deliver("INBOX", gmail = GmailMetadata(1, 10, listOf("Work")))
        }

        val already = h.executor.execute(h.op(OperationType.ADD_LABEL, "Work"))
        val add = h.executor.execute(h.op(OperationType.ADD_LABEL, "Later"))
        val remove = h.executor.execute(h.op(OperationType.REMOVE_LABEL, "Work"))
        val absent = h.executor.execute(h.op(OperationType.REMOVE_LABEL, "Nothing"))

        assertEquals(listOf(OperationOutcome.Done), listOf(already, add, remove, absent).distinct())
        assertEquals(
            listOf("addLabels INBOX [1] [Later]", "removeLabels INBOX [1] [Work]"),
            h.server.logged("addLabels") + h.server.logged("removeLabels")
        )
    }

    @Test
    fun `delete removes the message and deleting a vanished one is silent`() = runTest {
        val h = start { server.deliver("INBOX") }
        val delete = h.op(OperationType.DELETE, "")

        assertEquals(OperationOutcome.Done, h.executor.execute(delete))
        assertTrue(h.server.folder("INBOX").messages.isEmpty())
        assertEquals(OperationOutcome.Done, h.executor.execute(delete))
        assertTrue(h.noticesSoFar().isEmpty())
    }

    @Test
    fun `a move to a folder that does not exist is rejected`() = runTest {
        val h = start { server.deliver("INBOX") }

        val outcome = h.executor.execute(h.op(OperationType.MOVE, "Nowhere"))

        assertEquals(OperationOutcome.Rejected("not_found"), outcome)
    }

    @Test
    fun `a failing lookup of the message is mapped like any failure`() = runTest {
        val h = start { server.deliver("INBOX") }
        h.server.failure = { name -> MailResult.Timeout.takeIf { name.startsWith("fetchHeaders") } }

        val outcome = h.executor.execute(h.op(OperationType.DELETE, ""))

        assertEquals(OperationOutcome.RetryLater("timeout"), outcome)
    }

    @Test
    fun `a message missing from the source is looked for at the destination even if that fails`() =
        runTest {
            val h = start { server.deliver("INBOX", messageId = "<one@x>") }
            h.server.expunge("INBOX", 1)
            h.server.failure =
                { name -> MailResult.Timeout.takeIf { name.startsWith("folderStatus Archive") } }
            val move = h.op(OperationType.MOVE, "Archive")

            val outcome = h.executor.execute(move)

            assertEquals(OperationOutcome.Done, outcome)
            assertEquals(1, h.noticesSoFar().size)
        }

    @Test
    fun `an empty destination folder has nothing to find`() = runTest {
        val h = start { server.deliver("INBOX", messageId = "<one@x>") }
        h.server.expunge("INBOX", 1)

        h.executor.execute(h.op(OperationType.MOVE, "Archive"))

        assertEquals(1, h.noticesSoFar().size)
    }

    // --- failure classes ---

    @Test
    fun `every failure class maps to its outcome`() = runTest {
        val cases = listOf(
            MailResult.NetworkUnavailable to OperationOutcome.RetryLater("network"),
            MailResult.Timeout to OperationOutcome.RetryLater("timeout"),
            MailResult.AuthenticationFailed to OperationOutcome.RetryLater("auth_required"),
            MailResult.CertificateRejected to OperationOutcome.Rejected("certificate"),
            MailResult.ServerRejected(RejectionKind.NO, permanent = true) to
                OperationOutcome.Rejected("server_rejected"),
            MailResult.ServerRejected(RejectionKind.NO, permanent = false) to
                OperationOutcome.RetryLater("server_busy"),
            MailResult.NotFound to OperationOutcome.Rejected("not_found"),
            MailResult.Unsupported("x") to OperationOutcome.Rejected("unsupported"),
            MailResult.Protocol to OperationOutcome.RetryLater("unexpected"),
            MailResult.Unknown to OperationOutcome.RetryLater("unexpected")
        )
        val h = start { server.deliver("INBOX") }
        for ((failure, expected) in cases) {
            h.server.failure = { name -> failure.takeIf { name.startsWith("setFlags") } }

            val outcome = h.executor.execute(
                h.op(OperationType.SET_FLAGS, FlagChange(seen = true).encode())
            )

            assertEquals(expected, outcome, "for $failure")
        }
    }

    @Test
    fun `a login that no longer works retries later and the account asks for a new sign in`() =
        runTest {
            val h = start { server.deliver("INBOX") }
            h.connector.failure = MailResult.AuthenticationFailed

            val outcome = h.executor.execute(
                h.op(OperationType.SET_FLAGS, FlagChange(seen = true).encode())
            )

            assertEquals(OperationOutcome.RetryLater("auth_required"), outcome)
            assertEquals(AccountSyncState.ReauthenticationNeeded, h.status.get(h.accountId))
        }

    @Test
    fun `an unreachable server retries later`() = runTest {
        val h = start { server.deliver("INBOX") }
        h.connector.failure = MailResult.NetworkUnavailable

        val outcome = h.executor.execute(h.op(OperationType.DELETE, ""))

        assertEquals(OperationOutcome.RetryLater("network"), outcome)
    }

    @Test
    fun `an operation of an account that is gone is rejected`() = runTest {
        val h = start { server.deliver("INBOX") }
        val operation = h.op(OperationType.DELETE, "")
        h.db.accountDao().delete(h.accountId)

        assertEquals(OperationOutcome.Rejected("no_account"), h.executor.execute(operation))
        assertEquals(
            OperationOutcome.Rejected("no_account"),
            h.executor.execute(operation.copy(type = OperationType.SEND))
        )
    }

    // --- drafts ---

    @Test
    fun `a draft is stored once even if the operation runs twice`() = runTest {
        val h = start()
        val save = h.op(OperationType.SAVE_DRAFT, payload(), uid = 0, folder = "Archive")

        assertEquals(OperationOutcome.Done, h.executor.execute(save))
        assertEquals(OperationOutcome.Done, h.executor.execute(save))

        assertEquals(1, h.server.appendedDrafts.size)
        assertEquals("<out@x>", h.server.appendedDrafts.single().messageId)
    }

    @Test
    fun `a draft that cannot be stored is mapped, and a broken payload is rejected`() = runTest {
        val h = start()
        h.server.failure = { name -> MailResult.Timeout.takeIf { name.startsWith("appendDraft") } }

        val failed = h.executor.execute(
            h.op(OperationType.SAVE_DRAFT, payload(), uid = 0, folder = "Archive")
        )
        val broken = h.executor.execute(
            h.op(OperationType.SAVE_DRAFT, "garbage", uid = 0, folder = "Archive")
        )

        assertEquals(OperationOutcome.RetryLater("timeout"), failed)
        assertEquals(OperationOutcome.Rejected("bad_payload"), broken)
    }

    @Test
    fun `a failing look into the drafts folder is mapped`() = runTest {
        val h = start()
        h.server.failure = { name -> MailResult.Timeout.takeIf { name.startsWith("folderStatus") } }

        val outcome = h.executor.execute(
            h.op(OperationType.SAVE_DRAFT, payload(), uid = 0, folder = "Archive")
        )

        assertEquals(OperationOutcome.RetryLater("timeout"), outcome)
    }

    @Test
    fun `a drafts folder that does not exist yet is not an obstacle to look into`() = runTest {
        val h = start()

        val outcome = h.executor.execute(
            h.op(OperationType.SAVE_DRAFT, payload(), uid = 0, folder = "Drafts")
        )

        assertEquals(OperationOutcome.Rejected("not_found"), outcome)
    }

    // --- send ---

    @Test
    fun `a message is sent once and the operation is done`() = runTest {
        val h = start()

        val outcome = h.executor.execute(
            h.op(OperationType.SEND, payload(), uid = 0, folder = "Drafts")
        )

        assertEquals(OperationOutcome.Done, outcome)
        assertEquals(listOf("<out@x>"), h.sender.sent.map { it.messageId })
    }

    @Test
    fun `a timeout is retried later and the retry does not send again once Sent has the message`() =
        runTest {
            val h = start()
            h.sender.onSend = { MailResult.Timeout }
            val send = h.op(OperationType.SEND, payload(), uid = 0, folder = "Drafts")

            val first = h.executor.execute(send)

            assertEquals(OperationOutcome.RetryLater("timeout"), first)
            // The server had taken it after all: it shows up in Sent.
            h.server.deliver("Sent", messageId = "<out@x>")
            h.sender.onSend = { error("must not send again") }

            val second = h.executor.execute(send)

            assertEquals(OperationOutcome.Done, second)
            assertEquals(1, h.sender.sent.size)
        }

    @Test
    fun `an ambiguous failure is settled at once when Sent already has the message`() = runTest {
        val h = start()
        h.sender.onSend = {
            h.server.deliver("Sent", messageId = "<out@x>")
            MailResult.NetworkUnavailable
        }

        val outcome = h.executor.execute(
            h.op(OperationType.SEND, payload(), uid = 0, folder = "Drafts")
        )

        assertEquals(OperationOutcome.Done, outcome)
    }

    @Test
    fun `when Sent cannot be read the message waits instead of being sent blind`() = runTest {
        val h = start()
        h.server.failure =
            { name -> MailResult.Timeout.takeIf { name.startsWith("folderStatus Sent") } }

        val outcome = h.executor.execute(
            h.op(OperationType.SEND, payload(), uid = 0, folder = "Drafts")
        )

        assertEquals(OperationOutcome.RetryLater("confirm_sent"), outcome)
        assertTrue(h.sender.sent.isEmpty())
    }

    @Test
    fun `after an ambiguous failure with Sent unreadable the message waits`() = runTest {
        val h = start()
        h.sender.onSend = {
            h.server.failure =
                { name -> MailResult.Timeout.takeIf { name.startsWith("folderStatus Sent") } }
            MailResult.Timeout
        }

        val outcome = h.executor.execute(
            h.op(OperationType.SEND, payload(), uid = 0, folder = "Drafts")
        )

        assertEquals(OperationOutcome.RetryLater("confirm_sent"), outcome)
    }

    @Test
    fun `a missing Sent folder does not block sending`() = runTest {
        val h = start()
        h.server.folders.remove("Sent")
        h.engine.sync(h.accountId)

        val outcome = h.executor.execute(
            h.op(OperationType.SEND, payload(), uid = 0, folder = "Drafts")
        )

        assertEquals(OperationOutcome.Done, outcome)
    }

    @Test
    fun `a Sent folder that disappeared since the last sync does not block sending`() = runTest {
        val h = start()
        h.server.folders.remove("Sent")

        val outcome = h.executor.execute(
            h.op(OperationType.SEND, payload(), uid = 0, folder = "Drafts")
        )

        assertEquals(OperationOutcome.Done, outcome)
    }

    @Test
    fun `send failures map to outcomes`() = runTest {
        val cases = listOf(
            MailResult.ServerRejected(RejectionKind.SMTP, permanent = true) to
                OperationOutcome.Rejected("server_rejected"),
            MailResult.ServerRejected(RejectionKind.SMTP, permanent = false) to
                OperationOutcome.RetryLater("server_busy"),
            MailResult.AuthenticationFailed to OperationOutcome.RetryLater("auth_required"),
            MailResult.CertificateRejected to OperationOutcome.Rejected("certificate"),
            MailResult.Timeout to OperationOutcome.RetryLater("timeout"),
            MailResult.NetworkUnavailable to OperationOutcome.RetryLater("network"),
            MailResult.Unknown to OperationOutcome.RetryLater("unexpected"),
            MailResult.Protocol to OperationOutcome.RetryLater("unexpected")
        )
        val h = start()
        for ((failure, expected) in cases) {
            h.sender.onSend = { failure }

            val outcome = h.executor.execute(
                h.op(OperationType.SEND, payload(), uid = 0, folder = "Drafts")
            )

            assertEquals(expected, outcome, "for $failure")
        }
    }

    @Test
    fun `a broken send payload is rejected`() = runTest {
        val h = start()

        val outcome = h.executor.execute(
            h.op(OperationType.SEND, "garbage", uid = 0, folder = "Drafts")
        )

        assertEquals(OperationOutcome.Rejected("bad_payload"), outcome)
    }

    @Test
    fun `sending without usable credentials asks for a new sign in`() = runTest {
        val h = start()
        val send = h.op(OperationType.SEND, payload(), uid = 0, folder = "Drafts")

        // The IMAP session is open for the Sent check; the credentials are gone for SMTP.
        val leased = h.sessions.withSession(h.accountId) {
            h.vault.saved.remove(h.accountId)
            h.executor.execute(send)
        }

        assertEquals(Leased.Ok(OperationOutcome.RetryLater("auth_required")), leased)
        assertEquals(AccountSyncState.ReauthenticationNeeded, h.status.get(h.accountId))
    }

    @Test
    fun `sending while a new OAuth token cannot be had retries later`() = runTest {
        val h = start(AuthType.OAUTH_GOOGLE)
        val valid =
            AccountCredentials(
                oauth = OAuthTokens("good", "refresh", Instant.ofEpochSecond(2_000_000_000))
            )
        h.vault.save(h.accountId, valid)
        h.engine.sync(h.accountId, userInitiated = true)
        val send = h.op(OperationType.SEND, payload(), uid = 0, folder = "Drafts")

        val leased = h.sessions.withSession(h.accountId) {
            h.vault.save(
                h.accountId,
                AccountCredentials(oauth = OAuthTokens("old", "refresh", Instant.EPOCH))
            )
            h.oauth.result = OAuthRefreshResult.TemporaryFailure
            h.executor.execute(send)
        }

        assertEquals(Leased.Ok(OperationOutcome.RetryLater("network")), leased)
    }
}
