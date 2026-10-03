// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.data.mail

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import com.qtekfun.ultimatemail.domain.mail.MailCredentials
import com.qtekfun.ultimatemail.domain.mail.MailServer
import com.qtekfun.ultimatemail.domain.mail.TransportSecurity
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread

internal const val TEST_HOST = "127.0.0.1"
internal const val TEST_USER = "alice@example.test"
internal const val TEST_PASSWORD = "secret"

/**
 * A throwaway certificate for 127.0.0.1, made once per test JVM with the JDK's keytool. The test
 * servers present it and the test client trusts only it, so connections go through the same full
 * validation (chain and host name) as in production; nothing is switched off.
 */
internal object TestTls {
    private const val PASSWORD = "changeit"
    private val keystoreFile: File = File.createTempFile("ultimatemail-test", ".p12").also { it.delete() }

    init {
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val process = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "30", "-dname", "CN=127.0.0.1", "-ext", "san=ip:127.0.0.1",
            "-storetype", "PKCS12", "-keystore", keystoreFile.path,
            "-storepass", PASSWORD, "-keypass", PASSWORD
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "keytool failed: $output" }
        keystoreFile.deleteOnExit()
        // GreenMail reads these once, when its first TLS server starts.
        System.setProperty("greenmail.tls.keystore.file", keystoreFile.path)
        System.setProperty("greenmail.tls.keystore.password", PASSWORD)
        System.setProperty("greenmail.tls.key.password", PASSWORD)
    }

    private val keyStore: KeyStore = KeyStore.getInstance("PKCS12").apply {
        keystoreFile.inputStream().use { load(it, PASSWORD.toCharArray()) }
    }

    /** What the client trusts: only the test certificate. */
    val clientSocketFactory: SSLSocketFactory by lazy {
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore) }
        SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory
    }

    val serverSocketFactory: SSLServerSocketFactory by lazy {
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, PASSWORD.toCharArray()) }
        SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }.serverSocketFactory
    }
}

internal fun trustingTestConfig(
    connectTimeoutMillis: Int = 5_000,
    readTimeoutMillis: Int = 5_000
) = MailClientConfig(
    connectTimeoutMillis = connectTimeoutMillis,
    readTimeoutMillis = readTimeoutMillis,
    sslSocketFactory = TestTls.clientSocketFactory
)

internal fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName(TEST_HOST)).use { it.localPort }

/** GreenMail speaking IMAPS and SMTPS on free ports, with one user. */
internal class GreenMailServer {
    val imapPort = freePort()
    val smtpPort = freePort()
    val greenMail = GreenMail(
        arrayOf(
            ServerSetup(imapPort, TEST_HOST, ServerSetup.PROTOCOL_IMAPS),
            ServerSetup(smtpPort, TEST_HOST, ServerSetup.PROTOCOL_SMTPS)
        )
    )
    val imap = MailServer(TEST_HOST, imapPort, TransportSecurity.TLS)
    val smtp = MailServer(TEST_HOST, smtpPort, TransportSecurity.TLS)
    val credentials = MailCredentials.Password(TEST_USER, TEST_PASSWORD)

    fun start() {
        greenMail.start()
        greenMail.setUser(TEST_USER, TEST_USER, TEST_PASSWORD)
    }

    fun stop() = greenMail.stop()
}

/**
 * A scripted IMAP server over TLS (GreenMail's throwaway certificate) for what GreenMail cannot
 * do: answer NO/BAD on demand, go silent, or drop the connection. [respond] gets the tag and the
 * command line and returns the reply lines, or null to drop the connection. Return an empty list
 * to stay silent.
 */
internal class ScriptedImapServer(
    private val respond: (tag: String, command: String) -> List<String>?
) : AutoCloseable {
    private val socket = TestTls.serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName(TEST_HOST))
    private val clients = CopyOnWriteArrayList<Socket>()
    val port: Int = socket.localPort
    val commands = CopyOnWriteArrayList<String>()

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                clients += client
                thread(isDaemon = true) { serve(client) }
            }
        }
    }

    private fun serve(client: Socket) {
        runCatching {
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
            val writer = client.getOutputStream()
            fun send(line: String) {
                writer.write("$line\r\n".toByteArray(Charsets.US_ASCII))
                writer.flush()
            }
            send("* OK scripted ready")
            while (true) {
                val line = reader.readLine() ?: break
                val tag = line.substringBefore(' ')
                val command = line.substringAfter(' ')
                commands += command.substringBefore(' ').uppercase()
                val reply = respond(tag, command) ?: break
                reply.forEach(::send)
            }
        }
        runCatching { client.close() }
    }

    override fun close() {
        runCatching { socket.close() }
        clients.forEach { runCatching { it.close() } }
    }
}
