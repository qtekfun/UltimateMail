// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.model.AttachmentState
import com.qtekfun.ultimatemail.domain.mail.AttachmentInfo
import com.qtekfun.ultimatemail.domain.mail.MailFolderRole
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MessageBody
import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BodyDownloaderTest {
    private var harness: EngineHarness? = null

    @AfterEach
    fun close() {
        harness?.close()
    }

    private suspend fun TestScope.start(downloads: Boolean = true): EngineHarness {
        val h = EngineHarness(this)
        harness = h
        h.server.folder("INBOX", MailFolderRole.INBOX)
        h.addAccount()
        h.offlineDownloads.setEnabled(h.accountId, downloads)
        return h
    }

    /** Delivers message [index], [index] hours after ten days ago, with a body unless null. */
    private fun EngineHarness.deliver(
        index: Int,
        folder: String = "INBOX",
        size: Long = 100,
        body: MessageBody? = MessageBody("text $index", "<p>$index</p>", emptyList())
    ): Long {
        val sentAt = clock.now.minus(Duration.ofDays(10)).plusSeconds(index * 3600L)
        val uid = server.deliver(folder, sentAt = sentAt, size = size)
        if (body != null) server.folder(folder).bodies[uid] = body
        return uid
    }

    private suspend fun EngineHarness.bodyOf(uid: Long, folder: String = "INBOX") =
        messages.get(accountId, folder, uid)!!.bodyText

    private fun EngineHarness.fetches() = server.logged("fetchBody")

    /** Syncs the headers only, then turns the downloads on, to drive the downloader directly. */
    private suspend fun EngineHarness.headersOnly() {
        engine.sync(accountId)
        offlineDownloads.setEnabled(accountId, true)
    }

    @Test
    fun `sync downloads the bodies of every message newest first`() = runTest {
        val h = start()
        (1..3).forEach { h.deliver(it) }

        h.engine.sync(h.accountId)

        assertEquals(
            listOf("fetchBody INBOX 3", "fetchBody INBOX 2", "fetchBody INBOX 1"),
            h.fetches()
        )
        val stored = h.messages.get(h.accountId, "INBOX", 2)!!
        assertEquals("text 2", stored.bodyText)
        assertEquals("<p>2</p>", stored.bodyHtml)
    }

    @Test
    fun `messages outside the offline window are not downloaded`() = runTest {
        val h = start()
        val recent = h.deliver(1)
        h.engine.sync(h.accountId)
        h.server.log.clear()
        // A row kept past the window by a pending change: it is old, so it is not in the window.
        val old = h.messages.get(h.accountId, "INBOX", recent)!!
        h.messages.upsert(
            listOf(
                old.copy(
                    id = 0,
                    uid = 50,
                    bodyText = null,
                    bodyHtml = null,
                    pendingSync = true,
                    sentAt = h.clock.now.minus(Duration.ofDays(200))
                )
            )
        )

        h.engine.sync(h.accountId)

        assertEquals(emptyList<String>(), h.fetches())
    }

    @Test
    fun `the whole mailbox window downloads old messages too`() = runTest {
        val h = start()
        val dao = h.db.accountDao()
        dao.update(dao.get(h.accountId)!!.copy(offlineWindowDays = null))
        h.server.deliver("INBOX", sentAt = h.clock.now.minus(Duration.ofDays(2000)))
        h.server.folder("INBOX").bodies[1] = MessageBody("ancient", null, emptyList())

        h.engine.sync(h.accountId)

        assertEquals("ancient", h.bodyOf(1))
    }

    @Test
    fun `a message above the size cap is left to be fetched on demand`() = runTest {
        val h = start()
        h.deliver(1, size = BodyDownloader.MAX_MESSAGE_BYTES)
        h.deliver(2, size = BodyDownloader.MAX_MESSAGE_BYTES + 1)

        h.engine.sync(h.accountId)

        assertEquals(listOf("fetchBody INBOX 1"), h.fetches())
        assertNull(h.bodyOf(2))
    }

    @Test
    fun `a body already cached is never fetched again`() = runTest {
        val h = start()
        (1..3).forEach { h.deliver(it) }
        h.engine.sync(h.accountId)
        h.messages.setBody(h.accountId, "INBOX", 2, null, null)
        h.server.log.clear()

        h.engine.sync(h.accountId)
        h.engine.sync(h.accountId)

        assertEquals(listOf("fetchBody INBOX 2"), h.fetches())
    }

    @Test
    fun `the message count budget ends the run and the next one continues`() = runTest {
        val h = start(downloads = false)
        (1..5).forEach { h.deliver(it) }
        h.headersOnly()
        val account = h.db.accountDao().get(h.accountId)!!
        val budget = BodyBudget(2, Long.MAX_VALUE, Duration.ofHours(1))

        h.bodies.run(h.server.session(), account, budget)
        assertEquals(listOf("fetchBody INBOX 5", "fetchBody INBOX 4"), h.fetches())
        h.bodies.run(h.server.session(), account, budget)
        h.bodies.run(h.server.session(), account, budget)

        assertEquals((5 downTo 1).map { "fetchBody INBOX $it" }, h.fetches())
        assertTrue((1L..5L).all { h.bodyOf(it) != null })
    }

    @Test
    fun `the volume budget ends the run once enough text was downloaded`() = runTest {
        val h = start(downloads = false)
        val big = MessageBody("x".repeat(100), null, emptyList())
        (1..4).forEach { h.deliver(it, body = big) }
        h.headersOnly()
        val account = h.db.accountDao().get(h.accountId)!!

        h.bodies.run(h.server.session(), account, BodyBudget(100, 150, Duration.ofHours(1)))

        assertEquals(2, h.fetches().size)
    }

    @Test
    fun `the time budget ends the run`() = runTest {
        val h = start(downloads = false)
        (1..4).forEach { h.deliver(it) }
        h.headersOnly()
        val account = h.db.accountDao().get(h.accountId)!!
        h.server.failure = {
            if (it.startsWith("fetchBody")) h.clock.now = h.clock.now.plusSeconds(60)
            null
        }
        val budget = BodyBudget(100, Long.MAX_VALUE, Duration.ofSeconds(120))

        h.bodies.run(h.server.session(), account, budget)

        assertEquals(2, h.fetches().size)
    }

    @Test
    fun `a dropped connection mid way keeps what was stored and the next run resumes`() = runTest {
        val h = start()
        (1..4).forEach { h.deliver(it) }
        var dropped = false
        h.server.failure = {
            if (it == "fetchBody INBOX 2" && !dropped) {
                dropped = true
                MailResult.NetworkUnavailable
            } else {
                null
            }
        }

        val first = h.engine.sync(h.accountId)

        assertEquals(AccountSyncResult.Failed(SyncProblem.NETWORK), first)
        assertNotNull(h.bodyOf(4))
        assertNotNull(h.bodyOf(3))
        assertNull(h.bodyOf(2))
        assertNull(h.bodyOf(1))
        h.server.log.clear()

        val second = h.engine.sync(h.accountId)

        assertTrue(second is AccountSyncResult.Synced)
        assertEquals(listOf("fetchBody INBOX 2", "fetchBody INBOX 1"), h.fetches())
        assertEquals("text 1", h.bodyOf(1))
    }

    @Test
    fun `one message that fails does not block the others`() = runTest {
        val h = start()
        (1..4).forEach { h.deliver(it) }
        h.server.failure = { if (it == "fetchBody INBOX 3") MailResult.Protocol else null }

        val result = h.engine.sync(h.accountId)

        assertTrue(result is AccountSyncResult.Synced)
        assertNull(h.bodyOf(3))
        assertEquals(listOf(4L, 2L, 1L), listOf(4L, 2L, 1L).filter { h.bodyOf(it) != null })
    }

    @Test
    fun `a message that keeps failing is given up on after a few tries`() = runTest {
        val h = start()
        h.deliver(1)
        h.server.failure = { if (it == "fetchBody INBOX 1") MailResult.Protocol else null }
        repeat(BodyDownloader.MAX_FAILURES) { h.engine.sync(h.accountId) }
        h.server.failure = { null }
        h.server.log.clear()

        h.engine.sync(h.accountId)

        assertEquals(emptyList<String>(), h.fetches())
        assertNull(h.bodyOf(1))
    }

    @Test
    fun `a message gone from the server is skipped without failing the sync`() = runTest {
        val h = start()
        h.deliver(1, body = null)
        h.deliver(2)

        val result = h.engine.sync(h.accountId)

        assertTrue(result is AccountSyncResult.Synced)
        assertEquals("text 2", h.bodyOf(2))
        assertNull(h.bodyOf(1))
    }

    @Test
    fun `attachments are not downloaded but small inline images the HTML shows are`() = runTest {
        val h = start()
        val body = MessageBody(
            text = null,
            html = "<img src=\"cid:logo@x\"><img src=\"cid:big@x\">",
            attachments = listOf(
                AttachmentInfo("2", "logo.png", "image/png", 1_000, "<logo@x>", true),
                AttachmentInfo("3", "big.png", "image/png", 3_000_000, "<big@x>", true),
                AttachmentInfo("4", "a.pdf", "application/pdf", 500, null, false),
                AttachmentInfo("5", "other.png", "image/png", 10, "<other@x>", true)
            )
        )
        val uid = h.deliver(1, body = body)
        h.server.folder("INBOX").attachments[uid to "2"] = ByteArray(1_000)

        h.engine.sync(h.accountId)

        assertEquals(listOf("fetchAttachment INBOX 1 2"), h.server.logged("fetchAttachment"))
        val rows = h.db.attachmentDao().listFor(h.messages.get(h.accountId, "INBOX", uid)!!.id)
        assertEquals(
            mapOf(
                "logo.png" to AttachmentState.DOWNLOADED,
                "big.png" to AttachmentState.REMOTE,
                "a.pdf" to AttachmentState.REMOTE,
                "other.png" to AttachmentState.REMOTE
            ),
            rows.associate { it.fileName to it.state }
        )
        assertEquals(1, h.storage.files.size)
    }

    @Test
    fun `with the switch off sync downloads no bodies`() = runTest {
        val h = start(downloads = false)
        (1..3).forEach { h.deliver(it) }

        val result = h.engine.sync(h.accountId)

        assertTrue(result is AccountSyncResult.Synced)
        assertEquals(emptyList<String>(), h.fetches())
        assertNull(h.bodyOf(3))
    }

    @Test
    fun `folders that are not synced download nothing`() = runTest {
        val h = start(downloads = false)
        h.server.folder("Other")
        h.deliver(1, folder = "Other")
        h.engine.sync(h.accountId)
        h.folders.setSyncEnabled(h.accountId, "Other", false)
        h.offlineDownloads.setEnabled(h.accountId, true)

        h.engine.sync(h.accountId)

        assertEquals(emptyList<String>(), h.fetches())
        assertNull(h.bodyOf(1, "Other"))
    }

    @Test
    fun `turning the switch on later downloads what was left`() = runTest {
        val h = start(downloads = false)
        h.deliver(1)
        h.headersOnly()

        h.engine.sync(h.accountId)

        assertEquals("text 1", h.bodyOf(1))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `progress is published while bodies come in and ends idle`() = runTest {
        val h = start()
        (1..3).forEach { h.deliver(it) }
        val seen = mutableListOf<AccountSyncState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            h.status.observe(h.accountId).collect { seen += it }
        }

        h.engine.sync(h.accountId)

        assertEquals(
            listOf(
                AccountSyncState.Idle(),
                AccountSyncState.Syncing,
                AccountSyncState.DownloadingBodies(0, 3),
                AccountSyncState.DownloadingBodies(1, 3),
                AccountSyncState.DownloadingBodies(2, 3),
                AccountSyncState.DownloadingBodies(3, 3),
                AccountSyncState.Idle(h.clock.now)
            ),
            seen
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `progress counts the bodies already on the device`() = runTest {
        val h = start()
        (1..4).forEach { h.deliver(it) }
        h.engine.sync(h.accountId)
        h.messages.setBody(h.accountId, "INBOX", 1, null, null)
        h.messages.setBody(h.accountId, "INBOX", 2, null, null)
        val states = mutableListOf<AccountSyncState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            h.status.observe(h.accountId).collect { states += it }
        }

        h.engine.sync(h.accountId)

        assertTrue(AccountSyncState.DownloadingBodies(2, 4) in states)
        assertTrue(AccountSyncState.DownloadingBodies(4, 4) in states)
    }

    @Test
    fun `a downloaded message reads offline without touching the network`() = runTest {
        val h = start()
        h.deliver(1)
        h.engine.sync(h.accountId)
        h.connector.failure = MailResult.NetworkUnavailable
        val connects = h.connector.connects.size
        val id = h.messages.get(h.accountId, "INBOX", 1)!!.id

        val result = LoadMessageBody(h.messages, h.sessions, h.bodyStore)(id)

        assertEquals(BodyResult.Loaded("text 1", "<p>1</p>"), result)
        assertEquals(connects, h.connector.connects.size)
    }

    @Test
    fun `an authentication failure while downloading asks to sign in again`() = runTest {
        val h = start()
        h.deliver(1)
        h.server.failure = {
            if (it.startsWith("fetchBody")) MailResult.AuthenticationFailed else null
        }

        val result = h.engine.sync(h.accountId)

        assertEquals(AccountSyncResult.ReauthenticationNeeded, result)
    }
}
