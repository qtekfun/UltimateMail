// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.local

import com.qtekfun.ultimatemail.data.local.entity.AttachmentEntity
import com.qtekfun.ultimatemail.data.local.model.AttachmentState
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileAttachmentStorageTest {
    @TempDir
    lateinit var root: File

    private fun attachment(name: String, id: Long = 5, messageId: Long = 9) = AttachmentEntity(
        id = id,
        messageId = messageId,
        partId = "2",
        fileName = name,
        mimeType = "application/pdf",
        size = 3
    )

    @Test
    fun `a file is stored under its account and message and can be read back`() {
        val storage = FileAttachmentStorage(root)

        val path = storage.write(1, attachment("report.pdf"), byteArrayOf(1, 2, 3))

        assertEquals(listOf<Byte>(1, 2, 3), File(path).readBytes().toList())
        assertEquals(File(root, "1/9/5-report.pdf").absolutePath, path)
        assertTrue(storage.exists(path))
    }

    @Test
    fun `a hostile name cannot leave the folder of its message`() {
        val storage = FileAttachmentStorage(root)

        val path = storage.write(1, attachment("../../../evil.sh"), byteArrayOf(1))

        assertTrue(File(path).canonicalPath.startsWith(File(root, "1/9").canonicalPath))
        assertFalse(File(root.parentFile, "evil.sh").exists())
    }

    @Test
    fun `no partial file is left behind`() {
        val storage = FileAttachmentStorage(root)

        storage.write(1, attachment("a.bin"), byteArrayOf(1))

        assertEquals(listOf("5-a.bin"), File(root, "1/9").list()!!.toList())
    }

    @Test
    fun `writing again replaces the earlier copy`() {
        val storage = FileAttachmentStorage(root)
        storage.write(1, attachment("a.bin"), byteArrayOf(1))

        val path = storage.write(1, attachment("a.bin"), byteArrayOf(7, 8))

        assertEquals(listOf<Byte>(7, 8), File(path).readBytes().toList())
    }

    @Test
    fun `a file that is gone or outside the folder does not exist for the app`() {
        val storage = FileAttachmentStorage(File(root, "attachments"))
        val outside = File(root, "outside.txt").also { it.writeText("x") }

        assertFalse(storage.exists(File(root, "attachments/1/9/5-a").absolutePath))
        assertFalse(storage.exists(outside.absolutePath))
        assertFalse(storage.exists(File(root, "attachments/../outside.txt").path))
    }

    @Test
    fun `deleting an account removes its files only`() {
        val storage = FileAttachmentStorage(root)
        val gone = storage.write(1, attachment("a.bin"), byteArrayOf(1))
        val kept = storage.write(2, attachment("b.bin", id = 6), byteArrayOf(2))

        storage.deleteAccount(1)

        assertFalse(File(gone).exists())
        assertTrue(File(kept).exists())
    }

    @Test
    fun `deleting an account that has no files is harmless`() {
        FileAttachmentStorage(root).deleteAccount(42)
    }
}

class AttachmentDaoTest {
    private val db = inMemoryDatabase()

    @org.junit.jupiter.api.AfterEach
    fun close() = db.close()

    @Test
    fun `the attachments of several messages come together and keep their content id`() = runTest {
        val accountId = db.accountDao().insert(account())
        db.folderDao().upsert(listOf(folder(accountId)))
        db.messageDao().upsert(listOf(message(accountId, 1), message(accountId, 2)))
        val first = db.messageDao().get(accountId, "INBOX", 1)!!.id
        val second = db.messageDao().get(accountId, "INBOX", 2)!!.id
        val dao = db.attachmentDao()
        dao.insert(
            listOf(
                AttachmentEntity(
                    messageId = first,
                    partId = "2",
                    fileName = "a",
                    mimeType = "image/png",
                    size = 1,
                    contentId = "logo@x",
                    inline = true
                ),
                AttachmentEntity(
                    messageId = second,
                    partId = "2",
                    fileName = "b",
                    mimeType = "text/plain",
                    size = 2
                )
            )
        )

        val all = dao.observeForMessages(listOf(first, second)).first()
        val onlyFirst = dao.observeForMessages(listOf(first)).first()

        assertEquals(listOf("a", "b"), all.map { it.fileName })
        assertEquals("logo@x", all[0].contentId)
        assertTrue(all[0].inline)
        assertFalse(all[1].inline)
        assertEquals(listOf("a"), onlyFirst.map { it.fileName })
        assertEquals(listOf("a"), dao.listFor(first).map { it.fileName })
        assertEquals("b", dao.get(all[1].id)!!.fileName)
        assertEquals(null, dao.get(9_999))
        assertEquals(AttachmentState.REMOTE, all[0].state)
    }
}
