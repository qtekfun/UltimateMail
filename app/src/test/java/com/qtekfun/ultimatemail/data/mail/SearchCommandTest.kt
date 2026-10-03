// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.mail

import com.qtekfun.ultimatemail.domain.mail.MailCredentials
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MailSearchCriteria
import com.qtekfun.ultimatemail.domain.mail.MailServer
import com.qtekfun.ultimatemail.domain.mail.MailSession
import com.qtekfun.ultimatemail.domain.mail.TransportSecurity
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The SEARCH command lines the session sends, seen from a scripted server. */
class SearchCommandTest {
    private val closeables = mutableListOf<AutoCloseable>()
    private val searches = CopyOnWriteArrayList<String>()

    @AfterEach
    fun cleanUp() = closeables.forEach { it.close() }

    private fun session(): MailSession {
        val server = ScriptedImapServer { tag, command ->
            when (command.substringBefore(' ').uppercase()) {
                "CAPABILITY" -> listOf("* CAPABILITY IMAP4rev1", "$tag OK done")

                "LOGIN" -> listOf("$tag OK logged in")

                "LOGOUT" -> listOf("* BYE bye", "$tag OK done")

                "SEARCH" -> {
                    searches += command
                    listOf("* SEARCH", "$tag OK done")
                }

                else -> listOf(
                    "* 0 EXISTS",
                    "* OK [UIDVALIDITY 7] ok",
                    "* OK [UIDNEXT 5] ok",
                    "$tag OK [READ-ONLY] done"
                )
            }
        }
        closeables += server
        val connector = AngusMailConnector(Dispatchers.IO, trustingTestConfig(), GmailExtensions())
        val result = runBlocking {
            connector.connect(
                MailServer(TEST_HOST, server.port, TransportSecurity.TLS),
                MailCredentials.Password(TEST_USER, TEST_PASSWORD)
            )
        }
        check(result is MailResult.Success) { "connect failed: $result" }
        return result.value.also { closeables += AutoCloseable { runBlocking { it.close() } } }
    }

    private fun commandsFor(criteria: MailSearchCriteria): List<String> {
        searches.clear()
        runBlocking { session().search("INBOX", criteria) }
        return searches.toList()
    }

    @Test
    fun `free text is looked for in subject, sender and body`() {
        assertEquals(
            listOf("SEARCH OR OR SUBJECT x FROM x BODY x ALL"),
            commandsFor(MailSearchCriteria(text = listOf("x")))
        )
    }

    @Test
    fun `a phrase travels as one quoted string`() {
        assertEquals(
            listOf(
                "SEARCH OR OR SUBJECT \"lunch plans\" FROM \"lunch plans\" " +
                    "BODY \"lunch plans\" ALL"
            ),
            commandsFor(MailSearchCriteria(text = listOf("lunch plans")))
        )
    }

    @Test
    fun `a quote in the text is escaped and cannot close the string`() {
        assertEquals(
            listOf(
                "SEARCH OR OR SUBJECT \"a\\\" ALL b\" FROM \"a\\\" ALL b\" BODY \"a\\\" ALL b\" ALL"
            ),
            commandsFor(MailSearchCriteria(text = listOf("a\" ALL b")))
        )
    }

    @Test
    fun `every criterion becomes its own key`() {
        val command = commandsFor(
            MailSearchCriteria(
                text = listOf("x"),
                excluded = listOf("z"),
                from = listOf("ana"),
                to = listOf("bob"),
                subject = listOf("s"),
                unseen = true,
                flagged = true,
                since = LocalDate.of(2026, 3, 1),
                before = LocalDate.of(2026, 4, 5)
            )
        ).single()

        listOf(
            "SUBJECT x FROM x BODY x",
            "NOT OR OR SUBJECT z FROM z BODY z",
            "FROM ana",
            "OR TO bob CC bob",
            "SUBJECT s",
            "UNSEEN",
            "FLAGGED",
            "SENTSINCE 1-Mar-2026",
            "SENTBEFORE 5-Apr-2026"
        ).forEach { assertTrue(it in command, "$it missing from $command") }
    }

    @Test
    fun `a read filter asks for SEEN and criteria with no key ask for everything`() {
        assertTrue("SEEN" in commandsFor(MailSearchCriteria(unseen = false)).single())
        assertEquals(emptyList<String>(), commandsFor(MailSearchCriteria()))
    }
}
