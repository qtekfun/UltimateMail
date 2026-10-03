// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import app.cash.turbine.test
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DraftAttachmentsTest {
    private var harness: ComposeHarness? = null

    @AfterEach
    fun close() {
        harness?.close()
    }

    private suspend fun TestScope.start(): Pair<ComposeHarness, Long> {
        val h = ComposeHarness(this)
        harness = h
        h.addAccount()
        return h to h.engine.newMessage(h.accountId)!!.id
    }

    private fun ComposeHarness.pick(
        uri: String,
        name: String?,
        size: Int,
        mime: String? = "text/plain"
    ) = attachmentSource.put(uri, name, mime, ByteArray(size) { 1 })

    @Test
    fun `a picked file is copied into the outbox and listed`() = runTest {
        val (h, draft) = start()
        h.attachmentSource.put("content://x", "report.pdf", "application/pdf", byteArrayOf(1, 2, 3))

        val added = h.attachments.add(draft, "content://x") as AddAttachmentResult.Added

        assertEquals("report.pdf", added.attachment.displayName)
        assertEquals("application/pdf", added.attachment.mimeType)
        assertEquals(3L, added.attachment.size)
        assertEquals(3L, added.totalBytes)
        assertFalse(added.overWarning)
        assertEquals(
            listOf<Byte>(1, 2, 3),
            h.files.files.getValue(added.attachment.filePath).toList()
        )
        assertEquals(listOf(added.attachment), h.attachments.list(draft))
        assertEquals(3L, h.attachments.totalBytes(draft))
    }

    @Test
    fun `a file without a name or type gets plain defaults and a hostile name is made safe`() =
        runTest {
            val (h, draft) = start()
            h.attachmentSource.put("content://a", null, null, byteArrayOf(1))
            h.attachmentSource.put(
                "content://b",
                "../../etc/passwd\n",
                "text/plain",
                byteArrayOf(1)
            )

            val bare = h.attachments.add(draft, "content://a") as AddAttachmentResult.Added
            val hostile = h.attachments.add(draft, "content://b") as AddAttachmentResult.Added

            assertEquals("attachment", bare.attachment.displayName)
            assertEquals("application/octet-stream", bare.attachment.mimeType)
            assertTrue(
                "/" !in hostile.attachment.displayName && "\n" !in hostile.attachment.displayName
            )
        }

    @Test
    fun `the total over the warning level is flagged but still accepted`() = runTest {
        val (h, draft) = start()
        val big = (AttachmentLimits.WARN_BYTES / 2).toInt()
        h.pick("content://half1", "h1", big)
        h.pick("content://half2", "h2", big + 10)

        val first = h.attachments.add(draft, "content://half1") as AddAttachmentResult.Added
        val second = h.attachments.add(draft, "content://half2") as AddAttachmentResult.Added

        assertFalse(first.overWarning)
        assertTrue(second.overWarning)
        assertEquals(2L * big + 10, second.totalBytes)
    }

    @Test
    fun `a file that would take the draft over the limit is refused and nothing is kept`() =
        runTest {
            val (h, draft) = start()
            h.attachmentSource.put(
                "content://huge",
                "huge.bin",
                null,
                ByteArray(1),
                reportedSize = AttachmentLimits.MAX_BYTES + 1
            )

            val result = h.attachments.add(draft, "content://huge")

            assertEquals(AddAttachmentResult.TooLarge(AttachmentLimits.MAX_BYTES), result)
            assertTrue(h.files.files.isEmpty())
            assertTrue(h.attachments.list(draft).isEmpty())
        }

    @Test
    fun `the limit counts what is already attached`() = runTest {
        val (h, draft) = start()
        val almost = (AttachmentLimits.MAX_BYTES - 10).toInt()
        h.pick("content://first", "first", almost)
        assertTrue(h.attachments.add(draft, "content://first") is AddAttachmentResult.Added)
        h.pick("content://second", "second", 11)

        val result = h.attachments.add(draft, "content://second")

        assertEquals(AddAttachmentResult.TooLarge(AttachmentLimits.MAX_BYTES), result)
        assertEquals(1, h.attachments.list(draft).size)
    }

    @Test
    fun `a file whose real size is bigger than the one reported is still refused`() = runTest {
        val (h, draft) = start()
        val real = AttachmentLimits.MAX_BYTES.toInt() + 1
        h.attachmentSource.put("content://liar", "liar", null, ByteArray(real), reportedSize = 5)
        h.attachmentSource.put(
            "content://unknown",
            "unknown",
            null,
            ByteArray(real),
            reportedSize = null
        )

        assertEquals(
            AddAttachmentResult.TooLarge(AttachmentLimits.MAX_BYTES),
            h.attachments.add(draft, "content://liar")
        )
        assertEquals(
            AddAttachmentResult.TooLarge(AttachmentLimits.MAX_BYTES),
            h.attachments.add(draft, "content://unknown")
        )
        assertTrue(h.files.files.isEmpty())
    }

    @Test
    fun `a file that cannot be read or copied is reported and nothing is kept`() = runTest {
        val (h, draft) = start()
        h.attachmentSource.putUnopenable("content://gone")
        h.pick("content://ok", "ok.txt", 4)

        assertEquals(AddAttachmentResult.Unreadable, h.attachments.add(draft, "content://unknown"))
        assertEquals(AddAttachmentResult.Unreadable, h.attachments.add(draft, "content://gone"))
        h.files.failWrites = true
        assertEquals(AddAttachmentResult.Unreadable, h.attachments.add(draft, "content://ok"))
        assertTrue(h.attachments.list(draft).isEmpty())
    }

    @Test
    fun `a missing draft is reported`() = runTest {
        val (h, _) = start()

        assertEquals(AddAttachmentResult.DraftMissing, h.attachments.add(999, "content://x"))
    }

    @Test
    fun `removing an attachment removes its file and ignores unknown ones`() = runTest {
        val (h, draft) = start()
        h.pick("content://a", "a.txt", 4)
        h.pick("content://b", "b.txt", 6)
        val a = (h.attachments.add(draft, "content://a") as AddAttachmentResult.Added).attachment
        val b = (h.attachments.add(draft, "content://b") as AddAttachmentResult.Added).attachment

        h.attachments.remove(a.id)
        h.attachments.remove(999)

        assertEquals(listOf(b), h.attachments.list(draft))
        assertFalse(h.files.exists(a.filePath))
        assertTrue(h.files.exists(b.filePath))
        assertEquals(6L, h.attachments.totalBytes(draft))
    }

    @Test
    fun `the list can be observed`() = runTest {
        val (h, draft) = start()
        h.pick("content://a", "a.txt", 4)

        h.attachments.observe(draft).test {
            assertTrue(awaitItem().isEmpty())
            h.attachments.add(draft, "content://a")
            assertEquals(1, awaitItem().size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `discarding the draft takes its files with it`() = runTest {
        val (h, draft) = start()
        h.pick("content://a", "a.txt", 4)
        h.attachments.add(draft, "content://a")

        h.engine.discard(draft)

        assertTrue(h.files.files.isEmpty())
        assertNull(h.repository.get(draft))
    }

    @Test
    fun `the limits are what the documentation says`() {
        assertEquals(20L * 1024 * 1024, AttachmentLimits.WARN_BYTES)
        assertEquals(25L * 1024 * 1024, AttachmentLimits.MAX_BYTES)
        assertFalse(AttachmentLimits.isOverWarning(AttachmentLimits.WARN_BYTES))
        assertTrue(AttachmentLimits.isOverWarning(AttachmentLimits.WARN_BYTES + 1))
    }
}
