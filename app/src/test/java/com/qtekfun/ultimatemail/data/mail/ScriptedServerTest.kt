// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.mail

import com.qtekfun.ultimatemail.domain.mail.MailCredentials
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MailServer
import com.qtekfun.ultimatemail.domain.mail.MailSession
import com.qtekfun.ultimatemail.domain.mail.RejectionKind
import com.qtekfun.ultimatemail.domain.mail.TransportSecurity
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Failure handling against a scripted IMAP server: NO, BAD, silence and dropped connections. */
class ScriptedServerTest {
    private val closeables = mutableListOf<AutoCloseable>()
    private val credentials = MailCredentials.Password(TEST_USER, TEST_PASSWORD)

    @AfterEach
    fun cleanUp() = closeables.forEach { it.close() }

    private fun connector(readTimeoutMillis: Int = 5_000) = AngusMailConnector(
        Dispatchers.IO,
        trustingTestConfig(connectTimeoutMillis = 2_000, readTimeoutMillis = readTimeoutMillis),
        GmailExtensions()
    )

    /** A server that logs in and capabilities normally and lets [onCommand] decide the rest. */
    private fun server(
        onCommand: (tag: String, command: String) -> List<String>?
    ): ScriptedImapServer {
        val server = ScriptedImapServer { tag, command ->
            when (command.substringBefore(' ').uppercase()) {
                "CAPABILITY" -> listOf("* CAPABILITY IMAP4rev1", "$tag OK done")
                "LOGIN" -> listOf("$tag OK logged in")
                "LOGOUT" -> listOf("* BYE bye", "$tag OK done")
                else -> onCommand(tag, command)
            }
        }
        closeables += server
        return server
    }

    private fun imap(port: Int) = MailServer(TEST_HOST, port, TransportSecurity.TLS)

    private fun session(server: ScriptedImapServer, readTimeoutMillis: Int = 5_000): MailSession {
        val result =
            runBlocking { connector(readTimeoutMillis).connect(imap(server.port), credentials) }
        check(result is MailResult.Success) { "connect failed: $result" }
        return result.value.also { closeables += AutoCloseable { runBlocking { it.close() } } }
    }

    @Test
    fun `a NO answer to a command is a permanent ServerRejected`() {
        val s = session(server { tag, _ -> listOf("$tag NO [NONEXISTENT] no such mailbox") })
        assertEquals(
            MailResult.ServerRejected(RejectionKind.NO, permanent = true),
            runBlocking { s.folderStatus("INBOX") }
        )
    }

    @Test
    fun `a NO with a transient code can be retried`() {
        val s = session(server { tag, _ -> listOf("$tag NO [UNAVAILABLE] try again later") })
        assertEquals(
            MailResult.ServerRejected(RejectionKind.NO, permanent = false),
            runBlocking { s.folderStatus("INBOX") }
        )
    }

    @Test
    fun `a BAD answer is a permanent ServerRejected`() {
        val s =
            session(server { tag, _ -> listOf("$tag BAD command unknown or arguments invalid") })
        assertEquals(
            MailResult.ServerRejected(RejectionKind.BAD, permanent = true),
            runBlocking { s.folderStatus("INBOX") }
        )
    }

    @Test
    fun `the session keeps working after a rejected command`() {
        var first = true
        val scripted = server { tag, command ->
            val verb = command.substringBefore(' ').uppercase()
            if (verb == "LIST") {
                // After a refused open the library asks whether the folder exists.
                listOf("* LIST () \"/\" \"INBOX\"", "$tag OK done")
            } else if (verb == "STATUS" && first) {
                first = false
                listOf("$tag NO not now")
            } else if (verb == "STATUS") {
                listOf(
                    "* STATUS \"INBOX\" (MESSAGES 0 UIDNEXT 5 UIDVALIDITY 7)",
                    "$tag OK done"
                )
            } else {
                listOf(
                    "* 0 EXISTS",
                    "* OK [UIDVALIDITY 7] ok",
                    "* OK [UIDNEXT 5] ok",
                    "$tag OK [READ-ONLY] done"
                )
            }
        }
        val s = session(scripted)
        val rejected = runBlocking { s.folderStatus("INBOX") }
        assertTrue(
            rejected is MailResult.ServerRejected,
            "was $rejected, commands ${scripted.commands}"
        )
        val status = (runBlocking { s.folderStatus("INBOX") } as MailResult.Success).value
        assertEquals(7L, status.uidValidity)
        assertEquals(5L, status.uidNext)
    }

    @Test
    fun `a refused login is AuthenticationFailed`() {
        val server = ScriptedImapServer { tag, command ->
            when (command.substringBefore(' ').uppercase()) {
                "CAPABILITY" -> listOf("* CAPABILITY IMAP4rev1", "$tag OK done")
                else -> listOf("$tag NO [AUTHENTICATIONFAILED] invalid credentials")
            }
        }
        closeables += server
        val result = runBlocking { connector().connect(imap(server.port), credentials) }
        assertEquals(MailResult.AuthenticationFailed, result)
    }

    @Test
    fun `a connection dropped during a command is NetworkUnavailable`() {
        val s = session(server { _, _ -> null })
        assertEquals(MailResult.NetworkUnavailable, runBlocking { s.folderStatus("INBOX") })
    }

    @Test
    fun `a connection dropped during login is NetworkUnavailable`() {
        val server = ScriptedImapServer { tag, command ->
            if (command.startsWith(
                    "CAPABILITY"
                )
            ) {
                listOf("* CAPABILITY IMAP4rev1", "$tag OK")
            } else {
                null
            }
        }
        closeables += server
        assertEquals(
            MailResult.NetworkUnavailable,
            runBlocking {
                connector().connect(imap(server.port), credentials)
            }
        )
    }

    @Test
    fun `a server that stops answering is a Timeout`() {
        val s = session(server { _, _ -> emptyList() }, readTimeoutMillis = 600)
        val started = System.nanoTime()
        assertEquals(MailResult.Timeout, runBlocking { s.folderStatus("INBOX") })
        assertTrue((System.nanoTime() - started) / 1_000_000 < 4_000, "the read timeout must apply")
    }

    @Test
    fun `a server that accepts and never speaks is a Timeout on connect`() {
        val silent = ServerSocket(0, 1, InetAddress.getByName(TEST_HOST))
        closeables += silent
        val result =
            runBlocking {
                connector(readTimeoutMillis = 600).connect(imap(silent.localPort), credentials)
            }
        assertEquals(MailResult.Timeout, result)
    }

    @Test
    fun `a closed port is NetworkUnavailable`() {
        val result = runBlocking { connector().connect(imap(freePort()), credentials) }
        assertEquals(MailResult.NetworkUnavailable, result)
    }

    @Test
    fun `a clear text server on a TLS port gets no credentials`() {
        val plain = ServerSocket(0, 1, InetAddress.getByName(TEST_HOST))
        closeables += plain
        val received = java.io.ByteArrayOutputStream()
        val accepted = Thread {
            runCatching {
                plain.accept().use { client ->
                    client.soTimeout = 1_500
                    client.getOutputStream().apply {
                        write("* OK plain imap\r\n".toByteArray())
                        flush()
                    }
                    val buffer = ByteArray(4096)
                    while (true) {
                        val n = client.getInputStream().read(buffer)
                        if (n < 0) break
                        received.write(buffer, 0, n)
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        val result =
            runBlocking {
                connector(readTimeoutMillis = 600).connect(imap(plain.localPort), credentials)
            }
        accepted.join(4_000)
        assertTrue(result is MailResult.Failure, "was $result")
        val sent = received.toString(Charsets.ISO_8859_1)
        assertTrue(sent.isNotEmpty(), "the client should have started a TLS handshake")
        assertTrue(
            !sent.contains("LOGIN") && !sent.contains(TEST_PASSWORD),
            "nothing readable may be sent"
        )
    }

    @Test
    fun `cancelling a call that waits on the server cancels it without a failure result`() {
        val reached = CompletableDeferred<Unit>()
        val s = session(
            server { _, _ ->
                reached.complete(Unit)
                emptyList()
            },
            readTimeoutMillis = 1_500
        )
        runBlocking {
            val call =
                async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    s.folderStatus("INBOX")
                }
            withTimeout(5.seconds) { reached.await() }
            delay(50)
            call.cancelAndJoin()
            assertTrue(call.isCancelled)
        }
    }
}
