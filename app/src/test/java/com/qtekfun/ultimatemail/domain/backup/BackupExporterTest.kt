// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.backup

import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.folder
import com.qtekfun.ultimatemail.data.local.inMemoryDatabase
import com.qtekfun.ultimatemail.data.local.message
import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.data.local.model.FolderRole
import com.qtekfun.ultimatemail.data.settings.FakePreferenceStore
import com.qtekfun.ultimatemail.data.settings.SettingsRepository
import com.qtekfun.ultimatemail.data.settings.ThemeMode
import com.qtekfun.ultimatemail.domain.account.AccountCredentials
import com.qtekfun.ultimatemail.domain.oauth.MemoryClientIds
import com.qtekfun.ultimatemail.domain.settings.MemoryOfflineDownloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupExporterTest {
    private val db = inMemoryDatabase()
    private val vault = MemoryVault()
    private val store = FakePreferenceStore()
    private val settings = SettingsRepository(store)
    private val downloads = MemoryOfflineDownloads()
    private val clientIds = MemoryClientIds()
    private val documents = MemoryDocuments()
    private val crypto = BackupCrypto(FAST_KDF)
    private val exporter = BackupExporter(
        db,
        vault,
        settings,
        downloads,
        clientIds,
        crypto,
        documents,
        "9.9.9",
        Dispatchers.Unconfined
    )
    private val passphrase = "correct horse"

    @AfterEach
    fun close() = db.close()

    private fun pass() = passphrase.toCharArray()

    private fun read(uri: String = "uri"): BackupDocument {
        val plain = (crypto.decrypt(documents.files.getValue(uri), pass()) as DecryptResult.Plain)
        return (BackupCodec.decode(plain.bytes) as DecodeResult.Decoded).document
    }

    private suspend fun seedAccount(
        email: String = "ana@example.test",
        authType: AuthType = AuthType.PASSWORD
    ): Long {
        val id = db.accountDao().insert(
            account(email).copy(
                authType = authType,
                signature = "Ana\nAcme",
                signatureEnabled = false,
                signatureBeforeQuote = false,
                offlineWindowDays = 30
            )
        )
        db.folderDao().upsert(
            listOf(
                folder(id),
                folder(id, "Work/Invoices", FolderRole.OTHER).copy(syncEnabled = false),
                folder(id, "Sent", FolderRole.SENT)
            )
        )
        return id
    }

    @Test
    fun `an account is exported with its configuration and folder choices`() = runTest {
        val id = seedAccount()
        downloads.setEnabled(id, false)

        val result = exporter.export("uri", pass(), includeCredentials = false)

        assertEquals(ExportResult.Done(1, credentialsIncluded = false), result)
        val exported = read().accounts.single()
        assertEquals("ana@example.test", exported.email)
        assertEquals("Ana", exported.displayName)
        assertEquals("imap.example.test", exported.imap.host)
        assertEquals(993, exported.imap.port)
        assertEquals("smtp.example.test", exported.smtp.host)
        assertEquals("Ana\nAcme", exported.signature)
        assertFalse(exported.signatureEnabled)
        assertFalse(exported.signatureBeforeQuote)
        assertEquals(30, exported.offlineWindowDays)
        assertFalse(exported.downloadForOffline)
        assertEquals(
            mapOf("INBOX" to true, "Sent" to true, "Work/Invoices" to false),
            exported.folders
        )
        assertEquals("9.9.9", read().appVersion)
        assertEquals(BackupFormat.VERSION, read().formatVersion)
    }

    @Test
    fun `credentials are left out unless the user asks for them`() = runTest {
        val id = seedAccount()
        vault.save(id, AccountCredentials(password = "very-secret-password"))

        exporter.export("uri", pass(), includeCredentials = false)

        assertNull(read().accounts.single().credentials)
        val raw = String(documents.files.getValue("uri"), Charsets.ISO_8859_1)
        assertFalse(raw.contains("very-secret-password"))
    }

    @Test
    fun `credentials go into the encrypted payload when asked, and only there`() = runTest {
        val id = seedAccount()
        val oauthId = seedAccount("bo@gmail.test", AuthType.OAUTH_GOOGLE)
        vault.save(id, AccountCredentials(password = "very-secret-password"))
        vault.save(oauthId, AccountCredentials(oauth = SAMPLE_TOKENS))

        val result = exporter.export("uri", pass(), includeCredentials = true)

        assertEquals(ExportResult.Done(2, credentialsIncluded = true), result)
        val accounts = read().accounts
        assertEquals("very-secret-password", accounts[0].credentials?.password)
        assertEquals(SAMPLE_TOKENS, accounts[1].credentials?.oauth)
        val raw = String(documents.files.getValue("uri"), Charsets.ISO_8859_1)
        assertFalse(raw.contains("very-secret-password"))
        assertFalse(raw.contains("access-token-1"))
        assertFalse(raw.contains("example.test"))
    }

    @Test
    fun `an account whose credentials cannot be read is exported without them`() = runTest {
        seedAccount()

        val result = exporter.export("uri", pass(), includeCredentials = true)

        assertEquals(ExportResult.Done(1, credentialsIncluded = false), result)
        assertNull(read().accounts.single().credentials)
    }

    @Test
    fun `credentials need a passphrase of at least eight characters`() = runTest {
        val id = seedAccount()
        vault.save(id, AccountCredentials(password = "pw"))

        val result = exporter.export("uri", "short".toCharArray(), includeCredentials = true)

        assertEquals(ExportResult.BadPassphrase(PassphraseIssue.TOO_SHORT), result)
        assertTrue(documents.files.isEmpty())
    }

    @Test
    fun `an empty passphrase is never accepted`() = runTest {
        seedAccount()

        val result = exporter.export("uri", CharArray(0), includeCredentials = false)

        assertEquals(ExportResult.BadPassphrase(PassphraseIssue.EMPTY), result)
        assertTrue(documents.files.isEmpty())
    }

    @Test
    fun `a short passphrase is fine without credentials`() = runTest {
        seedAccount()

        val result = exporter.export("uri", "abc".toCharArray(), includeCredentials = false)

        assertTrue(result is ExportResult.Done)
    }

    @Test
    fun `the passphrase is wiped after the export, whatever its outcome`() = runTest {
        seedAccount()
        val ok = pass()
        val rejected = "short".toCharArray()
        val unwritable = pass()
        documents.writeFails = false

        exporter.export("uri", ok, includeCredentials = false)
        exporter.export("uri", rejected, includeCredentials = true)
        documents.writeFails = true
        exporter.export("uri", unwritable, includeCredentials = false)

        assertTrue(ok.all { it == '\u0000' })
        assertTrue(rejected.all { it == '\u0000' })
        assertTrue(unwritable.all { it == '\u0000' })
    }

    @Test
    fun `a location that cannot be written is reported`() = runTest {
        seedAccount()
        documents.writeFails = true

        assertEquals(
            ExportResult.WriteFailed,
            exporter.export("uri", pass(), includeCredentials = false)
        )
    }

    @Test
    fun `the device settings are included and the language is not`() = runTest {
        seedAccount()
        settings.setTheme(ThemeMode.DARK)
        settings.setAmoled(true)

        exporter.export("uri", pass(), includeCredentials = false)

        assertEquals(settings.current(), read().settings)
        val plain = (crypto.decrypt(documents.files.getValue("uri"), pass()) as DecryptResult.Plain)
        assertFalse(String(plain.bytes).contains("language", ignoreCase = true))
    }

    @Test
    fun `settings can be left out`() = runTest {
        seedAccount()

        exporter.export("uri", pass(), includeCredentials = false, includeSettings = false)

        assertNull(read().settings)
    }

    @Test
    fun `OAuth client ids are exported for the account that uses them`() = runTest {
        seedAccount()
        seedAccount("bo@gmail.test", AuthType.OAUTH_GOOGLE)
        seedAccount("cy@outlook.test", AuthType.OAUTH_MICROSOFT)
        clientIds.setGoogle("123-abc.apps.googleusercontent.com")
        clientIds.setMicrosoft("11111111-2222-3333-4444-555555555555")

        exporter.export("uri", pass(), includeCredentials = false)

        val accounts = read().accounts
        assertNull(accounts[0].oauthClientId)
        assertEquals("123-abc.apps.googleusercontent.com", accounts[1].oauthClientId)
        assertEquals("11111111-2222-3333-4444-555555555555", accounts[2].oauthClientId)
    }

    @Test
    fun `no mail is ever exported`() = runTest {
        val id = seedAccount()
        db.messageDao().upsert(
            listOf(
                message(
                    id,
                    1,
                    subject = "SECRET-SUBJECT",
                    bodyText = "SECRET-BODY",
                    senderAddress = "stranger@elsewhere.test",
                    snippet = "SECRET-SNIPPET"
                )
            )
        )

        vault.save(id, AccountCredentials(password = "password-1"))

        exporter.export("uri", pass(), includeCredentials = true)

        val plain = (crypto.decrypt(documents.files.getValue("uri"), pass()) as DecryptResult.Plain)
        val text = String(plain.bytes)
        listOf("SECRET-SUBJECT", "SECRET-BODY", "stranger@elsewhere.test", "SECRET-SNIPPET")
            .forEach { assertFalse(text.contains(it), it) }
    }

    @Test
    fun `with no accounts only the settings are exported`() = runTest {
        val result = exporter.export("uri", pass(), includeCredentials = false)

        assertEquals(ExportResult.Done(0, credentialsIncluded = false), result)
        assertTrue(read().accounts.isEmpty())
    }
}
