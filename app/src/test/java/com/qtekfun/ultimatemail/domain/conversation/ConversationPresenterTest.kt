// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.conversation

import com.qtekfun.ultimatemail.data.local.entity.AttachmentEntity
import com.qtekfun.ultimatemail.data.local.entity.FolderEntity
import com.qtekfun.ultimatemail.data.local.message
import com.qtekfun.ultimatemail.data.local.model.AttachmentState
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.data.settings.RemoteContentPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConversationPresenterTest {
    private val presenter = ConversationPresenter()
    private val folders = listOf(
        FolderEntity(1, "INBOX", "INBOX", FolderRole.INBOX),
        FolderEntity(1, "Archive", "Archive", FolderRole.ARCHIVE)
    )

    private fun data(
        vararg messages: com.qtekfun.ultimatemail.data.local.entity.MessageEntity,
        attachments: Map<Long, List<AttachmentEntity>> = emptyMap()
    ) = ConversationData("ana@example.test", messages.toList(), attachments, folders)

    private fun msg(id: Long, uid: Long = id, text: String? = null, html: String? = null) =
        message(1, uid, bodyText = text).copy(id = id, bodyHtml = html)

    private fun present(d: ConversationData, local: ConversationLocal = ConversationLocal()) =
        presenter.present(d, local, "INBOX")

    @Test
    fun `messages keep their order and a collapsed one has no body`() {
        val view = present(data(msg(1), msg(2)), ConversationLocal(expanded = setOf(2)))

        assertEquals(listOf(1L, 2L), view.messages.map { it.id })
        assertNull(view.messages[0].body)
        assertEquals(2L, view.newest?.id)
        assertTrue(view.messages[1].expanded)
    }

    @Test
    fun `the subject is the newest one that is not blank`() {
        val view = present(
            data(
                msg(1).copy(subject = "Old subject"),
                msg(2).copy(subject = "Newest subject"),
                msg(3).copy(subject = "")
            )
        )

        assertEquals("Newest subject", view.subject)
    }

    @Test
    fun `labels are merged across messages without the folder and system labels`() {
        val view = present(
            data(
                msg(1).copy(labels = listOf("\\Inbox", "Travel", "INBOX")),
                msg(2).copy(labels = listOf("Travel", "Work/Invoices"))
            )
        )

        assertEquals(listOf("Travel", "Invoices"), view.labels.chips.map { it.text })
    }

    @Test
    fun `recipients are summarized from the account address`() {
        val view = present(
            data(msg(1).copy(toAddresses = listOf("ana@example.test", "bob@example.test")))
        )

        val recipients = view.messages.single().recipients
        assertTrue(recipients.includesMe)
        assertEquals(listOf("bob@example.test"), recipients.names)
    }

    @Test
    fun `an open message with a cached body is ready`() {
        val view = present(
            data(msg(1, text = "Hello")),
            ConversationLocal(expanded = setOf(1))
        )

        val body = view.messages.single().body as BodyView.Ready
        assertEquals(RenderedBody.Text(listOf(TextRun("Hello"))), body.rendered)
        assertFalse(body.remoteBanner != null)
    }

    @Test
    fun `an open message without a body is loading, or failed with its preview`() {
        val open = ConversationLocal(expanded = setOf(1))

        assertEquals(BodyView.Loading, present(data(msg(1)), open).messages.single().body)
        assertEquals(
            BodyView.Loading,
            present(
                data(msg(1)),
                open.copy(bodyLoads = mapOf(1L to BodyLoad.Loading))
            ).messages.single().body
        )
        val failed = present(
            data(msg(1).copy(snippet = "Preview text")),
            open.copy(bodyLoads = mapOf(1L to BodyLoad.Failed(BodyFailure.OFFLINE)))
        ).messages.single().body
        assertEquals(BodyView.Failed(BodyFailure.OFFLINE, "Preview text"), failed)
    }

    @Test
    fun `a cached body wins over a stale failure`() {
        val view = present(
            data(msg(1, text = "here")),
            ConversationLocal(
                expanded = setOf(1),
                bodyLoads = mapOf(1L to BodyLoad.Failed(BodyFailure.OTHER))
            )
        )

        assertTrue(view.messages.single().body is BodyView.Ready)
    }

    @Test
    fun `the remote content policy of the settings reaches the banner and the colours choice`() {
        val html = "<p>Hi</p><img src=\"https://tracker.example.test/p.gif\">"
        val data = data(msg(1, html = html))
        val open = ConversationLocal(expanded = setOf(1), originalColors = setOf(1))

        val view = presenter.present(data, open, "INBOX", RemoteContentPolicy.ASK)
        val body = view.messages.single().body as BodyView.Ready

        assertEquals(RemoteBanner.ASK, body.remoteBanner)
        assertTrue(body.originalColors)
    }

    @Test
    fun `remote images offer the banner until the reader allows them`() {
        val html = "<p>Hi</p><img src=\"https://tracker.example.test/p.gif\">"
        val data = data(msg(1, html = html))
        val open = ConversationLocal(expanded = setOf(1))

        val blocked = present(data, open).messages.single().body as BodyView.Ready
        val allowed = present(data, open.copy(remoteAllowed = setOf(1))).messages.single()
            .body as BodyView.Ready

        assertEquals(RemoteBanner.BLOCKED, blocked.remoteBanner)
        assertNull(allowed.remoteBanner)
        assertFalse((allowed.rendered as RenderedBody.Html).sanitized.hadBlockedRemoteContent)
    }

    @Test
    fun `the quoted part is folded until the reader unfolds it`() {
        val data = data(msg(1, text = "Thanks\n\n> old text"))
        val open = ConversationLocal(expanded = setOf(1))

        val folded = present(data, open).messages.single().body as BodyView.Ready
        val unfolded = present(data, open.copy(quotedShown = setOf(1))).messages.single()
            .body as BodyView.Ready

        assertTrue(folded.body.hasQuote)
        assertEquals(RenderedBody.Text(listOf(TextRun("Thanks"))), folded.rendered)
        assertEquals(
            "Thanks\n\n> old text",
            (unfolded.rendered as RenderedBody.Text).runs.joinToString("") { it.text }
        )
    }

    private fun attachment(
        id: Long,
        state: AttachmentState = AttachmentState.REMOTE,
        contentId: String? = null,
        inline: Boolean = false,
        path: String? = null,
        mime: String = "application/pdf"
    ) = AttachmentEntity(
        id = id,
        messageId = 1,
        partId = "$id",
        fileName = "file$id",
        mimeType = mime,
        size = 10,
        state = state,
        localPath = path,
        contentId = contentId,
        inline = inline
    )

    @Test
    fun `attachments show their kind and state`() {
        val view = present(
            data(
                msg(1),
                attachments = mapOf(1L to listOf(attachment(1, AttachmentState.FAILED)))
            )
        )

        val file = view.messages.single().attachments.single()
        assertEquals(AttachmentKind.PDF, file.kind)
        assertEquals(AttachmentState.FAILED, file.state)
    }

    @Test
    fun `a download that is not running any more is offered again`() {
        val files = mapOf(1L to listOf(attachment(1, AttachmentState.DOWNLOADING)))

        val stale = present(data(msg(1), attachments = files))
        val running = present(
            data(msg(1), attachments = files),
            ConversationLocal(downloading = setOf(1))
        )

        assertEquals(AttachmentState.REMOTE, stale.messages.single().attachments.single().state)
        assertEquals(
            AttachmentState.DOWNLOADING,
            running.messages.single().attachments.single().state
        )
    }

    @Test
    fun `inline images the html shows are not listed and are served by content id`() {
        val files = mapOf(
            1L to listOf(
                attachment(
                    1,
                    AttachmentState.DOWNLOADED,
                    contentId = "logo@x",
                    inline = true,
                    path = "/files/1",
                    mime = "image/png"
                ),
                attachment(2, contentId = "other@x", inline = true, mime = "image/png"),
                attachment(3)
            )
        )

        val view = present(
            data(msg(1, html = "<img src=\"cid:logo@x\">"), attachments = files)
        )

        val message = view.messages.single()
        assertEquals(listOf(2L, 3L), message.attachments.map { it.id })
        assertEquals(mapOf("logo@x" to CidFile("/files/1", "image/png")), message.cidFiles)
    }

    @Test
    fun `details and pending state are passed on`() {
        val view = present(
            data(msg(1).copy(pendingSync = true, flagged = true)),
            ConversationLocal(detailsShown = setOf(1))
        )

        val shown = view.messages.single()
        assertTrue(shown.pendingSync)
        assertTrue(shown.flagged)
        assertTrue(shown.detailsShown)
        assertTrue(shown.unread)
    }

    @Test
    fun `the targets follow the folder the conversation is in`() {
        val view = present(data(msg(1)))

        assertEquals("Archive", view.targets.archivePath)
        assertFalse(view.targets.canDelete)
    }
}

class BodyPreparerTest {
    private val preparer = BodyPreparer()

    @Test
    fun `html wins over text and is sanitized`() {
        val body = preparer.prepare(
            1,
            "plain",
            "<p onclick=\"x()\">rich</p><script>bad()</script>",
            false
        )

        val html = (body.collapsed as RenderedBody.Html).sanitized.html
        assertEquals("<p>rich</p>", html)
    }

    @Test
    fun `a blank html falls back on the text`() {
        val body = preparer.prepare(1, "plain", "  ", false)

        assertEquals(RenderedBody.Text(listOf(TextRun("plain"))), body.collapsed)
    }

    @Test
    fun `no text at all is empty`() {
        assertEquals(RenderedBody.Empty, preparer.prepare(1, "", null, false).collapsed)
        assertEquals(RenderedBody.Empty, preparer.prepare(2, null, null, false).collapsed)
    }

    @Test
    fun `an html quote gives a collapsed and an expanded body`() {
        val body = preparer.prepare(1, null, "<p>Reply</p><blockquote>old</blockquote>", false)

        assertTrue(body.hasQuote)
        assertEquals("<p>Reply</p>", (body.shown(false) as RenderedBody.Html).sanitized.html)
        assertEquals(
            "<p>Reply</p><blockquote>old</blockquote>",
            (body.shown(true) as RenderedBody.Html).sanitized.html
        )
    }

    @Test
    fun `without a quote showing the quote changes nothing`() {
        val body = preparer.prepare(1, "just text", null, false)

        assertFalse(body.hasQuote)
        assertEquals(body.collapsed, body.shown(true))
    }

    @Test
    fun `remote content is only let through when allowed, and the banner knows`() {
        val html = "<img src=\"https://example.test/a.png\">"

        val blocked = preparer.prepare(1, null, html, false)
        val allowed = preparer.prepare(1, null, html, true)

        assertTrue(blocked.hadBlockedRemoteContent)
        assertFalse(allowed.hadBlockedRemoteContent)
    }

    @Test
    fun `a quoted image that is blocked is reported even when the visible part has none`() {
        val body = preparer.prepare(
            1,
            null,
            "<p>Reply</p><blockquote><img src=\"https://example.test/a.png\"></blockquote>",
            false
        )

        assertTrue(body.hadBlockedRemoteContent)
    }

    @Test
    fun `the same message is prepared once`() {
        val first = preparer.prepare(7, "text", null, false)
        val second = preparer.prepare(7, "text", null, false)

        assertTrue(first === second)
    }

    @Test
    fun `a body that arrives later is not served from an old empty result`() {
        val empty = preparer.prepare(8, null, null, false)
        val filled = preparer.prepare(8, "now there is text", null, false)

        assertEquals(RenderedBody.Empty, empty.collapsed)
        assertTrue(filled.collapsed is RenderedBody.Text)
    }
}
