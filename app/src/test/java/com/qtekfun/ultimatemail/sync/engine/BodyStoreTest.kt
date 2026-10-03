// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.sync.engine

import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.folder
import com.qtekfun.ultimatemail.data.local.inMemoryDatabase
import com.qtekfun.ultimatemail.data.local.message
import com.qtekfun.ultimatemail.domain.mail.AttachmentInfo
import com.qtekfun.ultimatemail.domain.mail.MessageBody
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BodyStoreTest {
    private val db = inMemoryDatabase()

    @org.junit.jupiter.api.AfterEach
    fun close() = db.close()

    private val body = MessageBody(
        text = "hello",
        html = null,
        attachments = listOf(AttachmentInfo("2", "a.pdf", "application/pdf", 3, null, false))
    )

    @Test
    fun `a second save of the same message keeps the first body and lists attachments once`() =
        runTest {
            val accountId = db.accountDao().insert(account())
            db.folderDao().upsert(listOf(folder(accountId)))
            db.messageDao().upsert(listOf(message(accountId, 1)))
            val row = db.messageDao().get(accountId, "INBOX", 1)!!
            val store = BodyStore(db.messageDao(), db.attachmentDao())

            val first = store.save(row, body)
            val second = store.save(row, body.copy(text = "other"))

            assertEquals(StoredBody("hello", null), first)
            assertEquals(first, second)
            assertEquals(1, db.attachmentDao().listFor(row.id).size)
        }
}
