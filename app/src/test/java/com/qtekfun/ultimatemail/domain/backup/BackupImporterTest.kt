// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.domain.backup

import com.qtekfun.ultimatemail.data.local.FakeAttachmentStorage
import com.qtekfun.ultimatemail.data.local.account
import com.qtekfun.ultimatemail.data.local.inMemoryDatabase
import com.qtekfun.ultimatemail.data.local.model.AuthType
import com.qtekfun.ultimatemail.data.settings.FakePreferenceStore
import com.qtekfun.ultimatemail.data.settings.SettingsRepository
import com.qtekfun.ultimatemail.domain.account.AccountConnectionTester
import com.qtekfun.ultimatemail.domain.account.AccountCredentials
import com.qtekfun.ultimatemail.domain.account.AccountRemoval
import com.qtekfun.ultimatemail.domain.account.AccountSetup
import com.qtekfun.ultimatemail.domain.account.AccountValidator
import com.qtekfun.ultimatemail.domain.account.ServerAutodetector
import com.qtekfun.ultimatemail.domain.compose.FakeOutboxStorage
import com.qtekfun.ultimatemail.domain.oauth.MemoryClientIds
import com.qtekfun.ultimatemail.domain.settings.AccountSettingsStore
import com.qtekfun.ultimatemail.domain.settings.MemoryOfflineDownloads
import com.qtekfun.ultimatemail.sync.engine.SyncScheduler
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupImporterTest {
    private val db = inMemoryDatabase()
    private val vault = MemoryVault()
    private val scheduler = mockk<SyncScheduler>(relaxed = true)
    private val downloads = MemoryOfflineDownloads()
    private val store = FakePreferenceStore()
    private val settings = SettingsRepository(store)
    private val clientIds = MemoryClientIds()
    private val pending = MemoryPendingChoices()
    private val documents = MemoryDocuments()
    private val crypto = BackupCrypto(FAST_KDF)
    private val setup = AccountSetup(
        db,
        vault,
        AccountValidator(),
        ServerAutodetector(),
        mockk<AccountConnectionTester>(),
        Dispatchers.Unconfined
    )
    private val importer = BackupImporter(
        db,
        setup,
        AccountRemoval(
            db,
            vault,
            FakeAttachmentStorage(),
            FakeOutboxStorage(),
            Dispatchers.Unconfined
        ),
        AccountSettingsStore(db, scheduler, downloads, Dispatchers.Unconfined),
        settings,
        downloads,
        clientIds,
        pending,
        scheduler,
        crypto,
        documents,
        Dispatchers.Unconfined
    )
    private val passphrase = "correct horse"

    @AfterEach
    fun close() = db.close()

    private fun pass() = passphrase.toCharArray()

    private fun put(document: BackupDocument, uri: String = "uri"): String {
        documents.files[uri] = crypto.encrypt(BackupCodec.encode(document), pass())
        return uri
    }

    private suspend fun open(document: BackupDocument): BackupPreview {
        val result = importer.open(put(document), pass())
        return (result as OpenResult.Opened).preview
    }

    private suspend fun accounts() = db.accountDao().observeAll().first()

    @Test
    fun `the preview lists each account with its provider and whether it has credentials`() =
        runTest {
            val preview = open(
                backupDocument(
                    listOf(
                        backupAccount(credentials = AccountCredentials(password = "pw")),
                        backupAccount(email = "bo@example.test"),
                        backupAccount(
                            email = "cy@gmail.test",
                            authType = AuthType.OAUTH_GOOGLE,
                            credentials = AccountCredentials(oauth = SAMPLE_TOKENS)
                        )
                    )
                )
            )

            assertEquals(listOf(true, false, true), preview.entries.map { it.hasCredentials })
            assertEquals(
                listOf(AuthType.PASSWORD, AuthType.PASSWORD, AuthType.OAUTH_GOOGLE),
                preview.entries.map { it.authType }
            )
            assertTrue(preview.entries.all { it.status == PreviewStatus.IMPORTABLE })
            assertTrue(preview.hasSettings)
            assertTrue(accounts().isEmpty())
        }

    @Test
    fun `credentials of the wrong kind for the account do not count`() = runTest {
        val preview = open(
            backupDocument(
                listOf(
                    backupAccount(credentials = AccountCredentials(oauth = SAMPLE_TOKENS)),
                    backupAccount(
                        email = "bo@example.test",
                        authType = AuthType.OAUTH_GOOGLE,
                        credentials = AccountCredentials(password = "pw")
                    ),
                    backupAccount(
                        email = "cy@example.test",
                        credentials = AccountCredentials(password = "")
                    )
                )
            )
        )

        assertEquals(listOf(false, false, false), preview.entries.map { it.hasCredentials })
    }

    @Test
    fun `importing creates the account with everything the file says`() = runTest {
        val preview = open(
            backupDocument(
                listOf(backupAccount(credentials = AccountCredentials(password = "app-pw")))
            )
        )

        val summary = importer.import(preview, setOf(0), withSettings = false)

        assertEquals(
            listOf(
                ImportedAccount(accounts().single().id, "ana@example.test", needsSignIn = false)
            ),
            summary.imported
        )
        val stored = accounts().single()
        assertEquals("Ana Gil", stored.displayName)
        assertEquals("ana@example.test", stored.username)
        assertEquals("imap.example.test", stored.imapHost)
        assertEquals(587, stored.smtpPort)
        assertEquals("Ana\nAcme", stored.signature)
        assertFalse(stored.signatureEnabled)
        assertFalse(stored.signatureBeforeQuote)
        assertEquals(30, stored.offlineWindowDays)
        assertFalse(downloads.isEnabled(stored.id))
        assertEquals("app-pw", vault.saved[stored.id]?.password)
        verify { scheduler.requestSync(stored.id) }
    }

    @Test
    fun `folder choices wait until the folders exist`() = runTest {
        val preview = open(backupDocument(listOf(backupAccount())))

        importer.import(preview, setOf(0), withSettings = false)

        val id = accounts().single().id
        assertEquals(mapOf("INBOX" to true, "Work/Invoices" to false), pending.peek(id))
        assertTrue(db.folderDao().all(id).isEmpty())
    }

    @Test
    fun `an account without credentials is created awaiting sign in and nothing is stored`() =
        runTest {
            val preview = open(backupDocument(listOf(backupAccount())))

            val summary = importer.import(preview, setOf(0), withSettings = false)

            assertEquals(listOf(true), summary.imported.map { it.needsSignIn })
            assertEquals(0, summary.failed)
            val id = accounts().single().id
            assertNull(vault.load(id))
            verify { scheduler.requestSync(id) }
        }

    @Test
    fun `only the selected accounts are imported`() = runTest {
        val preview = open(
            backupDocument(
                listOf(
                    backupAccount("a@example.test"),
                    backupAccount("b@example.test"),
                    backupAccount("c@example.test")
                )
            )
        )

        val summary = importer.import(preview, setOf(0, 2), withSettings = false)

        assertEquals(listOf("a@example.test", "c@example.test"), accounts().map { it.email })
        assertEquals(2, summary.imported.size)
        assertEquals(0, summary.skipped)
    }

    @Test
    fun `an existing account with the same address and server is a duplicate and is left alone`() =
        runTest {
            val existingId = db.accountDao().insert(account("ana@example.test"))
            vault.save(existingId, AccountCredentials(password = "mine"))
            val preview = open(
                backupDocument(
                    listOf(backupAccount(credentials = AccountCredentials(password = "other")))
                )
            )

            val summary = importer.import(preview, setOf(0), withSettings = false)

            assertEquals(PreviewStatus.DUPLICATE, preview.entries.single().status)
            assertEquals(1, summary.skipped)
            assertTrue(summary.imported.isEmpty())
            assertEquals("Ana", accounts().single().displayName)
            assertEquals("mine", vault.saved[existingId]?.password)
        }

    @Test
    fun `duplicates ignore the case of the address and the host`() = runTest {
        db.accountDao().insert(account("ana@example.test"))

        val preview = open(
            backupDocument(
                listOf(backupAccount(email = "ANA@Example.test", imapHost = "IMAP.example.test"))
            )
        )

        assertEquals(PreviewStatus.DUPLICATE, preview.entries.single().status)
    }

    @Test
    fun `the same address on another server is reported as taken`() = runTest {
        db.accountDao().insert(account("ana@example.test"))

        val preview = open(
            backupDocument(listOf(backupAccount(imapHost = "imap.other.test")))
        )

        assertEquals(PreviewStatus.ADDRESS_TAKEN, preview.entries.single().status)
        assertEquals(1, importer.import(preview, setOf(0), false).skipped)
        assertEquals(1, accounts().size)
    }

    @Test
    fun `an account repeated inside the file is imported once`() = runTest {
        val preview = open(
            backupDocument(
                listOf(backupAccount(), backupAccount(), backupAccount("bo@example.test"))
            )
        )

        val summary = importer.import(preview, setOf(0, 1, 2), withSettings = false)

        assertEquals(
            listOf(PreviewStatus.IMPORTABLE, PreviewStatus.DUPLICATE, PreviewStatus.IMPORTABLE),
            preview.entries.map { it.status }
        )
        assertEquals(2, summary.imported.size)
        assertEquals(1, summary.skipped)
        assertEquals(2, accounts().size)
    }

    @Test
    fun `duplicates are judged again when importing`() = runTest {
        val preview = open(backupDocument(listOf(backupAccount())))
        db.accountDao().insert(account("ana@example.test"))

        val summary = importer.import(preview, setOf(0), withSettings = false)

        assertEquals(1, summary.skipped)
        assertEquals(1, accounts().size)
    }

    @Test
    fun `accounts with invalid data are listed but cannot be imported`() = runTest {
        val bad = backupAccount("bad@example.test").copy(
            imap = backupAccount().imap.copy(host = "not a host!", port = 0)
        )
        val badAddress = backupAccount("no-at-sign")
        val preview = open(backupDocument(listOf(bad, badAddress, backupAccount())))

        val summary = importer.import(preview, setOf(0, 1, 2), withSettings = false)

        assertEquals(
            listOf(PreviewStatus.INVALID, PreviewStatus.INVALID, PreviewStatus.IMPORTABLE),
            preview.entries.map { it.status }
        )
        assertEquals(2, summary.skipped)
        assertEquals(listOf("ana@example.test"), accounts().map { it.email })
    }

    @Test
    fun `when the credentials cannot be stored the account is not left behind`() = runTest {
        vault.failure = IOException("keystore unavailable")
        val preview = open(
            backupDocument(listOf(backupAccount(credentials = AccountCredentials(password = "pw"))))
        )

        val summary = importer.import(preview, setOf(0), withSettings = false)

        assertEquals(1, summary.failed)
        assertTrue(summary.imported.isEmpty())
        assertTrue(accounts().isEmpty())
        verify(exactly = 0) { scheduler.requestSync(any()) }
    }

    @Test
    fun `an account that cannot be fully configured is rolled back completely`() = runTest {
        // The decoder allows 4000 characters, the profile rules 1000: the second step fails.
        val long = backupAccount(credentials = AccountCredentials(password = "pw"))
            .copy(signature = "x".repeat(2_000))
        val ok = backupAccount("bo@example.test")
        val preview = open(backupDocument(listOf(long, ok)))

        val summary = importer.import(preview, setOf(0, 1), withSettings = false)

        assertEquals(1, summary.failed)
        assertEquals(listOf("bo@example.test"), accounts().map { it.email })
        assertTrue(vault.saved.values.none { it.password == "pw" })
        assertTrue(pending.stored.keys.none { it == 1L })
        verify(exactly = 0) { scheduler.requestSync(1L) }
    }

    @Test
    fun `app settings are applied only when asked`() = runTest {
        val preview = open(backupDocument(listOf(backupAccount())))

        val without = importer.import(preview, setOf(0), withSettings = false)
        assertFalse(without.settingsApplied)
        assertEquals(com.qtekfun.ultimatemail.data.settings.AppSettings(), settings.current())

        val with = importer.import(preview, emptySet(), withSettings = true)
        assertTrue(with.settingsApplied)
        assertEquals(CUSTOM_SETTINGS, settings.current())
    }

    @Test
    fun `asking for settings from a file without any changes nothing`() = runTest {
        val preview = open(backupDocument(listOf(backupAccount()), settings = null))

        val summary = importer.import(preview, emptySet(), withSettings = true)

        assertFalse(preview.hasSettings)
        assertFalse(summary.settingsApplied)
        assertEquals(com.qtekfun.ultimatemail.data.settings.AppSettings(), settings.current())
    }

    @Test
    fun `client ids are kept when valid and never overwrite the ones of the device`() = runTest {
        clientIds.setMicrosoft("99999999-2222-3333-4444-555555555555")
        val preview = open(
            backupDocument(
                listOf(
                    backupAccount(
                        "g@gmail.test",
                        AuthType.OAUTH_GOOGLE,
                        oauthClientId = "123-abc.apps.googleusercontent.com"
                    ),
                    backupAccount(
                        "m@outlook.test",
                        AuthType.OAUTH_MICROSOFT,
                        oauthClientId = "11111111-2222-3333-4444-555555555555"
                    )
                )
            )
        )

        importer.import(preview, setOf(0, 1), withSettings = false)

        assertEquals("123-abc.apps.googleusercontent.com", clientIds.google())
        assertEquals("99999999-2222-3333-4444-555555555555", clientIds.microsoft())
    }

    @Test
    fun `a client id that does not look like one is ignored`() = runTest {
        val preview = open(
            backupDocument(
                listOf(
                    backupAccount(
                        "g@gmail.test",
                        AuthType.OAUTH_GOOGLE,
                        oauthClientId = "javascript:alert(1)"
                    )
                )
            )
        )

        importer.import(preview, setOf(0), withSettings = false)

        assertNull(clientIds.google())
        assertEquals(1, accounts().size)
    }

    @Test
    fun `a wrong passphrase opens nothing and the array is wiped`() = runTest {
        val uri = put(backupDocument())
        val wrong = "battery staple".toCharArray()

        val result = importer.open(uri, wrong)

        assertEquals(BackupError.WrongPassphraseOrDamaged, (result as OpenResult.Failed).error)
        assertTrue(wrong.all { it == '\u0000' })
        assertTrue(accounts().isEmpty())
    }

    @Test
    fun `the passphrase is wiped after a successful open too`() = runTest {
        val uri = put(backupDocument())
        val typed = pass()

        importer.open(uri, typed)

        assertTrue(typed.all { it == '\u0000' })
    }

    @Test
    fun `a damaged file fails cleanly and imports nothing`() = runTest {
        val uri = put(backupDocument())
        documents.files[uri] =
            documents.files.getValue(uri).also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() }

        val result = importer.open(uri, pass())

        assertEquals(BackupError.WrongPassphraseOrDamaged, (result as OpenResult.Failed).error)
    }

    @Test
    fun `files that are too large or unreadable are refused`() = runTest {
        documents.readResult = DocumentRead.TooLarge
        assertEquals(BackupError.TooLarge, (importer.open("x", pass()) as OpenResult.Failed).error)

        documents.readResult = DocumentRead.Unreadable
        assertEquals(
            BackupError.Unreadable,
            (importer.open("x", pass()) as OpenResult.Failed).error
        )
    }

    @Test
    fun `a file that is not a backup is refused`() = runTest {
        documents.files["x"] = "just some text".toByteArray()

        assertEquals(
            BackupError.NotABackup,
            (importer.open("x", pass()) as OpenResult.Failed).error
        )
    }

    @Test
    fun `a newer format inside a valid container is refused with its version`() = runTest {
        val json = String(
            BackupCodec.encode(backupDocument())
        ).replace("\"formatVersion\":1", "\"formatVersion\":7")
        documents.files["x"] = crypto.encrypt(json.toByteArray(), pass())

        assertEquals(
            BackupError.UnsupportedVersion(7),
            (importer.open("x", pass()) as OpenResult.Failed).error
        )
    }

    @Test
    fun `a valid container with hostile content is refused as malformed`() = runTest {
        documents.files["x"] =
            crypto.encrypt("""{"format":"ultimatemail-backup"}""".toByteArray(), pass())

        assertEquals(BackupError.Malformed, (importer.open("x", pass()) as OpenResult.Failed).error)
        assertTrue(accounts().isEmpty())
    }

    @Test
    fun `an exported backup can be imported into another installation`() = runTest {
        val source = inMemoryDatabase()
        try {
            val sourceId = source.accountDao().insert(
                account("ana@example.test").copy(signature = "Ana", offlineWindowDays = 180)
            )
            val sourceVault = MemoryVault().also {
                it.save(sourceId, AccountCredentials(password = "app-pw"))
            }
            val exporter = BackupExporter(
                source,
                sourceVault,
                settings,
                downloads,
                clientIds,
                crypto,
                documents,
                "1.0.0",
                Dispatchers.Unconfined
            )
            assertTrue(
                exporter.export("out", pass(), includeCredentials = true) is ExportResult.Done
            )

            val preview = (importer.open("out", pass()) as OpenResult.Opened).preview
            val summary = importer.import(preview, setOf(0), withSettings = true)

            assertEquals(listOf(false), summary.imported.map { it.needsSignIn })
            val imported = accounts().single()
            assertEquals("Ana", imported.signature)
            assertEquals(180, imported.offlineWindowDays)
            assertEquals("app-pw", vault.saved[imported.id]?.password)
        } finally {
            source.close()
        }
    }

    @Test
    fun `printing a preview or an entry never shows an address`() = runTest {
        val preview = open(backupDocument())

        assertFalse(preview.toString().contains("example"))
        assertFalse(preview.entries.single().toString().contains("example"))
        assertFalse(ImportedAccount(1, "ana@example.test", true).toString().contains("example"))
    }
}
