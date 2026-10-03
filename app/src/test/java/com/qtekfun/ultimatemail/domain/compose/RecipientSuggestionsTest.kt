// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.compose

import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.folder
import com.qtekfun.ultimatemail.data.local.message
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import java.time.Instant
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecipientSuggestionsTest {
    private var harness: ComposeHarness? = null
    private val day = 86_400_000L

    @AfterEach
    fun close() {
        harness?.close()
    }

    private suspend fun TestScope.start(): ComposeHarness {
        val h = ComposeHarness(this)
        harness = h
        h.addAccount()
        return h
    }

    private val now: Long get() = Instant.ofEpochSecond(1_700_100_000).toEpochMilli()

    private suspend fun ComposeHarness.mail(
        uid: Long,
        from: String,
        name: String = "",
        to: List<String> = listOf("ana@example.test"),
        cc: List<String> = emptyList(),
        agoDays: Long = 1,
        folder: String = "INBOX",
        account: Long = accountId
    ) {
        db.messageDao().upsert(
            listOf(
                message(
                    account,
                    uid,
                    folder,
                    sentAt = now - agoDays * day,
                    senderName = name,
                    senderAddress = from
                )
                    .copy(toAddresses = to, ccAddresses = cc)
            )
        )
    }

    private suspend fun ComposeHarness.names(query: String, own: String? = "ana@example.test") =
        suggestions.suggest(accountId, query, own).map { it.address }

    @Test
    fun `people who write often rank above people who wrote once`() = runTest {
        val h = start()
        h.mail(1, "once@example.test")
        h.mail(2, "often@example.test")
        h.mail(3, "often@example.test")
        h.mail(4, "often@example.test")

        assertEquals(listOf("often@example.test", "once@example.test"), h.names(""))
    }

    @Test
    fun `a recent contact beats an old one with the same count`() = runTest {
        val h = start()
        h.mail(1, "old@example.test", agoDays = 400)
        h.mail(2, "new@example.test", agoDays = 2)

        assertEquals(listOf("new@example.test", "old@example.test"), h.names(""))
    }

    @Test
    fun `people the user wrote to count more than people who wrote to the user`() = runTest {
        val h = start()
        h.db.folderDao().upsert(listOf(folder(h.accountId, "Sent", FolderRole.SENT)))
        h.mail(1, "writer@example.test")
        h.mail(2, "writer@example.test")
        h.mail(3, "ana@example.test", to = listOf("recipient@example.test"), folder = "Sent")

        assertEquals(listOf("recipient@example.test", "writer@example.test"), h.names(""))
    }

    @Test
    fun `recipients and copies of messages are suggested too`() = runTest {
        val h = start()
        h.mail(
            1,
            "bob@example.test",
            to = listOf("ana@example.test", "cy@example.test"),
            cc = listOf("di@example.test")
        )

        assertEquals(
            setOf("bob@example.test", "cy@example.test", "di@example.test"),
            h.names("").toSet()
        )
    }

    @Test
    fun `matching ignores case and accents and works on the start of the address or any word`() =
        runTest {
            val h = start()
            h.mail(1, "jp@example.test", name = "José Pérez")
            h.mail(2, "nora@example.test")
            h.mail(3, "other@example.test", name = "Someone Else")

            assertEquals(listOf("jp@example.test"), h.names("jos"))
            assertEquals(listOf("jp@example.test"), h.names("PEREZ"))
            assertEquals(listOf("jp@example.test"), h.names("pérez"))
            assertEquals(listOf("nora@example.test"), h.names("ñor"))
            assertEquals(listOf("other@example.test"), h.names("else"))
            assertTrue(h.names("zzz").isEmpty())
        }

    @Test
    fun `a match in the middle of a word is not a match`() = runTest {
        val h = start()
        h.mail(1, "bob@example.test", name = "Bob Builder")

        assertTrue(h.names("uilder").isEmpty())
        assertEquals(listOf("bob@example.test"), h.names("build"))
    }

    @Test
    fun `the domain part can be typed too`() = runTest {
        val h = start()
        h.mail(1, "bob@acme.test")

        assertEquals(listOf("bob@acme.test"), h.names("bob@ac"))
    }

    @Test
    fun `the user's own address is never suggested`() = runTest {
        val h = start()
        h.mail(1, "ANA@example.test", to = listOf("bob@example.test"))

        assertEquals(listOf("bob@example.test"), h.names(""))
        assertEquals(2, h.names("", own = null).size)
    }

    @Test
    fun `the most recent name is suggested and entries that are not addresses are skipped`() =
        runTest {
            val h = start()
            h.mail(1, "bob@example.test", name = "Robert", agoDays = 300)
            h.mail(2, "bob@example.test", name = "Bob", agoDays = 1)
            h.mail(3, "not an address", to = listOf("also bad"))
            h.mail(4, "late@example.test", name = "", agoDays = 5)
            h.mail(5, "late@example.test", name = "Late Name", agoDays = 100)

            val all = h.suggestions.suggest(h.accountId, "", "ana@example.test")

            assertEquals(
                setOf("bob@example.test", "late@example.test"),
                all.map {
                    it.address
                }.toSet()
            )
            assertEquals("Bob", all.first { it.address == "bob@example.test" }.name)
            assertEquals("Late Name", all.first { it.address == "late@example.test" }.name)
        }

    @Test
    fun `only the account asked for is searched and the number of suggestions is limited`() =
        runTest {
            val h = start()
            val other = h.db.accountDao().insert(account("other@example.test"))
            h.db.folderDao().upsert(listOf(folder(other, "INBOX")))
            h.mail(1, "mine@example.test")
            h.mail(1, "theirs@example.test", account = other)
            (10L..20L).forEach { h.mail(it, "p$it@example.test") }

            assertTrue("theirs@example.test" !in h.names(""))
            assertEquals(3, h.suggestions.suggest(h.accountId, "", null, limit = 3).size)
        }

    @Test
    fun `ties are broken by recency then by address`() = runTest {
        val h = start()
        h.mail(1, "b@example.test", agoDays = 3)
        h.mail(2, "a@example.test", agoDays = 3)
        h.mail(3, "c@example.test", agoDays = 3, to = listOf("zz@example.test"))

        val result = h.names("", own = null).filter { it != "ana@example.test" }

        assertEquals(
            listOf("a@example.test", "b@example.test", "c@example.test", "zz@example.test"),
            result
        )
    }
}
