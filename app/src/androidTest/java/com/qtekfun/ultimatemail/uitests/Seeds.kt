// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.uitests

import com.qtekfun.ultimatemail.data.local.entity.AccountEntity
import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.data.local.model.ConnectionSecurity
import com.qtekfun.ultimatemail.domain.account.AccountCredentials
import com.qtekfun.ultimatemail.domain.mail.MailFolderRole
import com.qtekfun.ultimatemail.sync.engine.AccountSyncResult
import com.qtekfun.ultimatemail.testing.FakeMailbox
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue

/** The password every seeded mailbox accepts. */
const val SEED_PASSWORD = "correct horse"

/** The inbox of every seeded mailbox. */
const val INBOX = "INBOX"

/** A folder with an accent in its name, to prove that the picker searches without accents. */
const val ACCENTED_FOLDER = "Facturación"

/** An account of a test: its row in the database and the server side behind it. */
class SeededAccount(val id: Long, val mailbox: FakeMailbox)

/** The folders every mailbox of the tests has, with their special-use roles. */
fun FakeMailbox.withStandardFolders(): FakeMailbox = apply {
    folder(INBOX, MailFolderRole.INBOX)
    folder("Sent", MailFolderRole.SENT)
    folder("Drafts", MailFolderRole.DRAFTS)
    folder("Archive", MailFolderRole.ARCHIVE)
    folder("Trash", MailFolderRole.TRASH)
    folder(ACCENTED_FOLDER)
}

/**
 * Puts an account on the device as a finished first sync would have left it: the server has the
 * standard folders and [inboxSubjects] in the inbox (newest first), the account row and its password
 * are stored, and the
 * real sync engine has pulled everything into the in-memory database. With [credentials] false
 * the stored password is then removed and synced once more: the "sign in again" state.
 */
@Suppress("LongParameterList") // A seed is a bag of independent options with defaults.
fun UiTestBase.seedAccount(
    email: String,
    displayName: String,
    inboxSubjects: List<String> = emptyList(),
    signature: String = "",
    credentials: Boolean = true,
    /** Runs on the server side before the first sync: more messages, with flags or attachments. */
    onServer: FakeMailbox.() -> Unit = {}
): SeededAccount {
    val mailbox = world.mailbox(email, SEED_PASSWORD).withStandardFolders()
    inboxSubjects.forEachIndexed { index, subject ->
        mailbox.deliver(INBOX, subject, clock.instant().minus(Duration.ofHours(index + 1L)))
    }
    mailbox.onServer()
    val id = runBlocking {
        val account = AccountEntity(
            email = email,
            displayName = displayName,
            username = email,
            authType = AuthType.PASSWORD,
            imapHost = "imap.example.com",
            imapPort = IMAP_PORT,
            imapSecurity = ConnectionSecurity.TLS,
            smtpHost = "smtp.example.com",
            smtpPort = SMTP_PORT,
            smtpSecurity = ConnectionSecurity.STARTTLS,
            signature = signature,
            signatureEnabled = signature.isNotEmpty()
        )
        database.accountDao().insert(account).also {
            vault.save(it, AccountCredentials(password = SEED_PASSWORD))
        }
    }
    val result = runBlocking { engine.sync(id, userInitiated = true) }
    assertTrue(
        "The first sync of the seeded account should work, not $result",
        result is AccountSyncResult.Synced
    )
    if (!credentials) {
        // The password was changed on the server (or never imported): the mail already on the
        // device stays, but the next sync finds out that the account has to sign in again.
        runBlocking {
            vault.delete(id)
            engine.sync(id, userInitiated = true)
        }
    }
    return SeededAccount(id, mailbox)
}

private const val IMAP_PORT = 993
private const val SMTP_PORT = 587
