// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.testing

import com.qtekfun.ultimatemail.domain.account.AccountConnectionTester
import com.qtekfun.ultimatemail.domain.account.AccountInput
import com.qtekfun.ultimatemail.domain.account.ConnectionFailure
import com.qtekfun.ultimatemail.domain.account.ConnectionTestResult
import com.qtekfun.ultimatemail.domain.mail.MailConnector
import com.qtekfun.ultimatemail.domain.mail.MailCredentials
import com.qtekfun.ultimatemail.domain.mail.MailResult
import com.qtekfun.ultimatemail.domain.mail.MailSender
import com.qtekfun.ultimatemail.domain.mail.MailServer
import com.qtekfun.ultimatemail.domain.mail.MailSession
import com.qtekfun.ultimatemail.domain.mail.OutgoingMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every mail server of a test: one [FakeMailbox] per address, and the switch that turns the
 * network off. The connector, the sender and the connection tester of the app are replaced by
 * the classes below, which only talk to this world, so no test ever opens a socket.
 */
@Singleton
class FakeMailWorld @Inject constructor() {
    private val mailboxes = ConcurrentHashMap<String, FakeMailbox>()

    /** False makes every connection fail like a phone without network. */
    @Volatile
    var online = true

    /** What SMTP accepted, in order. */
    val sent = CopyOnWriteArrayList<OutgoingMessage>()

    /** Creates the mailbox of [address], reachable with [password]. */
    fun mailbox(address: String, password: String): FakeMailbox =
        FakeMailbox(address, password).also { mailboxes[address.lowercase()] = it }

    fun find(address: String): FakeMailbox? = mailboxes[address.lowercase()]

    /** The mailbox [username] may enter with [secret], or the reason it cannot. */
    internal fun open(username: String, secret: String?): MailResult<FakeMailbox> {
        val mailbox = find(username)
        return when {
            !online || mailbox == null -> MailResult.NetworkUnavailable
            secret != mailbox.password -> MailResult.AuthenticationFailed
            else -> MailResult.Success(mailbox)
        }
    }
}

private fun MailCredentials?.secret(): String? = (this as? MailCredentials.Password)?.password

/** IMAP: opens sessions on the [FakeMailWorld]. */
@Singleton
class FakeMailConnector @Inject constructor(private val world: FakeMailWorld) : MailConnector {
    override suspend fun connect(
        server: MailServer,
        credentials: MailCredentials
    ): MailResult<MailSession> {
        val opened = world.open(credentials.username, credentials.secret())
        return when (opened) {
            is MailResult.Success -> MailResult.Success(FakeMailSession(opened.value))
            is MailResult.Failure -> opened
        }
    }
}

/** SMTP: remembers what it was given, unless the network is off or the login is wrong. */
@Singleton
class FakeMailSender @Inject constructor(private val world: FakeMailWorld) : MailSender {
    override suspend fun send(
        server: MailServer,
        credentials: MailCredentials?,
        message: OutgoingMessage
    ): MailResult<String> {
        val name = credentials?.username ?: return MailResult.AuthenticationFailed
        return when (val opened = world.open(name, credentials.secret())) {
            is MailResult.Success -> {
                world.sent += message
                MailResult.Success(message.messageId ?: "<sent@example.com>")
            }

            is MailResult.Failure -> opened
        }
    }
}

/** The "check the connection" step of add account and sign in again, against the world. */
@Singleton
class FakeConnectionTester @Inject constructor(private val world: FakeMailWorld) :
    AccountConnectionTester {
    override suspend fun test(input: AccountInput): ConnectionTestResult {
        val failure = when (world.open(input.email, input.credentials.password)) {
            is MailResult.Success -> return ConnectionTestResult.Success
            MailResult.AuthenticationFailed -> ConnectionFailure.AUTHENTICATION_FAILED
            else -> ConnectionFailure.HOST_UNREACHABLE
        }
        return ConnectionTestResult.Failure(failure)
    }
}
